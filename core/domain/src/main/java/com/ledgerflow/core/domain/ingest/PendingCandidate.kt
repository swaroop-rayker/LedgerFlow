package com.ledgerflow.core.domain.ingest

import com.ledgerflow.core.model.EntrySource

/**
 * One candidate transaction on its way into `pending_transaction`
 * (SPEC.md §5.1, §5.2, §6.1). P2-4.
 *
 * **This is the shape Law 1 protects.** A candidate is what an automated source
 * is permitted to produce: it is written to `pending_transaction` and it waits
 * for a human. Only `ApproveTransactionUseCase` turns one into a `ledger_entry`,
 * and nothing here appears in a total, a ledger query or a rollup.
 *
 * It lives in `:core:domain` rather than in `:feature:ingest` for the same
 * reason [ExtractedTransaction] does: the Inbox has to render one at P2-6 and
 * features may not depend on features (CLAUDE.md §3).
 *
 * **The merchant is deliberately unresolved.** [ExtractedTransaction.merchantRaw]
 * is carried exactly as the message wrote it, and §5.1's
 * `MerchantRepository.createOrGet` is called at *approval*, not here — owner
 * decision at P2-4. Resolving at parse time would create a taxonomy row for
 * every candidate the user later discards, and for every garbled merchant string
 * a rule ever mis-extracted; it would also leave §5.5's Jaro-Winkler suggestion
 * at review time with nothing left to suggest, because the row would already
 * exist. §5.1's guarantee is that ingest *may never fail* for a merchant that
 * does not exist yet, and a candidate that carries only a raw name cannot fail
 * for one.
 *
 * **The raw row is not a field here.** `pending_transaction.raw_ref_id` is the
 * idempotency link — a raw row that already produced a candidate must not produce
 * a second when the worker re-runs — and
 * [RawIngestRepository.recordParseOutcome] already takes that id, because it also
 * writes the verdict onto that row. Carrying it twice would give the check and
 * the insert two sources for one value, and they would disagree exactly when a
 * caller got it wrong.
 *
 * @param source which capture produced this. Persisted for the audit trail,
 *   **never branched on** (CLAUDE.md §0).
 * @param dedupeKey §3.1's cross-source key, computed by [DedupeKey]. *Storing*
 *   it is P2-4; acting on a collision is P2-5.
 */
public data class PendingCandidate(
    val source: EntrySource,
    val extracted: ExtractedTransaction,
    val dedupeKey: String,
) {

    /** 0.0 when nothing matched (§5.1). A score, not money — Law 3 does not apply. */
    public val confidence: Double get() = extracted.confidence

    /**
     * Whether the review screen opens with fields to *fill* rather than to check.
     *
     * Derived from [ExtractedTransaction.isReviewable] rather than from
     * `confidence == 0.0`, and the difference is real: a rule that matched a
     * sentence but pulled no amount out of it scores above zero and is still
     * useless to a reviewer. §5.1 names the unmatched case explicitly; this is
     * the same promise held to the shallow-match case, which is the failure the
     * owner's real HDFC credit alert actually exhibited.
     */
    public val needsManualFill: Boolean get() = !extracted.isReviewable
}

/**
 * What became of one candidate on its way into `pending_transaction`.
 *
 * Shaped like [CaptureOutcome] on purpose: the same three answers, because the
 * same three things can happen — it landed, it was already there, or the write
 * failed and saying so is better than pretending.
 */
public sealed interface PendingWriteOutcome {

    /** Written. [pendingId] is the `pending_transaction` row. */
    public data class Created(val pendingId: String) : PendingWriteOutcome

    /**
     * This raw row had already produced a candidate.
     *
     * **Not a failure and not an error path** — it is the worker re-running,
     * which WorkManager does routinely. The verdict on the raw row is still
     * written, so a pass interrupted between the two writes converges.
     */
    public data class AlreadyPending(val pendingId: String) : PendingWriteOutcome

    /** The write itself failed. The raw row keeps its `CAPTURED` status and is retried. */
    public data class Failed(val reason: String) : PendingWriteOutcome
}
