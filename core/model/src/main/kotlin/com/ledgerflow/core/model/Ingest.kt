package com.ledgerflow.core.model

/**
 * What the pipeline made of one captured raw message (SPEC.md §6.1,
 * `sms_raw.parse_status` / `notification_raw.parse_status`).
 *
 * The raw row outlives its body: D-09 purges the text after 90 days and keeps
 * the record, so this is the durable account of what happened to a message
 * after the body is gone. That is also why "nothing happened to it yet" is a
 * value rather than a null.
 */
public enum class RawParseStatus {

    /**
     * Written, not yet looked at.
     *
     * Every raw row starts here, because §5.1 and §5.2 both persist *before*
     * parsing: a capture adapter has ~10 seconds before the system kills it, so
     * the only safe thing it can do is write and enqueue.
     */
    CAPTURED,

    /** A rule matched and a `pending_transaction` was created. */
    PARSED,

    /**
     * No rule matched, and a `pending_transaction` was created anyway with
     * `confidence = 0` and `needs_manual_fill = 1`.
     *
     * **This is not a failure and the message is not dropped** (§5.1). An
     * unparseable message from a financial sender is exactly the material the
     * ruleset needs to grow against, and silently discarding one is the defect
     * that rule is written to prevent.
     */
    UNMATCHED,

    /**
     * An SMS whose sender is not on the financial-sender allowlist. No pending
     * row was created.
     *
     * **This value exists only for SMS, and the asymmetry with notifications is
     * deliberate on §5.1's part.** A notification is filtered by package
     * *before* its body is ever read, so a non-allowlisted one leaves no row at
     * all (§5.2's privacy rule). An SMS is written first and filtered in the
     * worker, because the receiver cannot safely do lookups inside its ten
     * seconds. The consequence is that ordinary personal SMS briefly lands in
     * `sms_raw`; it is marked with this and is the first thing retention should
     * clear.
     */
    SENDER_NOT_ALLOWLISTED,

    /**
     * The same transaction already arrived through the other source (§3.1).
     *
     * Retained and visible under the Inbox's "Suppressed" filter, never
     * silently discarded — a suppressed row is the evidence that dedupe made a
     * choice, and the user has to be able to see the one it did not keep.
     */
    DUPLICATE_SUPPRESSED,

    /**
     * The worker errored on this message.
     *
     * Distinct from [UNMATCHED]: that is the ruleset saying "I do not recognise
     * this", which is information. This is the pipeline saying nothing at all,
     * which is a bug to chase.
     */
    FAILED,
}

/**
 * Where one candidate transaction stands in the approval queue (SPEC.md §6.1,
 * `pending_transaction.status`).
 *
 * These four exactly, as §6.1 lists them. **"Suppressed" is deliberately not
 * here**: a cross-source duplicate is recorded by `suppressed_by_id` pointing
 * at the row that won, and the Inbox's fourth filter reads that column. Making
 * it a status would mean a row could be either suppressed or discarded but not
 * both, and would lose which row it was a duplicate *of*.
 *
 * Law 1 lives on the transition out of [PENDING]: only
 * `ApproveTransactionUseCase` may move a row to [APPROVED], and only it may
 * insert into `ledger_entry`.
 */
public enum class PendingStatus {

    /** Awaiting the user. Nothing has reached the ledger. */
    PENDING,

    /** The user approved it; `approved_entry_id` names the committed entry. */
    APPROVED,

    /**
     * The user rejected it. **The row is kept** — §5.1 makes discards auditable
     * and restorable for 30 days, so this is a state, not a delete.
     */
    DISCARDED,

    /** The pipeline could not produce a reviewable candidate from the message. */
    FAILED,
}
