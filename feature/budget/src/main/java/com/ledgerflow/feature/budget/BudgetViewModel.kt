package com.ledgerflow.feature.budget

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ledgerflow.core.common.time.Clock
import com.ledgerflow.core.common.time.LocalDates
import com.ledgerflow.core.designsystem.format.MoneyFormat
import com.ledgerflow.core.domain.analytics.AnalyticsRange
import com.ledgerflow.core.domain.analytics.AnalyticsWindow
import com.ledgerflow.core.domain.analytics.BudgetError
import com.ledgerflow.core.domain.analytics.BudgetResult
import com.ledgerflow.core.domain.analytics.NewBudget
import com.ledgerflow.core.domain.ledger.LedgerRepository
import com.ledgerflow.core.domain.taxonomy.CategoryRepository
import com.ledgerflow.core.domain.usecase.CreateBudgetUseCase
import com.ledgerflow.core.domain.usecase.DeleteBudgetUseCase
import com.ledgerflow.core.domain.usecase.GetAnalyticsSnapshotUseCase
import com.ledgerflow.core.domain.usecase.UpdateBudgetAmountUseCase
import com.ledgerflow.core.model.BudgetPeriod
import com.ledgerflow.core.model.LedgerType
import com.ledgerflow.core.model.Money
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Budget management (`SPEC.md` §5.7).
 *
 * **Progress comes from the analytics snapshot, not from a second query.** The
 * figures here have to be the same ones the Analytics tab shows, and the only
 * way to guarantee that is to read them from the same place — two independent
 * "spent so far" calculations would eventually disagree, and the user would be
 * looking at two screens with two answers about one budget.
 *
 * **Reloaded after every write rather than observed.** `observeAll()` would tell
 * this screen when the *budgets* changed but not when spending did, so a list
 * that only observed budgets would show stale progress after an approval. A
 * reload is one query and this screen is not hot.
 */
