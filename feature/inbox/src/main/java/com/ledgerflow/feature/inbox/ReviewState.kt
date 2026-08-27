package com.ledgerflow.feature.inbox

import androidx.compose.runtime.Immutable
import com.ledgerflow.core.model.Category
import com.ledgerflow.core.model.LedgerType

/**
 * The review screen (SPEC.md §5.1). P2-6.
 *
 * §5.1 asks for "fields prefilled and focus on Category picker", and the second
 * half is the design: everything else on this screen is a *correction* of what
 * the parser read, while the category is the one thing it never supplies. So
 * the amount, book and payee arrive filled and the category arrives empty and
 * first among the things asking for attention.
 *
 * Fields are held as the strings the user is typing rather than as parsed
 * values, so a half-typed amount is representable. Parsing happens once, at
 * approve.
 */
@Immutable
public data class ReviewUiState(
    val pendingId: String = "",
    val loading: Boolean = true,
    /** Null once loaded means the id names nothing — a stale deep link, or a purge. */
    val missing: Boolean = false,
    val ledger: LedgerType? = null,
    val amountText: String = "",
    val merchantText: String = "",
    val noteText: String = "",
    val categoryId: String? = null,
    val categories: List<Category> = emptyList(),
    /** What the message said, shown so the user can see what they are overriding. */
    val sourceLabel: String = "",
    val rawBodyHint: String? = null,
    val needsManualFill: Boolean = false,
    val occurredAtLabel: String = "",
    val submitting: Boolean = false,
    val message: String? = null,
    /** Set when the entry is committed; the screen closes on it. */
    val approved: Boolean = false,
) {
    /**
     * A book and an amount are the two the ledger cannot be given without.
     *
     * The category is deliberately not required — approval accepts a null one,
     * and demanding one to clear the queue would make the Inbox harder to empty
     * than the entry form is to fill.
     */
    public val canApprove: Boolean
        get() = !submitting && ledger != null && amountText.isNotBlank()
}

public sealed interface ReviewEvent {
    public data class LedgerChosen(val ledger: LedgerType) : ReviewEvent
    public data class AmountChanged(val text: String) : ReviewEvent
    public data class MerchantChanged(val text: String) : ReviewEvent
    public data class NoteChanged(val text: String) : ReviewEvent

    /** Null clears it: uncategorised is a legitimate answer, not a missing one. */
    public data class CategoryChosen(val categoryId: String?) : ReviewEvent

    public data object Approve : ReviewEvent
    public data object Discard : ReviewEvent
    public data object MessageShown : ReviewEvent
}
