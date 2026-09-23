package com.ledgerflow.feature.settings.work

import android.content.Context
import android.util.Log
import androidx.hilt.work.HiltWorker
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequest
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.google.common.util.concurrent.ListenableFuture
import com.ledgerflow.core.common.time.Clock
import com.ledgerflow.core.domain.backup.BackupRepository
import com.ledgerflow.core.domain.backup.NightlyBackupOutcome
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine

/**
 * The nightly backup (ADR-0027).
 *
 * **It has no phrase and never will.** The vault holds the public half of a key
 * derived from the user's 24 words; this seals a `.lfbk` to it and cannot open
 * what it wrote. That is the property the whole design exists for, and it is
 * why this can run at 3 a.m. at all — ADR-0025 struck the original nightly
 * worker precisely because a phrase-keyed backup cannot be written unattended.
 *
 * **It runs with no Activity alive** (`CLAUDE.md` §7): the repository opens the
 * vault through `openForBackgroundWork()`, and reports a skip rather than
 * pretending when it cannot.
 *
 * **Never throws** (§8, BUG7(e)): a throw here is a crash in a background
 * process. **Nothing asks for a retry either** — a failed pass reports success
 * and waits for the next night. That is not indifference: a retry chain is how
 * the first failure on the owner's phone ended up wedged in WorkManager's
 * RUNNING state, which schedules no job at all, so the work would never have
 * run again without the app being opened. The pass runs daily; tomorrow is the
 * retry, and every attempt is recorded in the vault so a failure can be read
 * the next morning rather than lost with the log.
 *
 * **Battery not low, and nothing else.** Not "charging and idle" like the
 * rollup pass: a user who never charges overnight would simply never be backed
 * up, and the whole point is that this happens without being thought about.
 * Writing one file is small work. No network constraint exists because no
 * network is used — the folder may be a cloud provider's, and syncing it is
 * that provider's business, not this app's (Law 6).
 *
 * **Aimed at 03:00, decided by Android** (ADR-0027, amended 2026-09-23). A
 * periodic request of one day, whose next run each pass points at the next
 * 03:00 ([BackupTime]); without that the day is counted from the last run and
 * the pass drifts to any hour (§8 BUG31). 03:00 is the earliest it may start,
 * not a promise: Doze defers it to a maintenance window, often until the phone
 * is picked up. Periodic rather than a chain of one-time requests because a
 * chain can be lost by one run that dies before enqueuing its successor; this
 * can only drift for a day.
 */
