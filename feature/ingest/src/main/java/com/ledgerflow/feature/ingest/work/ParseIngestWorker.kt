package com.ledgerflow.feature.ingest.work

import android.content.Context
import android.util.Log
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.ledgerflow.core.domain.usecase.TriageCapturedIngestUseCase
import com.ledgerflow.feature.ingest.pipeline.ParseCapturedMessages
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
 * the SMS sender allowlist, clears raw bodies past their retention (D-09), and
 * runs the rule engine over what is left, recording `matched_rule_id` and
 * `PARSED` / `UNMATCHED` on each raw row. It does **not** create a
 * `pending_transaction` — that is the next step, and §5.1's rule that an
 * unparseable message still becomes a `PENDING` row is satisfied there. Until
 * then an `UNMATCHED` row is a recorded verdict rather than a lost message,
 * which is the distinction §5.1 actually cares about.
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
    private val parse: ParseCapturedMessages,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result = runCatching {
        // Order matters: the allowlist pass first, so a message from a
        // non-financial sender leaves the queue before the engine matches its
        // text against a single rule.
        val triaged = triage()
        val parsed = parse()
        // Counts only. Never a sender, never a body -- this log line runs on a
        // user's phone and the material it would otherwise carry is exactly what
        // CLAUDE.md §7 says must not be logged.
        Log.d(
            TAG,
            "Triage: ${triaged.sendersFiltered} not allowlisted, " +
                "${triaged.bodiesPurged} bodies purged. " +
                "Parse: ${parsed.parsed} matched, ${parsed.unmatched} unmatched.",
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
