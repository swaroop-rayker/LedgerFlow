package com.ledgerflow.feature.budget

import androidx.compose.runtime.Immutable
import com.ledgerflow.core.domain.analytics.BudgetProgress
import com.ledgerflow.core.model.BudgetPeriod
import com.ledgerflow.core.model.Category

/**
 * Budget management (`SPEC.md` §5.7).
 *
 * The list carries [BudgetProgress] rather than bare budgets, because a budget
 * on its own is a number the user already typed — what makes the screen worth
 * opening is how much of it is gone.
 */
@Immutable
public data class BudgetUiState(
    val isLoading: Boolean = true,
    val budgets: List<BudgetProgress> = emptyList(),
    val baseCurrency: String = "INR",
    /** Debit categories with no live budget — everything the form may offer. */
    val availableCategories: List<Category> = emptyList(),
    val editor: BudgetEditorState? = null,
    val message: String? = null,
) {
    public val showEmptyState: Boolean get() = !isLoading && budgets.isEmpty()

    /**
     * A budget needs a category, so with none left the form cannot open.
     *
     * §5.7 allows one budget per category, so a user who has budgeted
     * everything has nothing to add — and an "Add" button that opens an empty
     * picker is worse than one that explains itself.
     */
    public val canAddBudget: Boolean get() = availableCategories.isNotEmpty()
}

/**
 * The create/edit sheet.
 *
 * `amountText` is the raw string, not a parsed `Long`: the field has to hold
 * "12." and "" while someone types, and parsing on every keystroke would fight
 * them (ADR-0012's argument for the entry form, applied here).
 */
@Immutable
public data class BudgetEditorState(
    val editingId: String? = null,
    val categoryId: String? = null,
    val categoryName: String = "",
    val period: BudgetPeriod = BudgetPeriod.MONTHLY,
    val amountText: String = "",
    val error: String? = null,
) {
    public val isEdit: Boolean get() = editingId != null
    public val canSave: Boolean get() = categoryId != null && amountText.isNotBlank()
}

public sealed interface BudgetEvent {
    public data object AddClicked : BudgetEvent
    public data class EditClicked(val id: String) : BudgetEvent
    public data class DeleteClicked(val id: String) : BudgetEvent
    public data class CategoryPicked(val categoryId: String) : BudgetEvent
    public data class PeriodPicked(val period: BudgetPeriod) : BudgetEvent
    public data class AmountChanged(val text: String) : BudgetEvent
    public data object SaveClicked : BudgetEvent
    public data object EditorDismissed : BudgetEvent
    public data object MessageShown : BudgetEvent
}
