package com.ledgerflow.feature.ledger

import androidx.compose.runtime.Immutable
import com.ledgerflow.core.model.DeletedEntry

/**
 * The bin's state (SPEC.md §5.5, ADR-0015).
 *
 * **Both books in one list**, which no other ledger surface does. That carve-out
 * is ADR-0015: a bin is storage management rather than reading a book, the two
 * queries underneath are still one per ledger, and nothing here is ever summed
 * — every row carries its own direction and colours itself.
 */
@Immutable
public data class BinUiState(
    /** Every binned entry from both books, newest first by when it happened. */
    val entries: List<DeletedEntry> = emptyList(),

    /**
     * What the user has ticked, as `"$ledger:$id"`.
     *
     * Keyed on the pair rather than the id alone because the list spans both
     * books, and an id carries no ledger inside it — the same reason every
     * write here takes both.
     */
    val selected: Set<String> = emptySet(),

    /** True while a restore or a purge is running. */
    val isWorking: Boolean = false,

    /** The question on screen, if any. */
    val confirmation: BinConfirmation? = null,

    /** The outcome of the last action, for a line the user can dismiss. */
    val message: String? = null,

    /**
     * Whether the query has answered.
     *
     * The same distinction the Ledger needs: an empty list before the first
     * read and an empty bin look identical, and only one of them should render
     * "nothing here".
     */
    val isLoaded: Boolean = false,
) {
    /** Nothing ticked means the actions apply to nothing. */
    public val hasSelection: Boolean get() = selected.isNotEmpty()

    public val selectionCount: Int get() = selected.size
}

/** The key a row is selected by: its book and its id together. */
public fun DeletedEntry.selectionKey(): String = "$ledger:$id"

/**
 * A question the bin has asked.
 *
 * Only the destructive answers get one. Restoring needs no confirmation: it
 * puts something back, and the worst case is that the user bins it again.
 */
public sealed interface BinConfirmation {

    /** Destroy the ticked rows. [count] is named in the warning. */
    @Immutable
    public data class PurgeSelected(val count: Int) : BinConfirmation

    /** Destroy everything in the bin, both books. */
    @Immutable
    public data class PurgeAll(val count: Int) : BinConfirmation
}

public sealed interface BinEvent {
    /** Tick or untick one row. */
    public data class Toggled(val key: String) : BinEvent

    public data object SelectAllToggled : BinEvent

    /** Restore runs immediately — it destroys nothing. */
    public data object RestoreRequested : BinEvent

    public data object PurgeSelectedRequested : BinEvent
    public data object PurgeAllRequested : BinEvent

    public data object ConfirmationAccepted : BinEvent
    public data object ConfirmationDismissed : BinEvent

    public data object MessageDismissed : BinEvent
}
