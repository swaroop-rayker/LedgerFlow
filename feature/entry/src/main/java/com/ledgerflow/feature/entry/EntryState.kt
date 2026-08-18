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

    /**
     * Other unsaved entries in this book, most recent first (ADR-0013).
     *
     * Excludes the one currently in the form. This is the surface that answers
     * D-06's objection to unbounded drafts -- they pile up only if nothing
     * shows them.
     */
    val unsaved: List<EntryDraftCard> = emptyList(),

    /** The draft this form is currently editing, if it has been written yet. */
    val openDraftId: String? = null,

    val picker: EntryPicker? = null,
    val choosingDate: Boolean = false,
    val confirmingDiscard: Boolean = false,

    /**
     * The stack card awaiting a discard confirmation.
     *
     * Discarding from the stack is one tap on a small control next to other
     * small controls, and what it destroys is unsaved work. It gets the same
     * confirmation "start fresh" does — BUG6 is about losing typing, and an
     * accidental tap loses it just as thoroughly as a process death.
     */
    val discardingDraft: EntryDraftCard? = null,

    /**
     * True when this form was restored from a draft rather than started empty.
     *
     * Surfaced to the user (§6.1.2): resuming silently would leave someone
     * wondering why yesterday's half-typed amount is on screen.
     */
    val resumedFromDraft: Boolean = false,

    /**
     * True until the draft read that runs on open has finished.
     *
     * The screen waits for this before deciding whether to raise the keyboard:
     * at first composition [resumedFromDraft] is still false for a form that is
     * about to be restored, so a decision made then always autofocuses -- which
     * put the keyboard over the resume notice.
     */
    val isRestoring: Boolean = true,

    /**
     * Bumped whenever the form is replaced wholesale — a new draft, a ledger
     * switch, a save. The screen keys its focus effect on it, so each fresh
     * form gets the caret without the effect re-running on every keystroke.
     */
    val formGeneration: Int = 0,

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

/** One unsaved entry in the stack. */
@Immutable
public data class EntryDraftCard(
    val id: String,
    val amountMinor: Long,
    val currencyCode: String,
    val note: String?,
    val lineItemCount: Int,
    val updatedAt: Long,
    /** Already relative ("2m ago"), resolved against the injected clock. */
    val age: String,
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

    /** Load an unsaved entry from the stack into the form. */
    public data class DraftOpened(val id: String) : EntryEvent

    /** Ask to throw one away. Confirmed separately — it is unsaved work. */
    public data class DraftDiscardRequested(val id: String) : EntryEvent
    public data object DraftDiscardConfirmed : EntryEvent
    public data object DraftDiscardDismissed : EntryEvent

    /** Park what is in the form and start another entry. */
    public data object NewDraftStarted : EntryEvent

    public data object SaveRequested : EntryEvent

    /** "Start fresh" over a resumed draft — behind a confirmation (§6.1.2). */
    public data object DiscardRequested : EntryEvent
    public data object DiscardConfirmed : EntryEvent
    public data object DiscardDismissed : EntryEvent

    public data object MessageDismissed : EntryEvent
    public data object ResumeNoticeDismissed : EntryEvent
}
