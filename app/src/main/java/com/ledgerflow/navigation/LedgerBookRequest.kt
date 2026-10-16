package com.ledgerflow.navigation

import androidx.lifecycle.SavedStateHandle
import com.ledgerflow.core.model.LedgerType

/**
 * "Open the Ledger on this book", from Home's "See all" (V2-PLAN BUG-C step 4).
 *
 * **A one-shot request on the Ledger tab's back-stack entry, not a route
 * argument.** The Ledger is a tab: it is switched to with `restoreState`, which
 * brings back the saved entry and ignores the arguments of the navigation that
 * restored it, so `Destination.Ledger(book)` would open on whatever book it
 * last showed. The entry's own `SavedStateHandle` is also not the one Hilt hands
 * `LedgerViewModel` (each ViewModel gets its own), so the graph reads the
 * request here and passes it on as the same `LedgerSelected` event a tap on
 * the segmented control sends. `:feature:ledger` knows nothing about Home.
 *
 * Consumed, not observed: once applied it is cleared, so a later return to the
 * tab keeps the book the user last chose rather than snapping back to this one.
 */
internal object LedgerBookRequest {

    const val KEY: String = "ledger.requestedBook"

    /** Leaves the request on the Ledger entry's handle. */
    fun place(handle: SavedStateHandle, book: LedgerType) {
        handle[KEY] = book.name
    }

    /**
     * The requested book, once; null when there is none or it does not name a
     * book. Clears it either way, so an unreadable request cannot stick.
     */
    fun consume(handle: SavedStateHandle): LedgerType? {
        val name = handle.get<String>(KEY) ?: return null
        handle.remove<String>(KEY)
        return LedgerType.entries.firstOrNull { it.name == name }
    }
}
