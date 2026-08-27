package com.ledgerflow.feature.ingest.pipeline

import android.util.Log
import com.ledgerflow.core.domain.ingest.CaptureOutcome
import com.ledgerflow.core.domain.ingest.IngestWorkTrigger
import com.ledgerflow.core.domain.ingest.RawIngestEvent
import com.ledgerflow.core.domain.usecase.RecordCapturedEventUseCase
import javax.inject.Inject
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
 * Enqueueing moved behind [IngestWorkTrigger] when a second caller appeared:
 * app launch also has to be able to ask for a pass, because §16 Q14's
 * re-triage is triggered by the allowlist changing rather than by a message
 * arriving.
 */
@Singleton
public class PersistingIngestEventSink @Inject constructor(
    private val recordCapturedEvent: RecordCapturedEventUseCase,
    private val ingestWork: IngestWorkTrigger,
) : IngestEventSink {

    override suspend fun submit(event: RawIngestEvent) {
        when (val outcome = recordCapturedEvent(event)) {
            is CaptureOutcome.Recorded -> ingestWork.requestParsePass()

            // Already on disk from an earlier delivery. The worker may still owe
            // it a pass, so this enqueues too -- the work is idempotent and
            // KEEP collapses a burst into one run.
            CaptureOutcome.AlreadySeen -> ingestWork.requestParsePass()

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

    private companion object {
        private const val TAG = "IngestSink"
    }
}
