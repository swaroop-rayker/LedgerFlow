package com.ledgerflow

import android.app.Application
import android.content.pm.ApplicationInfo
import android.os.Build
import android.os.StrictMode
import android.os.strictmode.Violation
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import com.ledgerflow.feature.analytics.work.RollupWorker
import com.ledgerflow.feature.budget.notify.BudgetNotifications
import com.ledgerflow.feature.ingest.notify.InboxNotifications
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject

/**
 * Application entry point and Hilt's object-graph root.
 *
 * The crash handler arrives with the step that introduces it. What is here now
 * is the `@HiltAndroidApp` trigger, StrictMode, and WorkManager's configuration.
 *
 * **WorkManager is configured here rather than initialised by its manifest
 * provider**, and the provider is removed in the manifest for that reason. Its
 * default initialiser runs at content-provider time and builds workers with a
 * factory that knows nothing about Hilt, which would leave `ParseIngestWorker`
 * unable to reach the repositories it is constructed with. Providing a
 * [Configuration] instead defers initialisation to the first `getInstance` call
 * and hands WorkManager the Hilt factory -- which also keeps its database open
 * off the cold-start path (§11).
 */
@HiltAndroidApp
public class LedgerFlowApplication : Application(), Configuration.Provider {

    @Inject internal lateinit var workerFactory: HiltWorkerFactory

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(workerFactory)
            .build()

    override fun onCreate() {
        super.onCreate()
        if (isDebuggable()) enableStrictMode()
        // SPEC.md §5.1's `inbox_high`, created before the first message rather
        // than before the first post: a channel that does not exist yet cannot
        // be found and configured in system settings, and the user should be
        // able to mute or unmute it without first receiving a bank SMS to
        // conjure it. `onCreate` runs for every process entry -- a
        // receiver-only wake included -- so the background path is covered by
        // this same call, and `ensureChannel` collapses the repeats.
        InboxNotifications.ensureChannel(this)
        // ADR-0006's reconciliation, once a day, idle and charging. Scheduling
        // is idempotent and cheap -- `enqueueUniquePeriodicWork` with `KEEP`
        // does nothing when a schedule already exists, which is the whole
        // reason it is `KEEP` and not `UPDATE`: re-registering with `UPDATE` on
        // every cold start resets the period, so on a phone the user opens
        // daily the pass would be perpetually deferred and never once run.
        RollupWorker.schedule(this)
        // The nightly pass above waits for idle and charging; this does not,
        // because a rollup that has never been built makes the whole Analytics
        // screen wrong until it is. No-op after the first successful run.
        RollupWorker.fillIfCold(this)
        // §5.7's alert channel, created before the first crossing for the same
        // reason `inbox_high` is: a channel that does not exist yet cannot be
        // found and muted in system settings, and the user should be able to
        // turn budget alerts off without first going over a budget to conjure
        // the switch.
        BudgetNotifications.ensureChannel(this)
    }

    /**
     * BUG7's tripwire, debug builds only.
     *
     * It guards the thing that matters most in this app: the unlock flow does
     * file I/O, 2048 rounds of PBKDF2 and a SQLCipher database open, and every
     * one of those must stay on the injected IO dispatcher. Drift back onto the
     * main thread should kill the debug build immediately rather than ship an
     * ANR to a real device.
     *
     * **`penaltyDeath()` is deliberately not used, and this is not a softening.**
     * A bare `penaltyDeath` makes the app unusable on this project's own test
     * device: Samsung's framework reads from disk on the main thread during
     * every `handleResumeActivity`, in code we neither call nor can avoid --
     *
     * ```
     * DiskReadViolation
     *   at android.app.ContextImpl.deleteSharedPreferences
     *   at android.app.IdsController.openIdsWindow
     *   at android.app.ActivityThread.handleResumeActivity
     * ```
     *
     * A tripwire that fires on someone else's code is a tripwire people switch
     * off, which would cost us the whole check. So the policy logs everything
     * and a listener re-throws only violations whose stack actually contains
     * LedgerFlow frames -- strictly *more* precise than `penaltyDeath`, not less
     * strict, because our own main-thread I/O still dies on the spot.
     *
     * `penaltyListener` needs API 28; below that the violations are logged and
     * the emulator/CI matrix at API 26 is where that gap gets covered.
     */
    private fun enableStrictMode() {
        val threadPolicy = StrictMode.ThreadPolicy.Builder()
            .detectDiskReads()
            .detectDiskWrites()
            .detectNetwork()
            .detectCustomSlowCalls()
            .penaltyLog()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            threadPolicy.penaltyListener(mainExecutor) { violation ->
                if (violation.isOurs()) throw violation
            }
        }
        StrictMode.setThreadPolicy(threadPolicy.build())

        StrictMode.setVmPolicy(
            StrictMode.VmPolicy.Builder()
                .detectLeakedSqlLiteObjects()
                .detectLeakedClosableObjects()
                .detectActivityLeaks()
                .penaltyLog()
                .build(),
        )
    }

    private fun Violation.isOurs(): Boolean =
        stackTrace.any { it.className.startsWith(PACKAGE_PREFIX) }

    private fun isDebuggable(): Boolean =
        (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0

    private companion object {
        private const val PACKAGE_PREFIX = "com.ledgerflow"
    }
}
