package com.ledgerflow.feature.categories

import androidx.compose.runtime.Immutable
import com.ledgerflow.core.model.CategoryTree
import com.ledgerflow.core.model.LedgerType
import com.ledgerflow.core.model.Merchant
import com.ledgerflow.core.model.PaymentMethod
import com.ledgerflow.core.model.PaymentMethodType

/** The three things this screen manages (SPEC.md §5.5). */
public enum class TaxonomySection { Categories, Merchants, PaymentMethods }

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

    val dialog: TaxonomyDialog? = null,
    val isWorking: Boolean = false,

    /** A refusal the user should see, in words they can act on. */
    val message: String? = null,
)

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
        val candidates: List<CategoryChoice>,
        val targetId: String? = null,
    ) : TaxonomyDialog

    public data class MergeMerchant(
        val sourceId: String,
        val sourceName: String,
        val candidates: List<MerchantChoice>,
        val targetId: String? = null,
    ) : TaxonomyDialog

    public data class NewPaymentMethod(
        val label: String = "",
        val type: PaymentMethodType = PaymentMethodType.UPI,
        val last4: String = "",
    ) : TaxonomyDialog
}

public enum class TextPromptKind { NewCategory, NewSubcategory, RenameCategory, NewMerchant, RenameMerchant }

@Immutable
public data class CategoryChoice(val id: String, val name: String)

@Immutable
public data class MerchantChoice(val id: String, val name: String)

public sealed interface CategoriesEvent {
    public data class SectionSelected(val section: TaxonomySection) : CategoriesEvent
    public data class LedgerSelected(val ledger: LedgerType) : CategoriesEvent

    public data class AddCategory(val parentId: String?, val parentName: String?) : CategoriesEvent
    public data class RenameCategory(val id: String, val currentName: String) : CategoriesEvent
    public data class DeleteCategory(val id: String, val name: String) : CategoriesEvent

    public data object AddMerchant : CategoriesEvent
    public data class RenameMerchant(val id: String, val currentName: String) : CategoriesEvent
    public data class StartMergeMerchant(val id: String, val name: String) : CategoriesEvent
    public data class DeleteMerchant(val id: String) : CategoriesEvent

    public data object AddPaymentMethod : CategoriesEvent
    public data class SetDefaultPaymentMethod(val id: String) : CategoriesEvent
    public data class DeletePaymentMethod(val id: String) : CategoriesEvent

    /** Dialog editing. Kept generic so one set of handlers serves every prompt. */
    public data class DialogTextChanged(val value: String) : CategoriesEvent
    public data class DialogTargetSelected(val id: String) : CategoriesEvent
    public data class DialogTypeSelected(val type: PaymentMethodType) : CategoriesEvent
    public data class DialogLast4Changed(val value: String) : CategoriesEvent
    public data object DialogConfirmed : CategoriesEvent
    public data object DialogDismissed : CategoriesEvent

    public data object MessageDismissed : CategoriesEvent
}
