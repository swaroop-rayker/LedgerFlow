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

    /**
     * How many rows each filter holds, for the chip row.
     *
     * A filter with nothing in it is not offered — the Inbox showed four chips
     * where two were routinely empty and one (`FAILED`) is unwritten by any
     * path in the app today.
     *
     * **Measured, not hard-coded.** `FAILED` stays reachable for a cause that
     * is genuinely terminal, so removing its chip outright would hide those
     * rows on the day something finally writes one. A count decides, and the
     * chip comes back on its own.
     */
    val counts: Map<InboxFilter, Int> = emptyMap(),
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

    /**
     * The rows ticked for erasing (CHANGE#1).
     *
     * Only ever populated on a filter that permits erasing — see [canErase].
     * Cleared when the filter changes, because a selection that survived a tab
     * switch would let "Erase 3" mean three rows the user can no longer see.
     */
    val selected: Set<String> = emptySet(),

    /** Set while a purge runs, so the controls cannot be tapped twice. */
    val isWorking: Boolean = false,

    /** The `Warning` dialog. Never null while a purge is one tap away. */
    val confirmation: InboxConfirmation? = null,
) {

    /**
     * Whether this filter's rows may be destroyed.
     *
     * `PENDING` is excluded and that is the point: it is the queue Law 1 is
     * about, and the way to destroy a live candidate is to discard it first —
     * which is reversible for 30 days. The SQL enforces this too; this is only
     * what decides whether the UI offers it.
     */
    val canErase: Boolean
        get() = filter != InboxFilter.PENDING

    /**
     * The chips worth drawing.
     *
     * `PENDING` always — it is the queue and the screen's home, and a chip row
     * that vanished on an empty Inbox would leave the user nothing to press.
     * The rest appear when they hold something.
     *
     * **The selected filter is always kept**, even once it empties. Erasing
     * every discarded row while standing on Discarded would otherwise pull the
     * chip out from under the user mid-tap and drop them somewhere they did not
     * choose.
     */
    val visibleFilters: List<InboxFilter>
        get() = InboxFilter.entries.filter { candidate ->
            candidate == InboxFilter.PENDING ||
                candidate == filter ||
                (counts[candidate] ?: 0) > 0
        }

    val hasSelection: Boolean get() = selected.isNotEmpty()

    val selectionCount: Int get() = selected.size
}

/**
 * What the `Warning` dialog is about to do.
 *
 * Both name a **count**, because "Erase all?" without a number is a question
 * the user cannot answer — the same rule the bin's purge dialog follows
 * (CLAUDE.md §7).
 */
@Immutable
public sealed interface InboxConfirmation {

    /** Destroy the ticked rows. */
    @Immutable
    public data class EraseSelected(val count: Int) : InboxConfirmation

    /** Destroy everything the current filter lists. */
    @Immutable
    public data class EraseAll(val count: Int, val filter: InboxFilter) : InboxConfirmation
}

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

    // ── Erasing (CHANGE#1) ──────────────────────────────────────────────────

    /** Tick or untick one row. */
    public data class SelectionToggled(val pendingId: String) : InboxEvent

    public data object SelectionCleared : InboxEvent

    /** Ask about the ticked rows. Opens the dialog; never erases. */
    public data object EraseSelectedRequested : InboxEvent

    /** Ask about everything on this filter. Opens the dialog; never erases. */
    public data object EraseAllRequested : InboxEvent

    /**
     * The dialog's confirm.
     *
     * **The only event that destroys anything**, and it exists separately from
     * the two above precisely so that no tap on a list control can reach the
     * delete without the dialog in between.
     */
    public data object EraseConfirmed : InboxEvent

    public data object EraseDismissed : InboxEvent
}
