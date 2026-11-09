package com.ledgerflow.feature.tools.goals

import androidx.lifecycle.SavedStateHandle
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.ledgerflow.core.common.time.Clock
import com.ledgerflow.core.domain.goals.GoalContribution
import com.ledgerflow.core.domain.goals.GoalDetails
import com.ledgerflow.core.domain.usecase.AddGoalContributionUseCase
import com.ledgerflow.core.domain.usecase.DeleteGoalContributionUseCase
import com.ledgerflow.core.domain.usecase.DeleteGoalUseCase
import com.ledgerflow.core.domain.usecase.ObserveGoalsUseCase
import com.ledgerflow.core.domain.usecase.UpdateGoalContributionUseCase
import com.ledgerflow.core.model.Money
import com.ledgerflow.core.testing.goals.FakeGoalRepository
import com.ledgerflow.core.testing.ledger.FakeLedgerRepository
import com.ledgerflow.core.testing.taxonomy.FakeCategoryRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * "Add money" and "Take out" (V2-PLAN §4.1). The sign is the whole difference
 * between them, so it is pinned: taken out is stored negative, an edit of it
 * opens as "Take out" with the amount the user typed, and nothing is stored
 * for zero.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class GoalViewModelTest {

    private val dispatcher = UnconfinedTestDispatcher()
    private val goals = FakeGoalRepository()
    private val clock = Clock { NOW }

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() = Dispatchers.resetMain()

    private suspend fun viewModel(): GoalViewModel {
        val id = (goals.create(GoalDetails("Emergency fund", Money(1_00_000_00), null, null))
            as com.ledgerflow.core.domain.goals.GoalResult.Success).value.id
        return GoalViewModel(
            observeGoals = ObserveGoalsUseCase(goals, clock),
            categories = FakeCategoryRepository(),
            ledger = FakeLedgerRepository(),
            addContribution = AddGoalContributionUseCase(goals),
            updateContribution = UpdateGoalContributionUseCase(goals),
            deleteContribution = DeleteGoalContributionUseCase(goals),
            deleteGoal = DeleteGoalUseCase(goals),
            clock = clock,
            savedStateHandle = SavedStateHandle(mapOf(GoalViewModel.GOAL_ID_ARG to id)),
        )
    }

    @Test
    fun takeOut_isStoredNegative() = runTest(dispatcher) {
        val vm = viewModel()
        backgroundScope.launch { vm.state.collect {} }

        vm.onEvent(GoalEvent.TakeOutClicked)
        vm.onEvent(GoalEvent.AmountChanged("2,000"))
        vm.onEvent(GoalEvent.EditorSaved)

        assertThat(goals.contributions.value.single().amount).isEqualTo(Money(-2_000_00))
        assertThat(vm.state.value.editor).isNull()
        assertThat(vm.state.value.contributions.single().takenOut).isTrue()
    }

    @Test
    fun addMoney_isStoredPositive() = runTest(dispatcher) {
        val vm = viewModel()
        backgroundScope.launch { vm.state.collect {} }

        vm.onEvent(GoalEvent.AddMoneyClicked)
        vm.onEvent(GoalEvent.AmountChanged("15,000"))
        vm.onEvent(GoalEvent.NoteChanged("Bonus"))
        vm.onEvent(GoalEvent.EditorSaved)

        val stored: GoalContribution = goals.contributions.value.single()
        assertThat(stored.amount).isEqualTo(Money(15_000_00))
        assertThat(stored.note).isEqualTo("Bonus")
    }

    @Test
    fun editingMoneyTakenOut_opensAsTakeOut_withTheAmountAsTyped() = runTest(dispatcher) {
        val vm = viewModel()
        backgroundScope.launch { vm.state.collect {} }
        vm.onEvent(GoalEvent.TakeOutClicked)
        vm.onEvent(GoalEvent.AmountChanged("2,000"))
        vm.onEvent(GoalEvent.EditorSaved)

        vm.onEvent(GoalEvent.ContributionClicked(goals.contributions.value.single().id))
        vm.onEvent(GoalEvent.EditContributionClicked)

        val editor = requireNotNull(vm.state.value.editor) { "the edit dialog is open" }
        assertThat(editor.takingOut).isTrue()
        assertThat(editor.amountText).isEqualTo("2,000.00")
        assertThat(editor.title).isEqualTo("Edit money taken out")
    }

    @Test
    fun aZeroAmount_isRefused_andNothingIsStored() = runTest(dispatcher) {
        val vm = viewModel()
        backgroundScope.launch { vm.state.collect {} }

        vm.onEvent(GoalEvent.AddMoneyClicked)
        vm.onEvent(GoalEvent.EditorSaved)

        assertThat(goals.contributions.value).isEmpty()
        assertThat(vm.state.value.editor?.error).isEqualTo("Enter an amount above zero")
    }

    private companion object {
        /** 9 Nov 2026, midday UTC. */
        const val NOW = 1_794_225_600_000L
    }
}
