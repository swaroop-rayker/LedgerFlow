package com.ledgerflow.feature.inbox

import androidx.compose.runtime.Immutable
import com.ledgerflow.core.model.Category
import com.ledgerflow.core.model.LedgerType
import com.ledgerflow.core.model.Merchant
import com.ledgerflow.core.model.Money
import com.ledgerflow.core.model.PaymentMethod
import com.ledgerflow.core.model.Quantity

/**
 * The review screen (SPEC.md §5.1). P2-6, redesigned.
 *
 * **The shape is the entry form's, deliberately and to the field.** The owner's
 * requirement is that reviewing a captured message and typing one by hand are
 * the same act with the same controls: the same detail rows, the same pickers,
 * the same `Single item | Itemised` choice, the same line editor. The pickers
 * and rows are literally the same composables, lifted into `:core:ui`, so the
 * two screens cannot drift.
 *
 * ## What is *not* here: the book
 *
 * The first version made the user choose Expense or Income. That was confusing
 * and unnecessary, because the message already said: a bank SMS reading
 * "debited" is spend and "credited" is income, and the parser has read that into
 * `direction` before this screen opens. So the book is derived and shown
 * nowhere.
 *
 * [bookIsUnread] is the exception, and it is the one §5.1 forces. The never-drop
 * rule means a message no rule understood still reaches the Inbox — with no
 * amount and no direction. There is nothing to derive from, so those candidates
 * *do* get a book row, in the details card beside Category, in the same picker
 * style as everything else. Never a segmented control, and never present when
 * there is nothing to decide.
 */
@Immutable
public data class ReviewUiState(
    val pendingId: String = "",
    val loading: Boolean = true,
    /** Null once loaded means the id names nothing — a stale deep link, or a purge. */
    val missing: Boolean = false,

    // ── What the ledger needs ────────────────────────────────────────────────
    val ledger: LedgerType? = null,
    /**
     * True when the parser could not read a direction, so the user must supply
     * the book. The only condition under which a book control appears at all.
     */
    val bookIsUnread: Boolean = false,
    val amountText: String = "",
    val occurredAt: Long = 0L,
    val noteText: String = "",

    // ── Filed as ─────────────────────────────────────────────────────────────
    val categoryId: String? = null,
    val subcategoryId: String? = null,
    val merchantId: String? = null,
    /**
     * The payee exactly as the message wrote it, kept when it matches no
     * existing merchant.
     *
     * Shown in the Merchant row so the user sees what the SMS said, and passed
     * to the approval, where §5.1's `createOrGet` makes it real. Holding the
     * raw name rather than creating one now is what stops a discarded candidate
     * leaving a merchant behind (the P2-4 decision).
     */
    val rawMerchantName: String? = null,
    val paymentMethodId: String? = null,

    // ── Itemised (ADR-0018) ──────────────────────────────────────────────────
    val itemised: Boolean = false,
    val lines: List<ReviewLine> = emptyList(),
    val expandedLineKey: String? = null,

    // ── Choices available ────────────────────────────────────────────────────
    val categories: List<Category> = emptyList(),
    val merchants: List<Merchant> = emptyList(),
    val paymentMethods: List<PaymentMethod> = emptyList(),
    val merchantQuery: String = "",

    // ── Transient UI ─────────────────────────────────────────────────────────
    val picker: ReviewPicker? = null,
    val choosingDate: Boolean = false,
    val confirmingSingleItem: Boolean = false,

    // ── Provenance, so the user can see what they are correcting ─────────────
    val sourceLabel: String = "",
    val referenceHint: String? = null,
    val needsManualFill: Boolean = false,

    val submitting: Boolean = false,
    val message: String? = null,
    /** Set when the entry is committed or the candidate discarded; the screen closes. */
    val finished: Boolean = false,
) {
    public val selectedCategory: String?
        get() = categoryId?.let { id -> categories.firstOrNull { it.id == id }?.name }

    public val selectedSubcategory: String?
        get() = subcategoryId?.let { id -> categories.firstOrNull { it.id == id }?.name }

    public val selectedMerchant: String?
        get() = merchantId?.let { id -> merchants.firstOrNull { it.id == id }?.canonicalName }
            ?: rawMerchantName?.trim()?.takeIf { it.isNotEmpty() }

    public val selectedPaymentMethod: String?
        get() = paymentMethodId?.let { id -> paymentMethods.firstOrNull { it.id == id }?.label }

    /**
     * A book and an amount are the two the ledger cannot be given without.
     *
     * A category is deliberately not required — the approval accepts a null one,
     * and demanding one to clear the queue would make the Inbox harder to empty
     * than the entry form is to fill.
     */
    public val canApprove: Boolean
        get() = !submitting && ledger != null && amountText.isNotBlank()
}

