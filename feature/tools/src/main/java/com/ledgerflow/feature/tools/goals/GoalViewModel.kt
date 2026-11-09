package com.ledgerflow.feature.tools.goals

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ledgerflow.core.common.time.Clock
import com.ledgerflow.core.common.time.LocalDates
import com.ledgerflow.core.designsystem.format.MoneyFormat
import com.ledgerflow.core.domain.goals.GoalError
import com.ledgerflow.core.domain.goals.GoalProgress
import com.ledgerflow.core.domain.goals.GoalResult
import com.ledgerflow.core.domain.ledger.LedgerRepository
import com.ledgerflow.core.domain.taxonomy.CategoryRepository
import com.ledgerflow.core.domain.usecase.AddGoalContributionUseCase
import com.ledgerflow.core.domain.usecase.DeleteGoalContributionUseCase
import com.ledgerflow.core.domain.usecase.DeleteGoalUseCase
import com.ledgerflow.core.domain.usecase.GoalsOverview
import com.ledgerflow.core.domain.usecase.ObserveGoalsUseCase
import com.ledgerflow.core.domain.usecase.UpdateGoalContributionUseCase
import com.ledgerflow.core.model.LedgerType
import com.ledgerflow.core.model.Money
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * One savings goal's page (V2-PLAN §4.1): what it holds, where it is heading,
 * its linked category's total, and the amounts added or taken out by hand.
 */
