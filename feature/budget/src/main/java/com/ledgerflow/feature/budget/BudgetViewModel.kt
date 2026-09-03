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

    public fun onEvent(event: BudgetEvent) {
        when (event) {
            BudgetEvent.AddClicked -> _state.update { it.copy(editor = BudgetEditorState()) }

            is BudgetEvent.EditClicked -> _state.update { current ->
                val progress = current.budgets.firstOrNull { it.budget.id == event.id }
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
                    ),
                )
            }

            is BudgetEvent.CategoryPicked -> _state.update { current ->
                val category = current.availableCategories.firstOrNull { it.id == event.categoryId }
                current.copy(
                    editor = current.editor?.copy(
                        categoryId = event.categoryId,
                        categoryName = category?.name.orEmpty(),
                        error = null,
                    ),
                )
            }

            is BudgetEvent.PeriodPicked -> _state.update {
                it.copy(editor = it.editor?.copy(period = event.period))
            }

            is BudgetEvent.AmountChanged -> _state.update {
                it.copy(editor = it.editor?.copy(amountText = event.text, error = null))
            }

            BudgetEvent.EditorDismissed -> _state.update { it.copy(editor = null) }
            BudgetEvent.MessageShown -> _state.update { it.copy(message = null) }
            BudgetEvent.SaveClicked -> save()
            is BudgetEvent.DeleteClicked -> delete(event.id)
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
                        period = editor.period,
                        amount = Money(minor),
                        // The period starts today, which is the only start date
                        // that needs no explanation. A date picker can come
                        // later; guessing the 1st of the month would silently
                        // choose a period the user did not.
                        startDate = LocalDates.of(clock.nowMillis()),
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
