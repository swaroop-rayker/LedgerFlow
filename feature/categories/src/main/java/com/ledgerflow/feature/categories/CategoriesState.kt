package com.ledgerflow.feature.categories

import androidx.compose.runtime.Immutable
import com.ledgerflow.core.model.CategoryTree
import com.ledgerflow.core.model.HiddenTaxonomy
import com.ledgerflow.core.model.LedgerType
import com.ledgerflow.core.model.Merchant
import com.ledgerflow.core.model.MerchantCategory
import com.ledgerflow.core.model.PaymentMethod
import com.ledgerflow.core.model.PaymentMethodType

/** The three things this screen manages (SPEC.md §5.5). */
public enum class TaxonomySection { Categories, Merchants, PaymentMethods }

/**
 * Which merchants the Merchants tab lists (V2-PLAN §5.2). Uncategorised lists
 * exactly the ones still to file, so the work can be cleared in one sitting.
 */
public enum class MerchantFilter { All, Uncategorised }

@Immutable
public data class CategoriesUiState(
    val section: TaxonomySection = TaxonomySection.Categories,

    /**
     * Which book's tree is shown. Not a filter over shared data — the two trees
     * are disjoint (Law 2), so this selects a partition.
     */
    val ledger: LedgerType = LedgerType.DEBIT,

    val tree: List<CategoryTree> = emptyList(),
    val merchants: List<Merchant> = emptyList(),
    val paymentMethods: List<PaymentMethod> = emptyList(),

    /**
     * What has been hidden **in the section currently shown** (ADR-0016).
     *
     * One list rather than three, selected by [section] in the ViewModel. The
     * screen only ever renders the section it is on, so collecting the other
     * two would keep two database reads alive to populate a list nothing can
     * see.
     */
    val hidden: List<HiddenTaxonomy> = emptyList(),

    /**
     * Whether the hidden section is open.
     *
     * Collapsed by default and per-section: hidden rows are the exception on a
     * screen whose job is showing what you have, and a list that opens with a
     * block of things the user chose to get rid of buries the ones they kept.
     */
    val hiddenExpanded: Boolean = false,

    val dialog: TaxonomyDialog? = null,
    val isWorking: Boolean = false,

    /** A refusal the user should see, in words they can act on. */
    val message: String? = null,

    /** Live merchant categories (V2-6), for each merchant's second line and the picker. */
    val merchantCategories: List<MerchantCategory> = emptyList(),

    val merchantFilter: MerchantFilter = MerchantFilter.All,

    /** Selecting merchants to file several at once; the pinned bar becomes the selection bar. */
    val selecting: Boolean = false,
    val selected: Set<String> = emptySet(),
) {
    /** Merchants still to file, for the filter's count. */
    public val uncategorisedCount: Int get() = merchants.count { it.merchantCategoryId == null }

    /** The merchants the tab lists under [merchantFilter]. */
    public val visibleMerchants: List<Merchant>
        get() = when (merchantFilter) {
            MerchantFilter.All -> merchants
            MerchantFilter.Uncategorised -> merchants.filter { it.merchantCategoryId == null }
        }

    /** A merchant's second line: its category, or Uncategorised. */
    public fun merchantCategoryName(id: String?): String =
        id?.let { wanted -> merchantCategories.firstOrNull { it.id == wanted }?.name } ?: UNCATEGORISED
}

/** The name of no merchant category (V2-PLAN §5): a bucket, not a category of its own. */
public const val UNCATEGORISED: String = "Uncategorised"

/**
 * What is being asked, if anything.
 *
 * Modelled as state rather than as one-shot navigation because every one of
 * these survives a rotation mid-typing. A dialog driven by a `remember` inside
 * the composable loses a half-entered category name to a config change, which
 * is a small version of exactly the problem BUG6 is about.
 */
public sealed interface TaxonomyDialog {

    /** Create or rename: one shape, because the difference is only the title. */
    public data class TextPrompt(
        val kind: TextPromptKind,
        val value: String,
        /** Parent category, merchant, or category being renamed. */
        val contextId: String? = null,
        val contextName: String? = null,
    ) : TaxonomyDialog

