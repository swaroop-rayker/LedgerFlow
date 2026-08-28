package com.ledgerflow.core.domain.inbox

import com.ledgerflow.core.domain.ingest.ExtractedTransaction
import com.ledgerflow.core.model.EntrySource
import com.ledgerflow.core.model.PendingStatus
import kotlinx.coroutines.flow.Flow

/**
 * One candidate, as the Inbox shows it (SPEC.md §5.1, §6.1). P2-6.
 *
 * The read side of what P2-4 writes. `extracted_json` is decoded by the time it
 * gets here — the Inbox renders extraction targets, not a string — and that
 * decoding lives in `:core:data` beside the encoder, so the two cannot drift
 * (see `ExtractedTransactionJson`).
 *
 * @param isSuppressed §3.1's cross-source duplicate. Derived from
 *   `suppressed_by_id` rather than from [status], because "suppressed" is not
 *   one of §6.1's four statuses: a row can be both suppressed and discarded, and
 *   making it a status would lose which row it was a duplicate *of*.
 */
public data class PendingTransaction(
    val id: String,
    val source: EntrySource,
    val extracted: ExtractedTransaction,
    val confidence: Double,
    val status: PendingStatus,
    val needsManualFill: Boolean,
    val suppressedById: String?,
    val createdAt: Long,
    val reviewedAt: Long?,
    val approvedEntryId: String?,
    /**
     * What the user typed on the review screen and has not approved (v8, BUG6).
     *
     * **Opaque here on purpose.** This is the review *form's* state -- an amount
     * mid-keystroke, a category not chosen yet -- and it belongs to the one
     * screen that produces it, exactly as `draft_entry.payload_json` belongs to
     * the entry form. Contrast [extracted]: those are §5.1's extraction targets,
     * they are spec-level, and so they are decoded in `:core:data` and arrive
     * typed. Giving this layer an opinion about the review screen's field list
     * would make every UI change a domain change.
     *
     * Null means nothing has been typed; the screen then opens from [extracted]
     * as it always did. Cleared by the same statement that approves or discards,
     * so a resolved candidate can never carry stale typing.
     */
    val reviewDraftJson: String? = null,
) {
    public val isSuppressed: Boolean get() = suppressedById != null

    /**
     * Approvable without opening the review screen first.
     *
     * An amount and a book are the two the ledger cannot be given without. A
     * category is **not** required — the approval path accepts a null one, and
     * demanding a category to clear the queue would make the Inbox harder to
     * empty than the entry form is to fill.
     */
    public val isOneTapApprovable: Boolean
        get() = !needsManualFill && extracted.isReviewable && status == PendingStatus.PENDING
}

/**
 * The Inbox's four filters (SPEC.md §5.1).
 *
 * [SUPPRESSED] is the odd one and deliberately so: the other three select on
 * `status`, and this one selects on `suppressed_by_id IS NOT NULL`. §3.1
 * requires a suppressed duplicate to stay **visible** — a row the dedupe layer
 * hid with no way to see it would be indistinguishable from a message that was
 * dropped, which §5.1 forbids. This filter is that visibility.
 */
public enum class InboxFilter {
    /** Awaiting the user, and not suppressed. The queue proper. */
    PENDING,

    /** Lost a cross-source dedupe (§3.1). Retained, never discarded. */
    SUPPRESSED,

    /** Rejected by the user. Kept, and restorable for 30 days (§5.1). */
    DISCARDED,

    /**
     * The pipeline could not produce a candidate.
     *
     * **Nothing writes this today**, and the filter exists anyway because §5.1
     * names it and an empty filter is honest. See `RawIngestRepository`'s note:
     * `Unmatched` is a result, not a failure, and a worker exception is
     * transient and retried.
     */
    FAILED,
}

/** Why a review action could not be applied. */
public sealed interface InboxError {

    /** No such candidate. It was purged, or the id came from a stale deep link. */
    public data object NotFound : InboxError

    /**
     * Someone already decided this one.
     *
     * The guard against a double-approve: a deep link the user taps twice, or a
     * notification action racing the screen (§5.1's `[Review] [Discard]`).
     */
    public data class AlreadyReviewed(val status: PendingStatus) : InboxError

    /** The candidate has no amount or no direction, so the ledger cannot take it. */
    public data object NotReviewable : InboxError

