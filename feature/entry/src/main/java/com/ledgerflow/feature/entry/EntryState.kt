package com.ledgerflow.feature.entry

import androidx.compose.runtime.Immutable
import com.ledgerflow.core.model.CategoryTree
import com.ledgerflow.core.model.LedgerType
import com.ledgerflow.core.model.Merchant
import com.ledgerflow.core.model.Money
import com.ledgerflow.core.model.PaymentMethod
import com.ledgerflow.core.model.Quantity
import com.ledgerflow.core.ui.lineitem.LineItemEditorState

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

    /**
     * Whether this entry files at line grain (ADR-0018).
     *
     * The `Single item | Itemised` choice, and it is not cosmetic: an itemised
     * entry stores **no** entry-level category, so this flag decides whether
     * [categoryId] or the lines' own categories are what gets written.
     */
    val itemised: Boolean = false,

    val lineItems: List<EntryLineItem> = emptyList(),

    /**
     * The line editor's state, with names resolved and amounts formatted.
     *
     * Built in the ViewModel rather than by the screen because it is derived
     * from the taxonomy and the base currency, neither of which a stateless
     * composable should be looking up (CLAUDE.md §5).
     */
    val editor: LineItemEditorState = LineItemEditorState(),

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

    /**
     * What is typed in the merchant picker's field (SPEC.md §5.4).
     *
     * Serves as both the filter over existing merchants and the name a new one
     * would be created under -- one field, because they are one act: the user
     * types a shop's name, and whether it already exists is the app's problem
     * rather than something to decide before choosing which control to use.
     *
     * Lives here rather than in a `remember` for the reason the picker itself
     * does: a rotation mid-typing must not lose it.
     */
    val merchantQuery: String = "",
    val choosingDate: Boolean = false,
    val confirmingDiscard: Boolean = false,

    /**
     * True while asking whether to drop the lines and go back to a single item.
     *
     * Leaving itemised mode destroys work the user typed, so it is confirmed
     * for the same reason discarding a draft is (BUG6): an accidental tap on a
     * two-option control loses it exactly as thoroughly as a process death.
     */
    val confirmingSingleItem: Boolean = false,

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

    /**
     * Set when Cancel has nothing left to back out of and the screen should
     * close. Cancel on a form with content parks it instead — see the
     * ViewModel's `cancel`.
     */
    val dismissed: Boolean = false,

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
 * One line of an itemised entry, as the form holds it (ADR-0018).
 *
 * [key] is a client-side identity for the editor and for the draft payload; it
 * is not the eventual `line_item.id`, which the approval mints. Without it,
 * removing the second of three rows re-keys the third and Compose reuses the
 * wrong text field.
 *
 * The line carries its own category and subcategory, and for an itemised entry
 * those are the *only* filing there is -- the entry stores none. That is the
 * whole point of the feature: a ₹1,000 bill at a shop selling across categories
 * is not ₹1,000 of one category.
 */
@Immutable
public data class EntryLineItem(
    val key: String,
    val name: String = "",
    /** Raw text, for the same caret reason as [EntryUiState.amountText]. */
    val unitPriceText: String = "",
    val unitPriceMinor: Long = 0L,
    /** Raw text. Blank reads as one -- see `QuantityFormat.parse`. */
    val quantityText: String = "",
    val quantityMilli: Long = Quantity.SCALE,
    val categoryId: String? = null,
    val subcategoryId: String? = null,
) {
    /**
     * `unit price × quantity`, in integers (Law 3).
     *
     * Derived rather than stored, so the three numbers cannot disagree. It is
     * also why the editor shows the line total read-only: a third editable
     * figure would be a third thing to keep in step.
     */
    val amountMinor: Long get() = (Money(unitPriceMinor) * Quantity(quantityMilli)).minor
}

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
    /**
     * "Zepto · Food & Dining" — the merchant and category chosen so far.
     *
     * Resolved to *names* here rather than carried as ids, because a card
     * cannot look anything up: it is handed a finished string. Null while
     * nothing has been picked, which is a real state and the most common one
     * for a draft a few keystrokes old.
     *
     * **Two names, not three.** The subcategory was here and came out: on a
     * card fixed at `peekCardWidth` three names overran the line, so the
     * subcategory was ellipsised away and a long one took the category with it.
     * The same two fields the Ledger's pending rows show, for the same reason.
     *
     * The entry form builds this from the payload directly — it owns that
     * shape — so unlike the Ledger's pending rows it needs no denormalised
     * columns to do it.
     */
    val filedAs: String? = null,
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

    /**
     * @param lineKey the line being filed, or null for the entry itself.
     *
     * One picker serves both, rather than a second pair of cases: the list, the
     * empty message and the "Clear" affordance are identical, and the only
     * difference is where the answer lands. A separate `LineCategory` case
     * would have added a branch to five exhaustive `when`s to say the same
     * thing twice.
     */
    public data class Category(val lineKey: String? = null) : EntryPicker

    /** Only reachable once a category is chosen — a subcategory needs its parent. */
    public data class Subcategory(val parentId: String, val lineKey: String? = null) : EntryPicker

    public data object Merchant : EntryPicker

    public data object PaymentMethod : EntryPicker
}