@HiltViewModel
public class BudgetViewModel @Inject constructor(
    private val getSnapshot: GetAnalyticsSnapshotUseCase,
    private val createBudget: CreateBudgetUseCase,
    private val updateAmount: UpdateBudgetAmountUseCase,
    private val deleteBudget: DeleteBudgetUseCase,
    private val categories: CategoryRepository,
    private val ledgerRepository: LedgerRepository,
    private val clock: Clock,
) : ViewModel() {

    private val _state = MutableStateFlow(BudgetUiState())
    public val state: StateFlow<BudgetUiState> = _state.asStateFlow()

    init {
        reload()
    }

    /**
     * Split by what the event is about — the list, or the form.
     *
     * The two halves grew past the point where the whole `when` fits on a
     * screen, and they are read at different times: the list events are the
     * screen's verbs, the editor events are one dialog's field-by-field state.
     */
    public fun onEvent(event: BudgetEvent) {
        when (event) {
            BudgetEvent.AddClicked -> _state.update {
                // The period starts today unless the picker moves it. Guessing
                // the 1st of the month would silently choose a period the user
                // did not, and §5.7's periods repeat from `start_date`.
                it.copy(
                    editor = BudgetEditorState(startDate = LocalDates.of(clock.nowMillis())),
                    subcategoriesForPicked = emptyList(),
                )
            }
            is BudgetEvent.EditClicked -> openEditor(event.id)
            is BudgetEvent.DeleteClicked -> delete(event.id)
            BudgetEvent.SaveClicked -> save()
            BudgetEvent.MessageShown -> _state.update { it.copy(message = null) }
            BudgetEvent.EditorDismissed -> _state.update { it.copy(editor = null) }
            else -> onEditorEvent(event)
        }
    }

    private fun openEditor(id: String) {
        _state.update { current ->
            val progress = current.budgets.firstOrNull { it.budget.id == id }
                ?: return@update current
            current.copy(
                editor = BudgetEditorState(
                    editingId = progress.budget.id,
                    categoryId = progress.budget.categoryId,
                    categoryName = progress.categoryName,
                    period = progress.budget.period,
                    amountText = MoneyFormat.plain(
                        progress.budget.amount.minor,
                        current.baseCurrency,
                    ),
                    subcategoryId = progress.budget.subcategoryId,
                    startDate = progress.budget.startDate,
                    rolloverEnabled = progress.budget.rolloverEnabled,
                ),
            )
        }
    }

    /** Field-by-field edits. Every branch is a `copy` on the editor. */
    private fun onEditorEvent(event: BudgetEvent) {
        when (event) {
            is BudgetEvent.CategoryPicked -> {
                _state.update { current ->
                    val category =
                        current.availableCategories.firstOrNull { it.id == event.categoryId }
                    current.copy(
                        editor = current.editor?.copy(
                            categoryId = event.categoryId,
                            categoryName = category?.name.orEmpty(),
                            // Changing category invalidates the old choice: a
                            // subcategory of a different parent would file
                            // spending nowhere the budget can see.
                            subcategoryId = null,
                            error = null,
                        ),
                    )
                }
                loadSubcategories(event.categoryId)
            }

            is BudgetEvent.SubcategoryPicked -> _state.update {
                it.copy(editor = it.editor?.copy(subcategoryId = event.subcategoryId))
            }

            is BudgetEvent.PeriodPicked -> _state.update {
                it.copy(editor = it.editor?.copy(period = event.period))
            }

            BudgetEvent.RolloverToggled -> _state.update { current ->
                val editor = current.editor ?: return@update current
                current.copy(editor = editor.copy(rolloverEnabled = !editor.rolloverEnabled))
            }

            BudgetEvent.StartDateClicked -> _state.update {
                it.copy(editor = it.editor?.copy(showDatePicker = true))
            }

            is BudgetEvent.StartDatePicked -> _state.update {
                it.copy(
                    editor = it.editor?.copy(
                        startDate = event.epochDay,
                        showDatePicker = false,
                    ),
                )
            }

            BudgetEvent.DatePickerDismissed -> _state.update {
                it.copy(editor = it.editor?.copy(showDatePicker = false))
            }

            is BudgetEvent.AmountChanged -> _state.update {
                it.copy(editor = it.editor?.copy(amountText = event.text, error = null))
            }

            // Handled by `onEvent`; listed so the `when` stays exhaustive over
            // the sealed type rather than needing an `else` (CLAUDE.md §5).
            BudgetEvent.AddClicked,
            is BudgetEvent.EditClicked,
            is BudgetEvent.DeleteClicked,
            BudgetEvent.SaveClicked,
            BudgetEvent.EditorDismissed,
            BudgetEvent.MessageShown,
            -> Unit
        }
    }

    private fun save() {
        val editor = _state.value.editor ?: return
        val currency = _state.value.baseCurrency

        // **Say why, rather than doing nothing.** `LfDialog`'s confirm button is
        // always enabled, so an early `return` here made Save a control that
        // silently did nothing when no category was picked -- observed on
        // device, and indistinguishable from a crash to the person tapping it.
        val categoryId = editor.categoryId
        if (categoryId == null) {
            _state.update { it.copy(editor = editor.copy(error = "Pick a category first")) }
            return
        }
        val minor = MoneyFormat.parse(editor.amountText, currency)
        if (minor <= 0L) {
            _state.update {
                it.copy(editor = editor.copy(error = "Enter an amount above zero"))
            }
            return
        }

        viewModelScope.launch {
            val result = if (editor.isEdit) {
                updateAmount(requireNotNull(editor.editingId), Money(minor))
            } else {
                createBudget(
                    NewBudget(
                        categoryId = categoryId,
                        subcategoryId = editor.subcategoryId,
                        period = editor.period,
                        amount = Money(minor),
                        startDate = editor.startDate,
                        rolloverEnabled = editor.rolloverEnabled,
                    ),
                )
            }

            when (result) {
                is BudgetResult.Success -> {
                    _state.update { it.copy(editor = null, message = "Budget saved") }
                    reload()
                }
                is BudgetResult.Failure -> _state.update {
                    it.copy(editor = it.editor?.copy(error = result.error.toMessage()))
                }
            }
        }
    }

    private fun delete(id: String) {
        viewModelScope.launch {
            when (deleteBudget(id)) {
                is BudgetResult.Success -> {
                    _state.update { it.copy(message = "Budget deleted") }
                    reload()
                }
                is BudgetResult.Failure -> _state.update { it.copy(message = "Could not delete") }
            }
        }
    }

    private fun loadSubcategories(categoryId: String) {
        viewModelScope.launch {
            val children = categories.observeTree(LedgerType.DEBIT).first()
                .firstOrNull { it.parent.id == categoryId }
                ?.children
                .orEmpty()
            _state.update { it.copy(subcategoriesForPicked = children) }
        }
    }

    private fun reload() {
        viewModelScope.launch {
            val currency = ledgerRepository.baseCurrency() ?: DEFAULT_CURRENCY
            val today = LocalDates.of(clock.nowMillis())
            val snapshot = getSnapshot(
                ledger = LedgerType.DEBIT,
                window = AnalyticsWindow.endingOn(today, AnalyticsRange.MONTH),
                comparePrevious = false,
            )
            val budgeted = snapshot.budgets.map { it.budget.categoryId }.toSet()
            // §5.7 allows one budget per category, so the picker only offers
            // categories that do not have one -- an option that always fails is
            // not an option.
            val available = categories.observe(LedgerType.DEBIT).first()
                .filterNot { it.id in budgeted }

            _state.update {
                it.copy(
                    isLoading = false,
                    budgets = snapshot.budgets,
                    baseCurrency = currency,
                    availableCategories = available,
                )
            }
        }
    }

    private fun BudgetError.toMessage(): String = when (this) {
        is BudgetError.AmountNotPositive -> "Enter an amount above zero"
        is BudgetError.AlreadyBudgeted -> "That category already has a budget"
        is BudgetError.CategoryHidden -> "That category is hidden"
        is BudgetError.CategoryNotFound -> "That category no longer exists"
    }

    private companion object {
        const val DEFAULT_CURRENCY = "INR"
    }
}
