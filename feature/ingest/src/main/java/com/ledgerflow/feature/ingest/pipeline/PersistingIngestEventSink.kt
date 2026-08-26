package com.ledgerflow.feature.ingest.pipeline

import android.util.Log
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.ledgerflow.core.domain.ingest.CaptureOutcome
import com.ledgerflow.core.domain.ingest.RawIngestEvent
import com.ledgerflow.core.domain.usecase.RecordCapturedEventUseCase
import com.ledgerflow.feature.ingest.work.ParseIngestWorker
import javax.inject.Inject
import javax.inject.Provider
import javax.inject.Singleton

/**
 * Write the raw row, enqueue the worker, return. Nothing else (SPEC.md §5.1).
 *
 * Replaces S11's discarding sink, and is the only class P2 had to swap to make
 * capture real — both adapters call this and neither changed.
 *
 * **The order is the contract.** Persist first, enqueue second: §5.1's promise
 * that a financial SMS is never silently dropped only holds if the row is on
 * disk before any judgement is made about it, and the worker exists precisely so
 * that judgement happens outside the receiver's ten seconds (CLAUDE.md §7).
 *
 * Nothing here parses, joins or decides. A duplicate is not an error — the
 * network re-delivers SMS and the unique `body_hash` absorbs that — and a
 * failure is logged rather than thrown, because an exception escaping here would
 * take a `BroadcastReceiver` with it and the user would see LedgerFlow crash
 * every time a text arrived.
 *
 * `Provider<WorkManager>` rather than the instance: `WorkManager.getInstance`
 * touches disk on first call, and resolving it eagerly would put that on
 * whatever thread built the Hilt graph.
 */
@Singleton
public class PersistingIngestEventSink @Inject constructor(
    private val recordCapturedEvent: RecordCapturedEventUseCase,
    private val workManager: Provider<WorkManager>,
) : IngestEventSink {

    override suspend fun submit(event: RawIngestEvent) {
        when (val outcome = recordCapturedEvent(event)) {
            is CaptureOutcome.Recorded -> enqueueParse()

            // Already on disk from an earlier delivery. The worker may still owe
            // it a pass, so this enqueues too -- the work is idempotent and
            // KEEP collapses a burst into one run.
            CaptureOutcome.AlreadySeen -> enqueueParse()

            CaptureOutcome.NotAllowed -> Unit

            is CaptureOutcome.Failed -> Log.e(
                TAG,
                // The source and the body's LENGTH, never its content. A bank
                // SMS is exactly the material CLAUDE.md §7 says must not be
                // logged, and a debug build is still the user's phone.
                "Failed to persist ${event.sourceType} event " +
                    "(${event.body.length} chars): ${outcome.reason}",
            )
        }
    }

    /**
     * One pass, however many messages arrived.
     *
     * [ExistingWorkPolicy.KEEP] on a unique name: a burst of five bank SMS in a
     * second should produce one worker run over five rows, not five runs racing
     * each other over the same table. A run already in flight keeps going and
     * picks up whatever landed while it worked; if it has already passed them,
     * the next enqueue starts a fresh one.
     */
    private fun enqueueParse() {
        workManager.get().enqueueUniqueWork(
            ParseIngestWorker.UNIQUE_NAME,
            ExistingWorkPolicy.KEEP,
            OneTimeWorkRequestBuilder<ParseIngestWorker>().build(),
        )
    }

    private companion object {
        private const val TAG = "IngestSink"
    }
}
