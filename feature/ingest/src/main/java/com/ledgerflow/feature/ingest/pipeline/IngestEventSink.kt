package com.ledgerflow.feature.ingest.pipeline

import com.ledgerflow.core.domain.ingest.RawIngestEvent

/**
 * Where a capture adapter hands off, and the last place that knows which
 * adapter it was.
 *
 * This is the seam the whole S11 skeleton exists to establish. Both adapters
 * call exactly this, with exactly [RawIngestEvent]; nothing past it is
 * flavour-scoped or source-shaped.
 *
 * It lives in `:feature:ingest` rather than on a `:core:domain` port because of
 * what the real implementation will do at P2: persist the raw row **and enqueue
 * `ParseIngestWorker`** (§5.1, §5.2). The second half is `androidx.work`, which
 * `:core:domain` must not see — ADR-0014's carve-out admitted `paging-common`
 * and named `androidx.work` as precisely the thing it is not a precedent for.
 * The persistence half will reach the database through a repository port like
 * everything else.
 *
 * **The contract is "return fast, having lost nothing."** An SMS receiver has
 * ~10 seconds before the system kills it (CLAUDE.md §7), so an implementation
 * writes and enqueues — it does not parse, join, or make a decision about the
 * message. And it never drops a financial message on the floor: §5.1's
 * unparseable-SMS rule (a `PENDING` row with `confidence = 0`) only works if the
 * raw row got written first.
 */
public interface IngestEventSink {

    /**
     * Accepts one captured event.
     *
     * Suspending, and callers are expected to keep the process alive across it
     * (`goAsync()` for a receiver). Must not throw: a capture adapter has no
     * meaningful recovery and an exception here would take the receiver with it.
     */
    public suspend fun submit(event: RawIngestEvent)
}
