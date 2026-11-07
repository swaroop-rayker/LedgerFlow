package com.ledgerflow.feature.settings.work

import android.content.Context
import android.util.Log
import androidx.hilt.work.HiltWorker
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.ledgerflow.core.domain.drive.CopyBackupsToDriveUseCase
import com.ledgerflow.core.domain.drive.DriveOutcome
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import java.util.concurrent.TimeUnit

/**
 * Copies the nightly backup to Google Drive (ADR-0034 §5), **after** the
 * nightly pass and never inside it: a Drive failure cannot touch the backup on
 * the phone, and `lastBackupAt` never waits for the network.
 *
 * Any network, mobile data included (owner, 2026-11-07). A one-time request,
 * replaced by each new one, so at most one copy is ever queued. A failure Drive
 * might get over (no network, a 429, a 5xx, a checksum that did not match) is
 * retried with backoff up to [MAX_ATTEMPTS]; after that the next night tries
 * again. **Never throws** (§8 BUG7(e)), and never shows UI: a grant that needs
 * consent is recorded as "Sign in again" for the Drive page to show.
 */
@HiltWorker
public class DriveUploadWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val copyToDrive: CopyBackupsToDriveUseCase,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result = runCatching {
        when (val outcome = copyToDrive()) {
            // Counts only: never a file's contents.
            is DriveOutcome.Copied -> Result.success().also {
                Log.i(TAG, "Drive: ${outcome.backups} backups, ${outcome.attachments} receipts copied.")
            }
            is DriveOutcome.Failed -> if (outcome.retryable && runAttemptCount < MAX_ATTEMPTS) {
                Result.retry()
            } else {
                Result.success()
            }
            DriveOutcome.Off,
            DriveOutcome.NothingToCopy,
            DriveOutcome.NeedsSignIn,
            is DriveOutcome.Unavailable,
            -> Result.success().also { Log.i(TAG, "Drive: ${outcome.record()}") }
        }
    }.getOrElse { error ->
        Log.e(TAG, "Drive copy failed", error)
        Result.success()
    }

    public companion object {
        private const val TAG = "DriveUploadWorker"
        private const val UNIQUE_NAME = "drive-upload"

        /** Five tries with backoff from 30 s covers a dropped connection; the next night covers the rest. */
        internal const val MAX_ATTEMPTS = 5
        private const val BACKOFF_SECONDS = 30L

        /**
         * Queues a copy, replacing any already queued. Called after a nightly
         * pass writes a backup, when Drive backup is turned on, and by Copy now.
         * Cheap when Drive backup is off: one metadata read that says so.
         */
        public fun enqueue(context: Context) {
            val request = OneTimeWorkRequestBuilder<DriveUploadWorker>()
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, BACKOFF_SECONDS, TimeUnit.SECONDS)
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(UNIQUE_NAME, ExistingWorkPolicy.REPLACE, request)
        }
    }
}
