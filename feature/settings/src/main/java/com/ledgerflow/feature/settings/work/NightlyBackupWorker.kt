package com.ledgerflow.feature.settings.work

import android.content.Context
import android.util.Log
import androidx.hilt.work.HiltWorker
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.ledgerflow.core.domain.backup.BackupRepository
import com.ledgerflow.core.domain.backup.NightlyBackupOutcome
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import java.util.concurrent.TimeUnit

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
 */
@HiltWorker
public class NightlyBackupWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val backups: BackupRepository,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result = runCatching {
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

    public companion object {
        private const val TAG = "NightlyBackupWorker"
        private const val UNIQUE_NAME = "nightly-backup"

        /**
         * Schedules the pass, keeping any existing schedule.
         *
         * `KEEP` rather than `UPDATE` for the reason the rollup pass documents:
         * re-registering on every cold start with `UPDATE` resets the period, so
         * on a phone opened daily the work would be perpetually deferred and
         * never once run.
         *
         * Scheduling happens whether or not the install is enrolled. An
         * unenrolled run is one metadata read that reports a skip, and making
         * the schedule conditional would mean enrolment had to remember to
         * start it — a state the app could get wrong in the direction of "no
         * backups, silently".
         */
        public fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<NightlyBackupWorker>(1, TimeUnit.DAYS)
                .setConstraints(Constraints.Builder().setRequiresBatteryNotLow(true).build())
                .build()

            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                UNIQUE_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                request,
            )
        }
    }
}