/**
 * One line of an itemised candidate.
 *
 * The entry form's `EntryLineItem` to the field, including the derived total —
 * three numbers that cannot disagree, which is why the editor shows the total
 * read-only.
 */
@Immutable
public data class ReviewLine(
    val key: String,
    val name: String = "",
    /** Raw text, for the same caret reason the amount is. */
    val unitPriceText: String = "",
    val unitPriceMinor: Long = 0L,
    /** Raw text. Blank reads as one. */
    val quantityText: String = "",
    val quantityMilli: Long = Quantity.SCALE,
    val categoryId: String? = null,
    val subcategoryId: String? = null,
) {
    /** `unit price × quantity`, in integers (Law 3). */
    public val amountMinor: Long get() = (Money(unitPriceMinor) * Quantity(quantityMilli)).minor

    /**
     * Whether the line is worth saving.
     *
     * A row added and never typed in is an empty form, not an item. Saving it
     * would send a blank name to the approval, which refuses it.
     */
    public val hasContent: Boolean
        get() = name.isNotBlank() || unitPriceMinor != 0L || categoryId != null
}

/** Which picker is open, and what it is parented on. */
public sealed interface ReviewPicker {

    /** [lineKey] non-null files a *line* rather than the candidate (ADR-0018). */
    public data class Category(val lineKey: String? = null) : ReviewPicker

    public data class Subcategory(val parentId: String, val lineKey: String? = null) : ReviewPicker

    public data object Merchant : ReviewPicker

    public data object PaymentMethod : ReviewPicker

    /** Only reachable when the parser could not read a direction. */
    public data object Book : ReviewPicker
}

public sealed interface ReviewEvent {
    public data class AmountChanged(val text: String) : ReviewEvent
    public data class NoteChanged(val text: String) : ReviewEvent

    public data class PickerOpened(val picker: ReviewPicker) : ReviewEvent
    public data object PickerDismissed : ReviewEvent

    /** Null clears the selection: uncategorised is an answer, not a missing one. */
    public data class PickerItemSelected(val id: String?) : ReviewEvent

    public data class MerchantQueryChanged(val text: String) : ReviewEvent
    public data object MerchantCreateRequested : ReviewEvent

    public data object DateRequested : ReviewEvent
    public data class DateSelected(val epochMillis: Long) : ReviewEvent
    public data object DateDismissed : ReviewEvent

    public data class ModeSelected(val itemised: Boolean) : ReviewEvent
    public data object SingleItemConfirmed : ReviewEvent
    public data object SingleItemDismissed : ReviewEvent

    public data object LineAdded : ReviewEvent
    public data class LineExpanded(val key: String) : ReviewEvent
    public data object LineCollapsed : ReviewEvent
    public data class LineNameChanged(val key: String, val value: String) : ReviewEvent
    public data class LineUnitPriceChanged(val key: String, val text: String) : ReviewEvent
    public data class LineQuantityChanged(val key: String, val text: String) : ReviewEvent
    public data class LineRemoved(val key: String) : ReviewEvent
    public data class LineCategoryRequested(val key: String) : ReviewEvent
    public data class LineSubcategoryRequested(val key: String) : ReviewEvent

    public data object Approve : ReviewEvent
    public data object Discard : ReviewEvent
    public data object MessageShown : ReviewEvent
}
