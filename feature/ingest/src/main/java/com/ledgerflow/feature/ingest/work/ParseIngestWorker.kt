package com.ledgerflow.feature.ingest.work

import android.content.Context
import android.util.Log
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.ledgerflow.core.domain.usecase.TriageCapturedIngestUseCase
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject

/**
 * The work a capture adapter must not do (SPEC.md §5.1, §5.2).
 *
 * The receiver has ~10 seconds before the system kills it, so it writes the raw
 * row and enqueues this. Everything that needs a database lookup happens here,
 * where there is time and where a failure can be retried.
 *
 * **What it does at this step, and what it deliberately does not.** It applies
 * the SMS sender allowlist and clears raw bodies past their retention (D-09).
 * It does **not** parse: the rule engine and `pending_transaction` are the next
 * steps, and a row this cannot yet judge is left `CAPTURED` rather than given a
 * verdict nothing produced. The class is named for what it becomes because the
 * next step extends this method rather than replacing the class — the same
 * reason the receiver was written in its final shape at S11.
 *
 * Notifications need no allowlist pass here: theirs runs before the row exists
 * (§5.2's privacy rule), so by the time one is in `notification_raw` the
 * question has already been answered.
 *
 * **Never throws** (CLAUDE.md §8, BUG7(e)). A worker that throws is a crash in a
 * background process; this returns `retry` and lets WorkManager back off.
 */
@HiltWorker
public class ParseIngestWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val triage: TriageCapturedIngestUseCase,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result = runCatching {
        val report = triage()
        // Counts only. Never a sender, never a body -- this log line runs on a
        // user's phone and the material it would otherwise carry is exactly what
        // CLAUDE.md §7 says must not be logged.
        Log.d(
            TAG,
            "Triage: ${report.sendersFiltered} not allowlisted, " +
                "${report.bodiesPurged} bodies purged.",
        )
        Result.success()
    }.getOrElse { throwable ->
        Log.e(TAG, "Triage failed; will retry.", throwable)
        Result.retry()
    }

    public companion object {
        /**
         * One pass at a time.
         *
         * The sink enqueues under this name with `KEEP`, so a burst of messages
         * collapses into a single run over all of them rather than several runs
         * contending for the same rows.
         */
        public const val UNIQUE_NAME: String = "ledgerflow-parse-ingest"

        private const val TAG = "ParseIngestWorker"
    }
}