    /**
     * Deleting a category that still has entries (§5.5's re-assign flow).
     *
     * [affected] comes from the repository's refusal, so the count shown is the
     * one the database actually saw rather than a number the UI counted
     * separately and could disagree about.
     */
    public data class ReassignCategory(
        val id: String,
        val name: String,
        val affected: Int,
        val candidates: List<TaxonomyChoice>,
        val targetId: String? = null,
    ) : TaxonomyDialog

    public data class MergeMerchant(
        val sourceId: String,
        val sourceName: String,
        val candidates: List<TaxonomyChoice>,
        val targetId: String? = null,
    ) : TaxonomyDialog

    /**
     * "Erase this for good?" — the mis-tap guard on the irreversible one
     * (ADR-0016).
     *
     * Separate from [ConfirmDelete] rather than a flag on it, because the two
     * questions are not the same question and the answer to one is not evidence
     * about the other. Hiding is reversible and its dialog says what survives;
     * this one is the second irreversible operation in the app and its dialog
     * has to say that plainly, in the same `Warning` treatment the bin uses.
     */
    public data class ConfirmErase(
        val target: DeleteTarget,
        val id: String,
        val name: String,
    ) : TaxonomyDialog

    /**
     * Where the entries go before the row is destroyed.
     *
     * The purge's own [TaxonomyError.ReassignRequired], asked as a question.
     * Distinct from [ReassignCategory] in one way that matters: after this
     * dialog the row is **gone**, so the copy cannot say "nothing is deleted"
     * the way the soft-delete flow's honestly can.
     */
    public data class ReassignBeforeErase(
        val target: DeleteTarget,
        val id: String,
        val name: String,
        val affected: Int,
        val candidates: List<TaxonomyChoice>,
        val targetId: String? = null,
    ) : TaxonomyDialog

    /**
     * "Are you sure?", asked before anything is written.
     *
     * A separate case rather than a flag on the actions themselves because the
     * three targets do not have the same consequence, and a confirmation that
     * rounds them all to one sentence is one the user learns to tap through.
     * The wording is chosen per [target] in `TaxonomyDialogHost`.
     *
     * For a category this is the *first* of two questions: confirming may still
     * surface [ReassignCategory] if entries would be orphaned. That is not a
     * redundant double-prompt — this one guards the mis-tap, that one asks
     * where the entries go, and only the second needs an answer supplied.
     */
    public data class ConfirmDelete(
        val target: DeleteTarget,
        val id: String,
        val name: String,
    ) : TaxonomyDialog

    public data class NewPaymentMethod(
        val label: String = "",
        val type: PaymentMethodType = PaymentMethodType.UPI,
        val last4: String = "",
    ) : TaxonomyDialog

    /**
     * A row's actions, in a sheet opened by tapping the row (owner's option A,
     * 2026-10-31).
     *
     * State rather than a `remember` for the same reason as every other case
     * here: a rotation with the sheet open keeps it open. Choosing an action
     * sends the event the row's inline button used to, and the view model
     * closes the sheet before acting on it, so what each action does is
     * unchanged.
     */
    public data class RowActions(val row: TaxonomyRow) : TaxonomyDialog

    /**
     * File one merchant or several under a merchant category (V2-6, §5.2).
     * [targetId] is the choice so far: a category id, [UNCATEGORISED_CHOICE]
     * for Uncategorised, or null when nothing is chosen yet (several merchants
     * with different categories have no one current answer).
     */
    public data class SetMerchantCategory(
        val merchantIds: List<String>,
        /** "Big Bazaar", or "3 merchants". */
        val subject: String,
        val candidates: List<TaxonomyChoice>,
        val targetId: String? = null,
    ) : TaxonomyDialog
}

/** The picker row for Uncategorised: not a category id, and never one. */
public const val UNCATEGORISED_CHOICE: String = ""

/**
 * The row a [TaxonomyDialog.RowActions] sheet is about: just enough to build
 * its actions and the events they send.
 */
public sealed interface TaxonomyRow {
    public val name: String

