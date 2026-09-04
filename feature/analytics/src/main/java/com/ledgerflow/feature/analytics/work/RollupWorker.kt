package com.ledgerflow.feature.analytics.work

import android.content.Context
import android.util.Log
import androidx.hilt.work.HiltWorker
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.ledgerflow.core.domain.usecase.BackfillRollupsUseCase
import com.ledgerflow.core.domain.usecase.ReconcileRollupsUseCase
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import java.util.concurrent.TimeUnit

/**
 * The reconciliation half of ADR-0006, on a nightly cadence.
 *
 * The incremental half is not here and is not callable from here: it runs inside
 * the approval, soft-delete and restore transactions, because a rollup update
 * that can be invoked separately is one that can be forgotten. This worker's
 * only job is to run the same recompute over every date and record what it had
 * to fix.
 *
 * **It runs with no Activity alive,** which is the case `CLAUDE.md` §7 is
 * emphatic about: `VaultSession.requireDatabase()` throws in that process, the
 * throw lands in a `runCatching`, and the work reports success having done
 * nothing (BUG13). `DefaultRollupRepository` therefore opens the vault through
 * `openForBackgroundWork()`, and returns zero rather than pretending when the
 * vault cannot be opened at all.
 *
 * **Never throws** (`CLAUDE.md` §8, BUG7(e)). A worker that throws is a crash in
 * a background process; this returns `retry` and lets WorkManager back off.
 *
 * **Requires the device to be idle and charging.** Reconciliation rewrites every
 * bucket in the table, so it is exactly the kind of work that should never
 * compete with the user — and unlike ingest, nothing is waiting on it. A
 * reconciliation that runs a day late costs nothing, because the incremental
 * path has been keeping the table correct all along; the pass exists for the
 * case where it did not.
 */
@HiltWorker
public class RollupWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val reconcile: ReconcileRollupsUseCase,
    private val backfill: BackfillRollupsUseCase,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result = runCatching {
        // **Two jobs, one routine.** A cold run fills a rollup that has never
        // been built and returns zero the moment one has; the nightly run
        // repairs drift. Splitting the *decision* rather than the recompute is
        // what keeps ADR-0006's "one routine" true.
        if (inputData.getBoolean(KEY_COLD_START, false)) {
            val filled = backfill()
            if (filled > 0) {
                Log.i(TAG, "Cold rollup filled: $filled bucket(s).")
            }
            return@runCatching Result.success()
        }

        val repaired = reconcile()
        if (repaired > 0) {
            // Not shown to the user: the condition has already healed and there
            // is nothing for them to do. But a non-zero count on a healthy
            // install means the incremental path has a bug, so it is recorded
            // in `app_meta` for the P5 diagnostics screen and logged here.
            Log.w(TAG, "Rollup reconciliation repaired $repaired bucket(s).")
        }
        Result.success()
    }.getOrElse { error ->
        Log.e(TAG, "Rollup reconciliation failed", error)
        Result.retry()
    }

    public companion object {
        private const val TAG = "RollupWorker"
        private const val UNIQUE_NAME = "lf-rollup-reconcile"
        private const val COLD_NAME = "lf-rollup-cold-fill"
        private const val KEY_COLD_START = "coldStart"

        /**
         * Fill a rollup that has never been built, **without waiting for idle**.
         *
         * `MIGRATION_8_9` creates `daily_rollup` empty and leaves the filling to
         * the nightly pass. That pass requires the device to be idle *and*
         * charging — correct for drift repair, wrong for a cold cache, because
         * until it runs every analytics figure silently omits every entry
         * approved before the migration. On the owner's phone it had never run,
         * and two real credits were missing from the two-book view while the
         * Ledger showed them plainly.
         *
         * No constraints, and `KEEP` so repeated cold starts do not pile up.
         * The work is a metadata read on every install that has already been
         * reconciled.
         */
        public fun fillIfCold(context: Context) {
            val request = OneTimeWorkRequestBuilder<RollupWorker>()
                .setInputData(Data.Builder().putBoolean(KEY_COLD_START, true).build())
                .build()

            WorkManager.getInstance(context).enqueueUniqueWork(
                COLD_NAME,
                ExistingWorkPolicy.KEEP,
                request,
            )
        }

        /**
         * Schedules the nightly pass, keeping any existing schedule.
         *
         * `KEEP` rather than `UPDATE`: re-registering on every cold start with
         * `UPDATE` resets the period, so on a device the user opens daily the
         * pass would be perpetually deferred and never actually run — a
         * scheduled job that silently never fires, which is the same shape of
         * defect as a guard that cannot fail.
         */
        public fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<RollupWorker>(1, TimeUnit.DAYS)
                .setConstraints(
                    Constraints.Builder()
                        .setRequiresDeviceIdle(true)
                        .setRequiresCharging(true)
                        .build(),
                )
                .build()

            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                UNIQUE_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                request,
            )
        }
    }
}
