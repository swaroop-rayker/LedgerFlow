package com.ledgerflow.feature.budget

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.ledgerflow.core.designsystem.component.LfScaffold
import com.ledgerflow.core.designsystem.theme.LfTheme
import com.ledgerflow.core.domain.analytics.Budget
import com.ledgerflow.core.domain.analytics.BudgetProgress
import com.ledgerflow.core.domain.analytics.BudgetRepository
import com.ledgerflow.core.domain.analytics.BudgetResult
import com.ledgerflow.core.domain.analytics.BudgetSettings
import com.ledgerflow.core.domain.analytics.NewBudget
import com.ledgerflow.core.domain.usecase.CreateBudgetUseCase
import com.ledgerflow.core.domain.usecase.DeleteBudgetUseCase
import com.ledgerflow.core.domain.usecase.GetAnalyticsSnapshotUseCase
import com.ledgerflow.core.domain.usecase.UpdateBudgetUseCase
import com.ledgerflow.core.model.BudgetPeriod
import com.ledgerflow.core.model.Money
import com.ledgerflow.core.testing.analytics.FakeAnalyticsRepository
import com.ledgerflow.core.testing.ledger.FakeLedgerRepository
import com.ledgerflow.core.testing.taxonomy.FakeCategoryRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * BUG69: a budget's Delete deleted it on one tap. Now it asks, in a sheet that
 * names the budget, and nothing is deleted until the sheet's own Delete.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w360dp-h800dp")
class Bug69_BudgetDeleteAsksFirstTest {

    @get:Rule
    val rule = createComposeRule()

    private val dispatcher = StandardTestDispatcher()
    private val budgets = RecordingBudgets()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun delete_asksFirst_andDeletesNothing() {
        val vm = viewModel()

        vm.onEvent(BudgetEvent.DeleteClicked(ID))
        dispatcher.scheduler.advanceUntilIdle()

        assertThat(vm.state.value.confirmingDeleteId).isEqualTo(ID)
        assertThat(budgets.deleted).isEmpty()
    }

    @Test
    fun cancelling_deletesNothing() {
        val vm = viewModel()

        vm.onEvent(BudgetEvent.DeleteClicked(ID))
        vm.onEvent(BudgetEvent.DeleteCancelled)
        dispatcher.scheduler.advanceUntilIdle()

        assertThat(vm.state.value.confirmingDeleteId).isNull()
        assertThat(budgets.deleted).isEmpty()
    }

    @Test
    fun confirming_deletesThatBudgetOnce() {
        val vm = viewModel()

        vm.onEvent(BudgetEvent.DeleteClicked(ID))
        vm.onEvent(BudgetEvent.DeleteConfirmed)
        vm.onEvent(BudgetEvent.DeleteConfirmed) // a second tap as the sheet closes
        dispatcher.scheduler.advanceUntilIdle()

        assertThat(budgets.deleted).containsExactly(ID)
        assertThat(vm.state.value.confirmingDeleteId).isNull()
    }

    @Test
    fun theSheet_namesTheBudget_andKeepsItsAnswersApart() {
        val events = mutableListOf<BudgetEvent>()
        rule.setContent {
            LfTheme {
                LfScaffold {
                    BudgetScreen(
                        state = BudgetUiState(
                            isLoading = false,
                            budgets = listOf(groceries),
                            confirmingDeleteId = ID,
                        ),
                        onEvent = { events += it },
                        onBack = {},
                    )
                }
            }
        }
        rule.onNodeWithText("Delete the Groceries budget?").assertExists()
        rule.onNodeWithText("Cancel").performClick()
        assertThat(events).containsExactly(BudgetEvent.DeleteCancelled)
    }

    private fun viewModel() = BudgetViewModel(
        getSnapshot = GetAnalyticsSnapshotUseCase(FakeAnalyticsRepository()),
        createBudget = CreateBudgetUseCase(budgets),
        updateBudget = UpdateBudgetUseCase(budgets),
        deleteBudget = DeleteBudgetUseCase(budgets),
        categories = FakeCategoryRepository(),
        ledgerRepository = FakeLedgerRepository(),
        clock = { NOW },
    )

    private val groceries = BudgetProgress(
        budget = Budget(
            id = ID,
            categoryId = ID,
            subcategoryId = null,
            period = BudgetPeriod.MONTHLY,
            amount = Money(10_000_00),
            startDate = START,
            rolloverEnabled = false,
            alertThresholds = listOf(80, 100),
        ),
        categoryName = "Groceries",
        categoryColorArgb = 0xFF3E6AD6.toInt(),
        spent = Money(7_200_00),
        periodStart = START,
        periodEnd = START + 29,
        daysElapsed = 18,
        projectedSpend = Money(12_000_00),
    )

    /** Records deletes; every other write is out of this test's scope. */
    private class RecordingBudgets : BudgetRepository {
        val deleted = mutableListOf<String>()
        override fun observeAll(): Flow<List<Budget>> = flowOf(emptyList())
        override suspend fun create(request: NewBudget): BudgetResult<Budget> = error("not used")
        override suspend fun update(id: String, settings: BudgetSettings): BudgetResult<Unit> = error("not used")
        override suspend fun delete(id: String): BudgetResult<Unit> {
            deleted += id
            return BudgetResult.Success(Unit)
        }
        override suspend fun recordAlert(id: String, threshold: Int, periodStart: Int) = Unit
    }

    private companion object {
        const val ID = "g"
        const val START = 20_667
        const val NOW = 1_785_000_000_000L
    }
}
