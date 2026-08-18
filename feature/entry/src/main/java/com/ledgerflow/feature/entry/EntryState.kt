package com.ledgerflow.feature.entry

import androidx.compose.runtime.Immutable
import com.ledgerflow.core.model.CategoryTree
import com.ledgerflow.core.model.LedgerType
import com.ledgerflow.core.model.Merchant
import com.ledgerflow.core.model.PaymentMethod

/**
 * The manual entry form (SPEC.md §5.4).
 *
 * Everything the user has typed is a value in here, and everything in here is
 * persisted to `draft_entry` behind a 300 ms debounce (BUG6). Nothing the user
 * has entered lives only in the composition.
 *
 * [amountText] is what is in the field; [amountMinor] is what will be stored.
 * The text exists because reformatting a money field while someone is typing
 * moves the caret out from under their thumb; the `Long` exists because Law 3
 * says money is an integer. They are not two sources of truth -- the `Long` is
 * derived from the text by `MoneyFormat.parse` in one place, with integer
 * arithmetic and no `Double` anywhere on the path.
 */
@Immutable
public data class EntryUiState(
    /**
     * Which book. Not a filter: the two ledgers are disjoint (Law 2), so this
     * selects a partition, and switching it clears the assignment because a
     * debit category does not exist in the credit tree.
     */
    val ledger: LedgerType = LedgerType.DEBIT,

    /** Raw, exactly as typed. Empty renders as a placeholder, not as "0.00". */
    val amountText: String = "",

    /** Derived from [amountText]. What the approval receives. */
    val amountMinor: Long = 0L,

    val currencyCode: String = DEFAULT_CURRENCY,

    val categoryId: String? = null,
    val subcategoryId: String? = null,
    val merchantId: String? = null,
    val paymentMethodId: String? = null,
    val note: String = "",
    val occurredAt: Long = 0L,

    val lineItems: List<EntryLineItem> = emptyList(),

    /** The taxonomy for [ledger], for the pickers. */
    val tree: List<CategoryTree> = emptyList(),
    val merchants: List<Merchant> = emptyList(),
    val paymentMethods: List<PaymentMethod> = emptyList(),

    /** §5.4's repeat-expense chips, already resolved to names. */
    val combos: List<EntryComboChip> = emptyList(),

    val picker: EntryPicker? = null,
    val choosingDate: Boolean = false,
    val confirmingDiscard: Boolean = false,

    /**
     * True when this form was restored from a draft rather than started empty.
     *
     * Surfaced to the user (§6.1.2): resuming silently would leave someone
     * wondering why yesterday's half-typed amount is on screen.
     */
    val resumedFromDraft: Boolean = false,

    val isSaving: Boolean = false,

    /** Set once the entry is committed; the shell navigates away on it. */
    val savedEntryId: String? = null,

    /** A refusal, in words the user can act on. */
    val message: String? = null,
) {
    val canSave: Boolean get() = amountMinor > 0L && !isSaving

    /** What the line items add up to. The delta against [amountMinor] is shown, never hidden. */
    val lineItemTotalMinor: Long get() = lineItems.sumOf { it.amountMinor }

    val unallocatedMinor: Long get() = amountMinor - lineItemTotalMinor

    val selectedCategory: String?
        get() = tree.firstOrNull { it.parent.id == categoryId }?.parent?.name

    val selectedSubcategory: String?
        get() = tree.flatMap { it.children }.firstOrNull { it.id == subcategoryId }?.name

    val selectedMerchant: String?
        get() = merchants.firstOrNull { it.id == merchantId }?.canonicalName

    val selectedPaymentMethod: String?
        get() = paymentMethods.firstOrNull { it.id == paymentMethodId }?.label

    public companion object {
        /** Overwritten from `app_meta.baseCurrency` as soon as the vault answers. */
        public const val DEFAULT_CURRENCY: String = "INR"
    }
}

/**
 * One line of a multi-line entry, as the form holds it.
 *
 * [key] is a client-side identity for the `LazyColumn`-free editor and for the
 * draft payload; it is not the eventual `line_item.id`, which the approval
 * mints. Without it, removing the second of three rows re-keys the third and
 * Compose reuses the wrong text field.
 */
@Immutable
public data class EntryLineItem(
    val key: String,
    val name: String = "",
    /** Raw text, for the same caret reason as [EntryUiState.amountText]. */
    val amountText: String = "",
    val amountMinor: Long = 0L,
)

/** A repeat-expense chip: a combination already used, resolved to names (§5.4). */
@Immutable
public data class EntryComboChip(
    val label: String,
    val categoryId: String,
    val subcategoryId: String?,
    val merchantId: String?,
    val paymentMethodId: String?,
)

/**
 * Which picker is open, if any.
 *
 * Modelled as state rather than as a `remember` inside the composable, for the
 * reason `CategoriesUiState.dialog` gives: a rotation mid-choice must not lose
 * the choice, and half-entered work disappearing is BUG6 in miniature.
 */
public sealed interface EntryPicker {

    public data object Category : EntryPicker

    /** Only reachable once a category is chosen — a subcategory needs its parent. */
    public data class Subcategory(val parentId: String) : EntryPicker

    public data object Merchant : EntryPicker

    public data object PaymentMethod : EntryPicker
}

public sealed interface EntryEvent {

    public data class LedgerSelected(val ledger: LedgerType) : EntryEvent

    /** The amount field's raw text. Parsed to minor units by the ViewModel. */
    public data class AmountChanged(val text: String) : EntryEvent

    public data class NoteChanged(val value: String) : EntryEvent

    public data class PickerOpened(val picker: EntryPicker) : EntryEvent

    public data object DateRequested : EntryEvent
    public data class DateSelected(val epochMillis: Long) : EntryEvent
    public data object DateDismissed : EntryEvent

    /** Null clears the assignment — "no merchant" is a legitimate answer. */
    public data class PickerItemSelected(val id: String?) : EntryEvent
    public data object PickerDismissed : EntryEvent

    public data class ComboSelected(val index: Int) : EntryEvent

    public data object LineItemAdded : EntryEvent
    public data class LineItemNameChanged(val key: String, val value: String) : EntryEvent
    public data class LineItemAmountChanged(val key: String, val text: String) : EntryEvent
    public data class LineItemRemoved(val key: String) : EntryEvent

    public data object SaveRequested : EntryEvent

    /** "Start fresh" over a resumed draft — behind a confirmation (§6.1.2). */
    public data object DiscardRequested : EntryEvent
    public data object DiscardConfirmed : EntryEvent
    public data object DiscardDismissed : EntryEvent

    public data object MessageDismissed : EntryEvent
    public data object ResumeNoticeDismissed : EntryEvent
}