/**
 * The line this picker is filing, or null when it is the entry's own.
 *
 * One accessor rather than a `when` at every call site: three places need to
 * know, and only the two category pickers can ever answer anything but null.
 */
public val EntryPicker.lineKey: String?
    get() = when (this) {
        is EntryPicker.Category -> lineKey
        is EntryPicker.Subcategory -> lineKey
        EntryPicker.Merchant, EntryPicker.PaymentMethod -> null
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

    /** Typing in the merchant picker: filters the list, and names a new merchant. */
    public data class MerchantQueryChanged(val value: String) : EntryEvent

    /**
     * Create the merchant named by [EntryUiState.merchantQuery] and file this
     * entry against it.
     *
     * Goes through `createOrGet`, so typing a name that already exists selects
     * that merchant rather than refusing — including one the user had hidden,
     * which comes back with its aliases (BUG11).
     */
    public data object MerchantCreateRequested : EntryEvent
    public data object PickerDismissed : EntryEvent

    public data class ComboSelected(val index: Int) : EntryEvent

    /** `Single item | Itemised` (ADR-0018). */
    public data class ModeSelected(val itemised: Boolean) : EntryEvent

    /** Leaving itemised mode with lines entered is confirmed before it happens. */
    public data object SingleItemConfirmed : EntryEvent
    public data object SingleItemDismissed : EntryEvent

    public data object LineItemAdded : EntryEvent
    public data class LineItemNameChanged(val key: String, val value: String) : EntryEvent
    public data class LineItemUnitPriceChanged(val key: String, val text: String) : EntryEvent
    public data class LineItemQuantityChanged(val key: String, val text: String) : EntryEvent
    public data class LineItemRemoved(val key: String) : EntryEvent

    /** One line at a time is open for editing; the rest stay one-line summaries. */
    public data class LineItemExpanded(val key: String) : EntryEvent
    public data object LineItemCollapsed : EntryEvent

    /**
     * Filing one line.
     *
     * Separate events rather than the screen constructing an [EntryPicker]
     * itself: the subcategory picker needs the *line's* category as its parent,
     * which is a lookup into form state, and a stateless composable has no
     * business doing it (CLAUDE.md §5).
     */
    public data class LineItemCategoryRequested(val key: String) : EntryEvent
    public data class LineItemSubcategoryRequested(val key: String) : EntryEvent

    /** Load an unsaved entry from the stack into the form. */
    public data class DraftOpened(val id: String) : EntryEvent

    /** Ask to throw one away. Confirmed separately — it is unsaved work. */
    public data class DraftDiscardRequested(val id: String) : EntryEvent
    public data object DraftDiscardConfirmed : EntryEvent
    public data object DraftDiscardDismissed : EntryEvent

    /** Park what is in the form and start another entry. */
    public data object NewDraftStarted : EntryEvent

    public data object SaveRequested : EntryEvent

    /** Back out: parks a form with content, closes an empty one. */
    public data object CancelRequested : EntryEvent

    /** "Start fresh" over a resumed draft — behind a confirmation (§6.1.2). */
    public data object DiscardRequested : EntryEvent
    public data object DiscardConfirmed : EntryEvent
    public data object DiscardDismissed : EntryEvent

    public data object MessageDismissed : EntryEvent
    public data object ResumeNoticeDismissed : EntryEvent
}