@HiltWorker
public class NightlyBackupWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val backups: BackupRepository,
    private val clock: Clock,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        aimTheNextPass()
        return pass()
    }

    private suspend fun pass(): Result = runCatching {
        when (val outcome = backups.backUpNightly()) {
            is NightlyBackupOutcome.Done -> {
                // Counts only: never a file's contents, never a row.
                Log.i(
                    TAG,
                    "Nightly backup: ${outcome.rows} rows, ${outcome.imagesWritten} images written, " +
                        "${outcome.imagesFailed} images failed, ${outcome.olderBackupsRemoved} rotated out.",
                )
                Result.success()
            }

            is NightlyBackupOutcome.Skipped -> {
                Log.i(TAG, "Nightly backup skipped: ${outcome.reason}")
                Result.success()
            }

            NightlyBackupOutcome.WriteFailed -> {
                // Success, not retry, and deliberately (owner, 2026-09-22).
                // A retry chain is how the very first night's failure ended up
                // wedged: three attempts, then the process died mid-run and the
                // work sat in RUNNING, which schedules no job at all — so it
                // would never have run again without the app being opened. This
                // job runs daily anyway, so the next period is the retry, and
                // the attempt is recorded in the vault either way.
                Log.w(TAG, "Nightly backup could not be written; nothing was recorded. Waiting for the next pass.")
                Result.success()
            }
        }
    }.getOrElse { error ->
        // Same reasoning as a write failure: tomorrow is the retry.
        Log.e(TAG, "Nightly backup failed", error)
        Result.success()
    }

    /**
     * Points the next pass at the next 03:00 — **before** this one does anything.
     *
     * A periodic request counts its day from the moment a run finishes, so on
     * its own this pass drifted to whenever the last one happened, and on the
     * owner's phone settled at about 21:50 with no relationship to night at all
     * (§8 BUG31). The override moves only the *next* run, so every run has to
     * set it again, and this is where that happens.
     *
     * First rather than last, because the pass is the part that can die: the
     * wedge ADR-0027's amendment records was a process killed mid-pass. Aimed
     * first, a pass that never finishes has already pointed its successor at
     * 03:00. If this call itself fails, the next run follows this one by a day,
     * and the run after that is back on the hour — the schedule is a periodic
     * request either way, so a failure costs a drift, never the backups.
     *
     * Updating a running request does not stop it: WorkManager applies the
     * change to the next run (`UpdateResult.APPLIED_FOR_NEXT_RUN`) and, because
     * the update bumps the override's generation, does not clear it when this
     * run completes. `Bug31_NightlyBackupStaysAnchoredTest` holds both of those
     * against a real WorkManager.
     */
    private suspend fun aimTheNextPass() {
        runCatching {
            WorkManager.getInstance(applicationContext)
                .updateWork(request(BackupTime.nextAfter(clock.nowMillis()), id))
                .awaitResult()
        }.onFailure { error ->
            Log.w(TAG, "Could not aim the next pass at 03:00; it will follow this one by a day.", error)
        }
    }

    public companion object {
        private const val TAG = "NightlyBackupWorker"

        /**
         * The schedule's name since it was aimed at 03:00 (2026-09-23).
         *
         * A new name rather than the old one, because nothing else migrates an
         * existing install: `KEEP` leaves the drifting request exactly as it
         * is, forever, and `UPDATE` on every cold start is the trap described
         * on [schedule].
         */
        internal const val UNIQUE_NAME = "nightly-backup-0300"

        /**
         * The drifting schedule (ADR-0027 as first built, 1 day from the last
         * run). Cancelled on every cold start: once is enough, and cancelling
         * work that no longer exists is a no-op, which is cheaper than
         * remembering whether it has been done.
         */
        internal const val LEGACY_UNIQUE_NAME = "nightly-backup"

        /**
         * Schedules the pass, keeping any existing schedule, and retires the
         * drifting one.
         *
         * `KEEP` rather than `UPDATE` for the reason the rollup pass documents,
         * and it bites here too: re-registering on every cold start would
         * re-aim the pass each time the app is opened. That is harmless before
         * 03:00 and not after it — a pass Doze is still holding at 06:00 would
         * be pushed to tomorrow by the user opening the app, which is a night
         * skipped for looking at it. The pass re-aims itself instead
         * ([aimTheNextPass]), so the only aim this sets is the first.
         *
         * Scheduling happens whether or not the install is enrolled. An
         * unenrolled run is one metadata read that reports a skip, and making
         * the schedule conditional would mean enrolment had to remember to
         * start it — a state the app could get wrong in the direction of "no
         * backups, silently".
         */
        public fun schedule(context: Context, clock: Clock = Clock.System) {
            val workManager = WorkManager.getInstance(context)
            workManager.cancelUniqueWork(LEGACY_UNIQUE_NAME)
            workManager.enqueueUniquePeriodicWork(
                UNIQUE_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                request(BackupTime.nextAfter(clock.nowMillis())),
            )
        }

        /**
         * One day, battery not low, and the next run at [nextRunAt].
         *
         * The period and the constraint are the same on the first schedule and
         * on every re-aim, because an update replaces the whole request: a
         * constraint set here only is one the first re-aim would drop.
         */
        private fun request(nextRunAt: Long, id: UUID? = null): PeriodicWorkRequest =
            PeriodicWorkRequestBuilder<NightlyBackupWorker>(1, TimeUnit.DAYS)
                .setConstraints(Constraints.Builder().setRequiresBatteryNotLow(true).build())
                .setNextScheduleTimeOverride(nextRunAt)
                .apply { if (id != null) setId(id) }
                .build()
    }
}

/**
 * Waits for a WorkManager future without blocking the worker's thread.
 *
 * Written here rather than taken from `concurrent-futures-ktx`, which WorkManager
 * carries at runtime scope only: using it would mean a new compile coordinate
 * for ten lines.
 */
private suspend fun <T> ListenableFuture<T>.awaitResult(): T = suspendCancellableCoroutine { continuation ->
    addListener(
        {
            runCatching { get() }
                .onSuccess { continuation.resume(it) }
                .onFailure { continuation.resumeWithException(it.cause ?: it) }
        },
        Runnable::run,
    )
    continuation.invokeOnCancellation { cancel(false) }
}
