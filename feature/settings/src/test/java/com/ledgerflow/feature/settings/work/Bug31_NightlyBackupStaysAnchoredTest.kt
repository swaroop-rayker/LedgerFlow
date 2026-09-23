package com.ledgerflow.feature.settings.work

import android.content.Context
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.Configuration
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ListenableWorker
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.WorkManagerTestInitHelper
import com.google.common.truth.Truth.assertThat
import com.ledgerflow.core.common.time.Clock
import com.ledgerflow.core.domain.backup.BackupRepository
import com.ledgerflow.core.domain.backup.NightlyBackupOutcome
import com.ledgerflow.core.testing.backup.FakeBackupRepository
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * BUG31 — the "nightly" backup drifted to whatever hour it last ran.
 *
 * ADR-0027 scheduled a one-day `PeriodicWorkRequest`. WorkManager counts that
 * day from the moment the previous run finished, so the pass kept the hour of
 * its last run; on the owner's phone it settled at about 21:50, and two nights
 * of checking found no overnight backup because none was ever due.
 *
 * Run against a **real, in-memory WorkManager** rather than a fake, because the
 * two facts the fix rests on are WorkManager's own bookkeeping, read from its
 * bytecode: an update to a running request is applied to the next run without
 * stopping this one, and the override it sets survives the end-of-run reset.
 * A fake would restate those assumptions instead of testing them.
 *
 * Both clocks — the worker's and WorkManager's — are the same fixed instant,
 * so "the next run" is computed from the moment the test says the pass ran.
 * Times are local to whatever zone the machine is in; `BackupTimeTest` is
 * where zones, gaps and overlaps are pinned.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [ROBOLECTRIC_SDK])
class Bug31_NightlyBackupStaysAnchoredTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val zone: ZoneId = ZoneId.systemDefault()
    private val repository = FakeBackupRepository()

    /** What the worker is built with; a test that needs a pass to hang swaps it. */
    private var backups: BackupRepository = repository

    @Volatile
    private var now: Long = local(2026, 9, 23, 21, 50)
    private val clock = Clock { now }

    private lateinit var workManager: WorkManager

    @Before
    fun setUp() {
        val configuration = Configuration.Builder()
            .setExecutor(SynchronousExecutor())
            .setTaskExecutor(SynchronousExecutor())
            .setClock(
                object : androidx.work.Clock {
                    override fun currentTimeMillis(): Long = now
                },
            )
            .setWorkerFactory(
                object : WorkerFactory() {
                    override fun createWorker(
                        appContext: Context,
                        workerClassName: String,
                        workerParameters: WorkerParameters,
                    ): ListenableWorker = NightlyBackupWorker(appContext, workerParameters, backups, clock)
                },
            )
            .build()
        WorkManagerTestInitHelper.initializeTestWorkManager(context, configuration)
        workManager = WorkManager.getInstance(context)
    }

    /** Each test's WorkManager has its own in-memory database; left open, Robolectric reports it as a leak. */
    @After
    fun tearDown() {
        WorkManagerTestInitHelper.closeWorkDatabase()
    }

    @Test
    fun aFreshSchedule_aimsAtThreeTheNextMorning() {
        NightlyBackupWorker.schedule(context, clock)

        assertThat(scheduled().nextScheduleTimeMillis).isEqualTo(local(2026, 9, 24, 3, 0))
    }

    /**
     * The bug. Doze held last night's pass until 06:40; unfixed, the next run
     * is due at 06:40 tomorrow, and the one after that wherever *that* lands.
     */
    @Test
    fun Bug31_aPassHeldUntilMorning_aimsItsSuccessorAtThree_notADayAfterItself() {
        NightlyBackupWorker.schedule(context, clock)
        val ranAt = local(2026, 9, 24, 6, 40)

        runThePass(at = ranAt)

        val next = scheduled()
        assertThat(repository.nightlyRuns).isEqualTo(1)
        assertThat(next.state).isEqualTo(WorkInfo.State.ENQUEUED)
        assertThat(next.nextScheduleTimeMillis).isEqualTo(local(2026, 9, 25, 3, 0))
        assertThat(next.nextScheduleTimeMillis).isNotEqualTo(ranAt + TimeUnit.DAYS.toMillis(1))
        // The re-aim replaces the whole request; it must not shed the constraint.
        assertThat(next.constraints.requiresBatteryNotLow()).isTrue()
    }

    /** Aimed before the pass does anything, so a pass that fails still points at 03:00. */
    @Test
    fun aPassThatFails_stillAimsItsSuccessorAtThree() {
        repository.nightlyOutcome = NightlyBackupOutcome.WriteFailed
        NightlyBackupWorker.schedule(context, clock)

        runThePass(at = local(2026, 9, 24, 3, 20))

        assertThat(scheduled().nextScheduleTimeMillis).isEqualTo(local(2026, 9, 25, 3, 0))
    }

    /**
     * Aimed **first**: a pass the system stops in the middle of the backup has
     * already pointed its successor at 03:00. The stop stands in for the
     * process dying mid-pass, which is how ADR-0027's first night ended; a
     * re-aim placed after the backup never runs here, and the next run would be
     * the stale aim instead.
     */
    @Test
    fun aPassStoppedMidBackup_hasAlreadyAimedItsSuccessor() {
        val started = CompletableDeferred<Unit>()
        backups = object : BackupRepository by repository {
            override suspend fun backUpNightly(): NightlyBackupOutcome {
                started.complete(Unit)
                awaitCancellation()
            }
        }
        NightlyBackupWorker.schedule(context, clock)
        now = local(2026, 9, 24, 3, 20)
        val id = scheduled().id

        startThePass()
        awaitUntil("the pass never started") { started.isCompleted }
        driver().stopRunningWorkWithReason(id, WorkInfo.STOP_REASON_CONSTRAINT_BATTERY_NOT_LOW)
        awaitUntil("the stopped pass was never put back") { scheduled().state == WorkInfo.State.ENQUEUED }

        assertThat(scheduled().nextScheduleTimeMillis).isEqualTo(local(2026, 9, 25, 3, 0))
    }

    /**
     * RollupWorker's trap, in this schedule's form: opening the app at 05:00,
     * while Doze is still holding the 03:00 pass, must not re-aim it at
     * tomorrow. That would skip a night for looking at the phone.
     */
    @Test
    fun openingTheAppWhileAPassIsOverdue_doesNotPushItToTomorrow() {
        NightlyBackupWorker.schedule(context, clock)
        val first = scheduled()

        now = local(2026, 9, 24, 5, 0)
        NightlyBackupWorker.schedule(context, clock)

        val after = scheduled()
        assertThat(after.id).isEqualTo(first.id)
        assertThat(after.nextScheduleTimeMillis).isEqualTo(local(2026, 9, 24, 3, 0))
    }

    /** An install that already has the drifting schedule loses it, and has only the aimed one. */
    @Test
    fun scheduling_retiresTheDriftingSchedule() {
        val legacy = PeriodicWorkRequestBuilder<NightlyBackupWorker>(1, TimeUnit.DAYS)
            .setConstraints(Constraints.Builder().setRequiresBatteryNotLow(true).build())
            .build()
        workManager.enqueueUniquePeriodicWork(
            NightlyBackupWorker.LEGACY_UNIQUE_NAME,
            ExistingPeriodicWorkPolicy.KEEP,
            legacy,
        ).result.get()

        NightlyBackupWorker.schedule(context, clock)

        assertThat(workManager.getWorkInfoById(legacy.id).get()?.state).isEqualTo(WorkInfo.State.CANCELLED)
        assertThat(scheduled().state).isEqualTo(WorkInfo.State.ENQUEUED)
    }

    private fun scheduled(): WorkInfo =
        workManager.getWorkInfosForUniqueWork(NightlyBackupWorker.UNIQUE_NAME).get().single()

    /**
     * Lets the pass run as though the system started it at [at], and waits for
     * WorkManager to have finished with it — back to `ENQUEUED`, having run once.
     */
    private fun runThePass(at: Long) {
        now = at
        startThePass()
        awaitUntil("the pass never finished") {
            repository.lastNightly.value != null && scheduled().state == WorkInfo.State.ENQUEUED
        }
    }

    /**
     * The test driver holds a periodic request with a schedule override until
     * its period is declared met, as well as its constraints; it then winds the
     * next-run time back to "now" and starts it. What the pass leaves behind is
     * what these tests read, and the driver does not write that.
     */
    private fun startThePass() {
        val id = scheduled().id
        driver().setAllConstraintsMet(id)
        driver().setPeriodDelayMet(id)
    }

    private fun driver() = requireNotNull(WorkManagerTestInitHelper.getTestDriver(context)) { "no test driver" }

    private fun awaitUntil(failure: String, condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        while (!condition()) {
            check(System.nanoTime() < deadline) { "$failure: ${scheduled().state}" }
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(10)
        }
    }

    private fun local(year: Int, month: Int, day: Int, hour: Int, minute: Int): Long =
        LocalDateTime.of(year, month, day, hour, minute).atZone(zone).toInstant().toEpochMilli()
}

private const val ROBOLECTRIC_SDK = 34