@HiltViewModel
public class GoalViewModel @Inject constructor(
    private val observeGoals: ObserveGoalsUseCase,
    categories: CategoryRepository,
    ledger: LedgerRepository,
    private val addContribution: AddGoalContributionUseCase,
    private val updateContribution: UpdateGoalContributionUseCase,
    private val deleteContribution: DeleteGoalContributionUseCase,
    private val deleteGoal: DeleteGoalUseCase,
    private val clock: Clock,
    savedStateHandle: SavedStateHandle,
) : ViewModel() {

    private val goalId: String = savedStateHandle.get<String>(GOAL_ID_ARG).orEmpty()

    /** What only this page knows: which sheet or dialog is open, and the last message. */
    private val local = MutableStateFlow(GoalUiState())

    private val choices: Flow<List<CategoryChoice>> = combine(
        categories.observeTree(LedgerType.DEBIT),
        categories.observeTree(LedgerType.CREDIT),
    ) { debit, credit -> categoryChoices(LedgerType.DEBIT, debit) + categoryChoices(LedgerType.CREDIT, credit) }

    public val state: StateFlow<GoalUiState> = combine(
        observeGoals(),
        choices,
        flow { emit(ledger.baseCurrency() ?: DEFAULT_CURRENCY) },
        local,
    ) { overview, choices, currency, local -> render(overview, choices, currency, local) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS), GoalUiState())

    /**
     * Split by what the event is about: the goal itself here, its hand-added
     * amounts in [onContributionEvent], one dialog's fields in [onEditorEvent].
     */
    public fun onEvent(event: GoalEvent) {
        when (event) {
            GoalEvent.OptionsClicked -> local.update { it.copy(optionsOpen = true) }
            GoalEvent.OptionsDismissed -> local.update { it.copy(optionsOpen = false) }
            GoalEvent.DeleteClicked -> local.update { it.copy(optionsOpen = false, confirmingDelete = true) }
            GoalEvent.DeleteCancelled -> local.update { it.copy(confirmingDelete = false) }
            GoalEvent.DeleteConfirmed -> removeGoal()
            GoalEvent.MessageShown -> local.update { it.copy(message = null) }
            GoalEvent.AddMoneyClicked, GoalEvent.TakeOutClicked, is GoalEvent.ContributionClicked,
            GoalEvent.EditContributionClicked, GoalEvent.DeleteContributionClicked,
            GoalEvent.ContributionOptionsDismissed, GoalEvent.EditorSaved, GoalEvent.EditorDismissed,
            is GoalEvent.AmountChanged, is GoalEvent.NoteChanged, GoalEvent.DateClicked,
            is GoalEvent.DatePicked, GoalEvent.DatePickerDismissed,
            -> onContributionEvent(event)
        }
    }

    private fun onContributionEvent(event: GoalEvent) {
        when (event) {
            GoalEvent.AddMoneyClicked -> openEditor(takingOut = false)
            GoalEvent.TakeOutClicked -> openEditor(takingOut = true)
            is GoalEvent.ContributionClicked -> local.update { it.copy(contributionOptions = event.id) }
            GoalEvent.EditContributionClicked -> editContribution()
            GoalEvent.DeleteContributionClicked -> removeContribution()
            GoalEvent.ContributionOptionsDismissed -> local.update { it.copy(contributionOptions = null) }
            GoalEvent.EditorSaved -> saveContribution()
            GoalEvent.EditorDismissed -> local.update { it.copy(editor = null) }
            is GoalEvent.AmountChanged, is GoalEvent.NoteChanged, GoalEvent.DateClicked,
            is GoalEvent.DatePicked, GoalEvent.DatePickerDismissed,
            -> onEditorEvent(event)
            // The goal's own events, handled by `onEvent`; listed so the `when`
            // stays exhaustive over the sealed type (CLAUDE.md §5).
            GoalEvent.OptionsClicked, GoalEvent.OptionsDismissed, GoalEvent.DeleteClicked,
            GoalEvent.DeleteCancelled, GoalEvent.DeleteConfirmed, GoalEvent.MessageShown,
            -> Unit
        }
    }

    /** Field-by-field edits of the open "Add money" or "Take out" dialog. */
    private fun onEditorEvent(event: GoalEvent) {
        local.update { current ->
            val editor = current.editor ?: return@update current
            current.copy(
                editor = when (event) {
                    is GoalEvent.AmountChanged -> editor.copy(amountText = event.text, error = null)
                    is GoalEvent.NoteChanged -> editor.copy(note = event.text)
                    GoalEvent.DateClicked -> editor.copy(showDatePicker = true)
                    is GoalEvent.DatePicked -> editor.copy(date = event.epochDay, showDatePicker = false)
                    GoalEvent.DatePickerDismissed -> editor.copy(showDatePicker = false)
                    // The page's own events, handled by `onEvent`; listed so the
                    // `when` stays exhaustive over the sealed type (CLAUDE.md §5).
                    GoalEvent.AddMoneyClicked, GoalEvent.TakeOutClicked, is GoalEvent.ContributionClicked,
                    GoalEvent.EditContributionClicked, GoalEvent.DeleteContributionClicked,
                    GoalEvent.ContributionOptionsDismissed, GoalEvent.OptionsClicked, GoalEvent.OptionsDismissed,
                    GoalEvent.DeleteClicked, GoalEvent.DeleteConfirmed, GoalEvent.DeleteCancelled,
                    GoalEvent.EditorSaved, GoalEvent.EditorDismissed, GoalEvent.MessageShown,
                    -> editor
                },
            )
        }
    }

    private fun openEditor(takingOut: Boolean) {
        local.update {
            it.copy(editor = ContributionEditorState(takingOut = takingOut, date = LocalDates.of(clock.nowMillis())))
        }
    }

    private fun editContribution() {
        val id = local.value.contributionOptions ?: return
        local.update { it.copy(contributionOptions = null) }
        viewModelScope.launch {
            val contribution = observeGoals().first().contributions.firstOrNull { it.id == id } ?: return@launch
            local.update {
                it.copy(
                    editor = ContributionEditorState(
                        editingId = id,
                        takingOut = contribution.amount.isNegative,
                        amountText = MoneyFormat.plain(contribution.amount.absolute.minor, state.value.currency),
                        date = contribution.localDate,
                        note = contribution.note.orEmpty(),
                    ),
                )
            }
        }
    }

    private fun saveContribution() {
        val editor = local.value.editor ?: return
        val minor = MoneyFormat.parse(editor.amountText, state.value.currency)
        if (minor <= 0L) {
            local.update { it.copy(editor = editor.copy(error = "Enter an amount above zero")) }
            return
        }
        val amount = if (editor.takingOut) Money(-minor) else Money(minor)
        val note = editor.note.ifBlank { null }
        viewModelScope.launch {
            val result = editor.editingId?.let { updateContribution(it, amount, editor.date, note) }
                ?: addContribution(goalId, amount, editor.date, note)
            when (result) {
                is GoalResult.Success -> local.update {
                    it.copy(editor = null, message = if (editor.takingOut) "Taken out" else "Added")
                }
                is GoalResult.Failure -> local.update {
                    it.copy(editor = it.editor?.copy(error = result.error.toMessage()))
                }
            }
        }
    }

    private fun removeContribution() {
        val id = local.value.contributionOptions ?: return
        local.update { it.copy(contributionOptions = null) }
        viewModelScope.launch {
            val message = when (deleteContribution(id)) {
                is GoalResult.Success -> "Deleted"
                is GoalResult.Failure -> "Could not delete"
            }
            local.update { it.copy(message = message) }
        }
    }

    private fun removeGoal() {
        local.update { it.copy(confirmingDelete = false) }
        viewModelScope.launch {
            // On success the goal leaves the observed list and the page closes
            // itself (`gone`); only a failure has anything to say.
            if (deleteGoal(goalId) is GoalResult.Failure) local.update { it.copy(message = "Could not delete") }
        }
    }

    private fun render(
        overview: GoalsOverview,
        choices: List<CategoryChoice>,
        currency: String,
        local: GoalUiState,
    ): GoalUiState {
        val progress = overview.goals.firstOrNull { it.goal.id == goalId }
            ?: return local.copy(loading = false, gone = true, currency = currency)
        return local.copy(
            loading = false,
            gone = false,
            name = progress.goal.name,
            currency = currency,
            saved = GoalWords.money(progress.saved, currency),
            ofTarget = ofTarget(progress, currency),
            fraction = progress.fraction(),
            warning = GoalWords.caption(progress, overview.today, currency).warning,
            facts = GoalWords.facts(progress, overview.today, currency).map { GoalLineUi(it.text, it.warning) },
            linked = linked(progress, choices, currency),
            contributions = overview.contributions.filter { it.goalId == goalId }.map { it.toRow(currency) },
        )
    }

    private fun ofTarget(progress: GoalProgress, currency: String): String {
        val target = "of ${GoalWords.money(progress.goal.target, currency)}"
        val left = if (progress.isReached) "reached" else "${GoalWords.money(progress.remaining, currency)} to go"
        return "$target · $left"
    }

    private fun linked(progress: GoalProgress, choices: List<CategoryChoice>, currency: String): LinkedCategoryUi? {
        val link = progress.goal.link ?: return null
        val choice = choices.firstOrNull { it.id == link.categoryId }
        return LinkedCategoryUi(
            heading = choice?.let { "From your ${it.label} category · ${bookName(it.ledger)} book" }
                ?: "From a category you removed",
            since = "Since ${GoalWords.date(link.countFrom)}",
            amount = GoalWords.money(progress.fromCategory, currency),
        )
    }

    public companion object {
        /** The route's argument: `Destination.Goal(goalId)`. */
        public const val GOAL_ID_ARG: String = "goalId"
        private const val DEFAULT_CURRENCY = "INR"
        private const val STOP_TIMEOUT_MILLIS = 5_000L
    }
}

internal fun GoalError.toMessage(): String = when (this) {
    GoalError.NameBlank -> "Give the goal a name"
    GoalError.TargetNotPositive -> "Enter a target above zero"
    GoalError.AmountZero -> "Enter an amount above zero"
    is GoalError.CategoryNotFound -> "That category no longer exists"
    is GoalError.NotFound -> "That goal no longer exists"
}