    /** [isChild]: a subcategory, which cannot hold subcategories of its own. */
    public data class Category(val id: String, override val name: String, val isChild: Boolean) : TaxonomyRow
    public data class Merchant(val id: String, override val name: String) : TaxonomyRow
    public data class PaymentMethod(val id: String, override val name: String, val isDefault: Boolean) : TaxonomyRow

    /** A row under the Hidden header, in whichever section is showing. */
    public data class Hidden(val id: String, override val name: String) : TaxonomyRow
}

/** What a [TaxonomyDialog.ConfirmDelete] is about to remove. */
public enum class DeleteTarget { Category, Subcategory, Merchant, PaymentMethod }

public enum class TextPromptKind { NewCategory, NewSubcategory, RenameCategory, NewMerchant, RenameMerchant }

/**
 * A row in a picker: somewhere entries can be moved to, or a merchant to merge
 * into.
 *
 * One type for all of them. It was `CategoryChoice` and `MerchantChoice`, two
 * declarations of `(id, name)` that existed because the two dialogs were
 * written at different times; the erase flow would have made it three, at which
 * point the duplication is the design rather than an accident of history.
 */
@Immutable
public data class TaxonomyChoice(val id: String, val name: String)

public sealed interface CategoriesEvent {
    public data class SectionSelected(val section: TaxonomySection) : CategoriesEvent
    public data class LedgerSelected(val ledger: LedgerType) : CategoriesEvent

    public data class AddCategory(val parentId: String?, val parentName: String?) : CategoriesEvent
    public data class RenameCategory(val id: String, val currentName: String) : CategoriesEvent
    public data class DeleteCategory(
        val id: String,
        val name: String,
        val isChild: Boolean,
    ) : CategoriesEvent

    public data object AddMerchant : CategoriesEvent
    public data class RenameMerchant(val id: String, val currentName: String) : CategoriesEvent
    public data class StartMergeMerchant(val id: String, val name: String) : CategoriesEvent
    public data class DeleteMerchant(val id: String, val name: String) : CategoriesEvent

    /** V2-6: file [merchantIds] (one from a row's sheet, several from the selection). */
    public data class SetMerchantCategory(val merchantIds: List<String>, val subject: String) : CategoriesEvent
    public data class MerchantFilterSelected(val filter: MerchantFilter) : CategoriesEvent
    public data object SelectionStarted : CategoriesEvent
    public data class SelectionToggled(val merchantId: String) : CategoriesEvent

    /** Selects every merchant the current filter shows. */
    public data object SelectAllShown : CategoriesEvent
    public data object SelectionCancelled : CategoriesEvent

    public data object AddPaymentMethod : CategoriesEvent
    public data class SetDefaultPaymentMethod(val id: String) : CategoriesEvent
    public data class DeletePaymentMethod(val id: String, val name: String) : CategoriesEvent

    /** A row was tapped: show its actions. */
    public data class RowActionsOpened(val row: TaxonomyRow) : CategoriesEvent

    /** Open or close the hidden section of whichever tab is showing. */
    public data object HiddenToggled : CategoriesEvent

    /**
     * Put a hidden row back. **Asks nothing**, deliberately.
     *
     * The bin settled this: restoring is undone by hiding again, so a
     * confirmation would guard against a mis-tap whose cost is one more tap.
     * Erasing is the only thing here that cannot be walked back, and it is the
     * only one that asks.
     */
    public data class RestoreHidden(val id: String, val name: String) : CategoriesEvent

    /** Destroy a hidden row for good. Always confirmed. */
    public data class EraseHidden(val id: String, val name: String) : CategoriesEvent

    /** Dialog editing. Kept generic so one set of handlers serves every prompt. */
    public data class DialogTextChanged(val value: String) : CategoriesEvent
    public data class DialogTargetSelected(val id: String) : CategoriesEvent
    public data class DialogTypeSelected(val type: PaymentMethodType) : CategoriesEvent
    public data class DialogLast4Changed(val value: String) : CategoriesEvent
    public data object DialogConfirmed : CategoriesEvent
    public data object DialogDismissed : CategoriesEvent

    public data object MessageDismissed : CategoriesEvent
}
