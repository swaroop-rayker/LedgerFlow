package com.ledgerflow.feature.tools.goals

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ledgerflow.core.common.time.Clock
import com.ledgerflow.core.common.time.LocalDates
import com.ledgerflow.core.designsystem.format.MoneyFormat
import com.ledgerflow.core.domain.goals.GoalDetails
import com.ledgerflow.core.domain.goals.GoalLink
import com.ledgerflow.core.domain.goals.GoalResult
import com.ledgerflow.core.domain.ledger.LedgerRepository
import com.ledgerflow.core.domain.taxonomy.CategoryRepository
import com.ledgerflow.core.domain.usecase.CreateGoalUseCase
import com.ledgerflow.core.domain.usecase.ObserveGoalsUseCase
import com.ledgerflow.core.domain.usecase.UpdateGoalUseCase
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
 * The new-goal and edit-goal form (V2-PLAN §4.1; the owner's mockup 4).
 *
 * **A linked category's book follows the category**, so the form never asks
 * which book: the picker lists both books under their own headings, and the
 * label says which one was chosen ("Savings · Expense").
 */
@HiltViewModel
public class GoalEditorViewModel @Inject constructor(
    private val observeGoals: ObserveGoalsUseCase,
    private val categories: CategoryRepository,
    private val ledger: LedgerRepository,
    private val createGoal: CreateGoalUseCase,
    private val updateGoal: UpdateGoalUseCase,
    private val clock: Clock,
    savedStateHandle: SavedStateHandle,
) : ViewModel() {

    private val editingId: String? = savedStateHandle.get<String>(GoalViewModel.GOAL_ID_ARG)?.takeIf { it.isNotEmpty() }

    private val _state = MutableStateFlow(GoalEditorUiState(editingId = editingId))
    public val state: StateFlow<GoalEditorUiState> = _state.asStateFlow()

    private var choices: List<CategoryChoice> = emptyList()

    init {
        load()
    }

    public fun onEvent(event: GoalEditorEvent) {
        when (event) {
            is GoalEditorEvent.NameChanged -> _state.update { it.copy(name = event.text, error = null) }
            is GoalEditorEvent.TargetChanged -> _state.update { it.copy(targetText = event.text, error = null) }
            is GoalEditorEvent.PickerOpened -> _state.update { it.copy(openPicker = event.picker) }
            is GoalEditorEvent.TargetDatePicked -> _state.update {
                it.copy(targetDate = event.epochDay, openPicker = null)
            }
            is GoalEditorEvent.CategoryPicked -> _state.update { current ->
                current.copy(
                    categoryId = event.id,
                    categoryLabel = event.id?.let(::labelOf),
                    openPicker = null,
                    error = null,
                )
            }
            is GoalEditorEvent.CountFromPicked -> _state.update {
                it.copy(countFrom = event.epochDay, openPicker = null)
            }
            GoalEditorEvent.SaveClicked -> save()
        }
    }

    private fun load() {
        viewModelScope.launch {
            val currency = ledger.baseCurrency() ?: DEFAULT_CURRENCY
            choices = categoryChoices(LedgerType.DEBIT, categories.observeTree(LedgerType.DEBIT).first()) +
                categoryChoices(LedgerType.CREDIT, categories.observeTree(LedgerType.CREDIT).first())
            val today = LocalDates.of(clock.nowMillis())
            val goal = editingId?.let { id -> observeGoals().first().goals.firstOrNull { it.goal.id == id }?.goal }
            _state.update { current ->
                current.copy(
                    loading = false,
                    currency = currency,
                    categoryGroups = pickerGroups(choices),
                    name = goal?.name.orEmpty(),
                    targetText = goal?.let { MoneyFormat.plain(it.target.minor, currency) }.orEmpty(),
                    targetDate = goal?.targetDate,
                    categoryId = goal?.link?.categoryId,
                    categoryLabel = goal?.link?.categoryId?.let(::labelOf),
                    countFrom = goal?.link?.countFrom ?: today,
                )
            }
        }
    }

    /** "Savings · Expense"; a category no longer listed still says what it was linked to. */
    private fun labelOf(id: String): String =
        choices.firstOrNull { it.id == id }?.let { "${it.label} · ${bookName(it.ledger)}" } ?: "A removed category"

    private fun save() {
        val form = _state.value
        if (form.saving) return
        val minor = MoneyFormat.parse(form.targetText, form.currency)
        val details = GoalDetails(
            name = form.name,
            target = Money(minor),
            targetDate = form.targetDate,
            link = form.categoryId?.let { GoalLink(it, form.countFrom) },
        )
        _state.update { it.copy(saving = true) }
        viewModelScope.launch {
            val result = editingId?.let { updateGoal(it, details) } ?: createGoal(details)
            _state.update {
                when (result) {
                    is GoalResult.Success -> it.copy(saving = false, done = true)
                    is GoalResult.Failure -> it.copy(saving = false, error = result.error.toMessage())
                }
            }
        }
    }

    private companion object {
        const val DEFAULT_CURRENCY = "INR"
    }
}
