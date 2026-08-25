package com.ledgerflow.feature.ingest.pipeline

import android.util.Log
import com.ledgerflow.core.domain.ingest.RawIngestEvent
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The S11 sink: accepts an event and drops it.
 *
 * This is deliberate, not a stub someone forgot. S11 is the abstraction and the
 * flavour skeleton (SPEC.md §13, P1 row) — the tables it would write to,
 * `sms_raw` and `notification_raw`, are P2 and do not exist in schema v5, and
 * inventing them here would mean a `Migration` in a step whose definition of
 * done says "no schema change".
 *
 * What it buys, being here rather than absent: both capture adapters are wired
 * to something real, so the receiver's `goAsync()` shape, the injected
 * dispatcher, and the Hilt graph are exercised on device rather than being
 * assumed correct until P2. **P2 replaces this one class** — persist the raw row,
 * then enqueue `ParseIngestWorker` — and no adapter changes.
 *
 * The log line carries the source and the body's *length*, never its content.
 * A bank SMS and a UPI notification are exactly the material CLAUDE.md §7 says
 * must not be logged, and a debug-only build is still the user's phone.
 */
@Singleton
public class DiscardingIngestEventSink @Inject constructor() : IngestEventSink {

    override suspend fun submit(event: RawIngestEvent) {
        Log.d(
            TAG,
            "Captured ${event.sourceType} event (${event.body.length} chars); " +
                "discarded -- raw persistence lands at P2.",
        )
    }

    private companion object {
        private const val TAG = "IngestSink"
    }
}
