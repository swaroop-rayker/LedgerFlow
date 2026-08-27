package com.ledgerflow.feature.inbox

import androidx.compose.runtime.Immutable
import com.ledgerflow.core.domain.inbox.InboxFilter
import com.ledgerflow.core.domain.inbox.PendingTransaction

/**
 * The Inbox screen's whole state (SPEC.md §5.1). P2-6.
 *
 * One `@Immutable` data class exposed as a `StateFlow`, per CLAUDE.md §5. The
 * screen is stateless; everything here is decided in [InboxViewModel].
 */
@Immutable
public data class InboxUiState(
    val filter: InboxFilter = InboxFilter.PENDING,
    val rows: List<PendingTransaction> = emptyList(),
    val pendingCount: Int = 0,
    val loading: Boolean = true,
    /**
     * The candidate a swipe just discarded, held for the undo snackbar (§5.1).
     *
     * The row is already `DISCARDED` in the database by the time this is set —
     * undo restores it rather than deferring the write. A pending discard held
     * only in memory would be lost to a process death with the row still in the
     * queue, which is the same class of defect as BUG6.
     */
    val undoableDiscard: UndoableDiscard? = null,
    val message: String? = null,
)

/** What the undo snackbar needs: which row, and what to call it. */
@Immutable
public data class UndoableDiscard(val pendingId: String, val label: String)

/**
 * Everything the Inbox screen can ask for, as one sealed type.
 *
 * Events flow up through a single `(InboxEvent) -> Unit` (CLAUDE.md §5), so the
 * screen never holds a callback per control and the `when` below stays
 * exhaustive.
 */
public sealed interface InboxEvent {

    public data class FilterSelected(val filter: InboxFilter) : InboxEvent

    /**
     * One-tap approve from the list, for a candidate that needs no decisions.
     *
     * Only offered when [PendingTransaction.isOneTapApprovable] — an amount and
     * a book. Everything else opens the review screen instead, because the
     * ledger cannot be given a book that was guessed (Law 2).
     */
    public data class Approved(val pendingId: String) : InboxEvent

    public data class Discarded(val pendingId: String, val label: String) : InboxEvent

    public data class Restored(val pendingId: String) : InboxEvent

    /** The undo snackbar's action. */
    public data object UndoDiscard : InboxEvent

    public data object UndoExpired : InboxEvent

    public data object MessageShown : InboxEvent
}