    /** The ledger refused. Carries its own reason verbatim rather than flattening it. */
    public data class Rejected(val reason: String) : InboxError
}

/**
 * `pending_transaction`, read and reviewed (SPEC.md §5.1, §6.1). P2-6.
 *
 * **Nothing here inserts into `ledger_entry`.** Law 1's single writer is
 * `ApproveTransactionUseCase`, and [ApprovePendingUseCase] calls it rather than
 * reaching past it — this port's part of an approval is the *other* write, the
 * one that marks the candidate reviewed. `LedgerSingleWriterTest` guards the
 * door; this deliberately is not a fifth one.
 */
public interface PendingRepository {

    /** The rows one filter selects, newest first. */
    public fun observe(filter: InboxFilter): Flow<List<PendingTransaction>>

    /**
     * How many rows are waiting — the `Inbox (n)` count on §9.3's speed dial.
     *
     * Counts [InboxFilter.PENDING] only. A suppressed duplicate is not work the
     * user has to do, and a badge that counted it would send them to a screen to
     * find nothing needing their attention.
     */
    public fun observePendingCount(): Flow<Int>

    public suspend fun find(id: String): PendingTransaction?

    /**
     * §5.1: sets `DISCARDED` and **keeps the row**, auditable and restorable.
     *
     * Not a delete. The user rejecting a candidate is information — it is what
     * the rule editor's test bench and any future precision measurement are
     * made of — and §5.1 gives them 30 days to change their mind.
     */
    public suspend fun discard(id: String): Boolean

    /** Undo, for the snackbar and for the Discarded filter. Back to `PENDING`. */
    public suspend fun restore(id: String): Boolean

    /**
     * Persists the review screen's in-progress state (v8, BUG6).
     *
     * Called on a 300 ms debounce while the user types, and with null to clear.
     * The payload is the screen's own -- see [PendingTransaction.reviewDraftJson].
     *
     * **Only affects a `PENDING` row.** A late debounce tick arriving after the
     * user has approved or discarded must not write typing back onto a resolved
     * candidate; the statement binds the status so that it cannot.
     */
    public suspend fun saveReviewDraft(id: String, json: String?): Boolean

    /**
     * **Destroys candidates for good. The only irreversible operation here.**
     *
     * Erases the rows named, and returns how many actually went. Two guards are
     * in the SQL rather than in a caller, so they hold however the ids arrived:
     * a candidate that produced a `ledger_entry` is never erasable — it is that
     * entry's only record of where it came from, and [findApprovedEntryId]'s
     * half of the idempotency guard — and a live `PENDING` candidate is never
     * erasable either. Discarding first is the path, and that is reversible for
     * 30 days.
     *
     * **The raw message stays** (owner decision). Only the candidate goes; the
     * captured body expires on D-09's 90-day retention as it always would. §5.1
     * makes a rejected candidate information, and P2-9's corpus is made of
     * exactly these.
     *
     * A returned count lower than `ids.size` is not an error: it means some rows
     * did not satisfy the guards, which is the guards working.
     */
    public suspend fun erase(ids: List<String>): Int

    /**
     * Everything one filter selects, on the same terms as [purge].
     *
     * [InboxFilter.PENDING] is **refused** rather than emptied — it is the queue
     * Law 1 is about, and "erase all" on the screen listing work the user has
     * not looked at yet is not a thing this app offers.
     */
    public suspend fun eraseAll(filter: InboxFilter): Int

    /**
     * Records that a candidate became a ledger entry.
     *
     * Called by [ApprovePendingUseCase] *after* the entry is committed. The two
     * writes are not one transaction and cannot be — they live in different
     * repositories, and collapsing them would mean this port writing to
     * `ledger_entry`. The gap is closed by [findApprovedEntryId] instead.
     */
    public suspend fun markApproved(id: String, entryId: String): Boolean

    /**
     * The entry a candidate already produced, if any.
     *
     * **The idempotency guard across the approval's two writes.** If the process
     * dies between committing the entry and marking the candidate, the row is
     * still `PENDING` and the user would approve it again — writing a second
     * ledger entry for one payment, which is precisely the duplicate the whole
     * ingest pipeline exists to avoid. Consulted before every approval, the
     * half-finished state is recoverable rather than doubling.
     */
    public suspend fun findApprovedEntryId(pendingId: String): String?
}
