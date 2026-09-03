package com.ledgerflow.core.data.analytics

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.ledgerflow.core.data.ledger.LedgerTestVault
import com.ledgerflow.core.domain.analytics.BudgetError
import com.ledgerflow.core.domain.analytics.BudgetResult
import com.ledgerflow.core.domain.analytics.NewBudget
import com.ledgerflow.core.domain.taxonomy.NewCategory
import com.ledgerflow.core.domain.taxonomy.TaxonomyResult
import com.ledgerflow.core.model.BudgetPeriod
import com.ledgerflow.core.model.Category
import com.ledgerflow.core.model.LedgerType
import com.ledgerflow.core.model.Money
import java.time.LocalDate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Budget CRUD (`SPEC.md` §5.7).
 *
 * The rules worth testing here are the ones the *schema does not enforce*.
 * §6.1 gives `budget` no unique index and no foreign key, so "one budget per
 * category" and "the category must exist and be visible" live in the repository
 * — and a rule that lives in code is a rule that needs a test.
 */
@RunWith(AndroidJUnit4::class)
class BudgetCrudTest {

    private val vault = LedgerTestVault("lf_budget_crud_test")
    private lateinit var budgets: DefaultBudgetRepository

    private lateinit var groceries: Category
    private lateinit var transport: Category

    private val today: Int = LocalDate.of(2026, 6, 15).toEpochDay().toInt()

    @Before
    fun setUp() = runBlocking<Unit> {
        vault.open()
        budgets = DefaultBudgetRepository(vault.session, vault.ids, vault.clock, Dispatchers.IO)
        groceries = vault.categories.create(NewCategory(LedgerType.DEBIT, "Groceries")).success()
        transport = vault.categories.create(NewCategory(LedgerType.DEBIT, "Transport")).success()
    }

    @After
    fun tearDown() = vault.close()

    @Test
    fun aBudgetIsCreatedAndObserved() = runBlocking<Unit> {
        val created = budgets.create(request(groceries.id, 1_200_000L)).success()

        assertThat(created.categoryId).isEqualTo(groceries.id)
        assertThat(created.amount.minor).isEqualTo(1_200_000L)
        // §5.7's default, parsed back out of the stored "80,100".
        assertThat(created.alertThresholds).containsExactly(80, 100).inOrder()

        assertThat(budgets.observeAll().first().map { it.id }).containsExactly(created.id)
    }

    /**
     * **One live budget per category, enforced in code because the schema
     * cannot.**
     *
     * Two budgets on one category would each show a different "remaining", and
     * the app would have no basis for choosing which to alert on. §6.1 has no
     * unique index here, so without this rule the second insert simply succeeds.
     */
    @Test
    fun aSecondBudgetOnTheSameCategoryIsRefused() = runBlocking<Unit> {
        budgets.create(request(groceries.id, 1_200_000L)).success()

        val second = budgets.create(request(groceries.id, 500_000L))

        assertThat(second).isInstanceOf(BudgetResult.Failure::class.java)
        assertThat((second as BudgetResult.Failure).error)
            .isInstanceOf(BudgetError.AlreadyBudgeted::class.java)
        assertThat(budgets.observeAll().first()).hasSize(1)
    }

    /** A different category is fine — the rule is per category, not global. */
    @Test
    fun asecondBudgetOnADifferentCategoryIsAllowed() = runBlocking<Unit> {
        budgets.create(request(groceries.id, 1_200_000L)).success()
        budgets.create(request(transport.id, 300_000L)).success()

        assertThat(budgets.observeAll().first()).hasSize(2)
    }

    /**
     * **Deleting frees the category again**, which is the other half of the
     * uniqueness rule: the check is over *live* rows, so a binned budget must
     * not block a replacement.
     */
    @Test
    fun deletingABudgetLetsTheCategoryBeBudgetedAgain() = runBlocking<Unit> {
        val first = budgets.create(request(groceries.id, 1_200_000L)).success()
        budgets.delete(first.id).success()

        assertThat(budgets.observeAll().first()).isEmpty()
        assertThat(budgets.create(request(groceries.id, 900_000L)))
            .isInstanceOf(BudgetResult.Success::class.java)
    }

    /**
     * Delete is a *soft* delete: the row survives with a timestamp.
     *
     * ADR-0017 puts the bin in the backup and ADR-0006 says nothing can
     * reconstruct a budget, so destroying the row here would be the one way to
     * lose user intent permanently from a screen with no confirmation dialog.
     */
    @Test
    fun deleteIsSoft_soTheRowSurvivesForTheBackup() = runBlocking<Unit> {
        val budget = budgets.create(request(groceries.id, 1_200_000L)).success()

        budgets.delete(budget.id).success()

        val all = vault.session.requireDatabase().budgetDao().all()
        assertThat(all).hasSize(1)
        assertThat(all.single().deletedAt).isNotNull()
    }

    @Test
    fun aZeroBudgetIsRefused() = runBlocking<Unit> {
        val result = budgets.create(request(groceries.id, 0L))

        assertThat((result as BudgetResult.Failure).error)
            .isEqualTo(BudgetError.AmountNotPositive)
    }

    @Test
    fun aBudgetOnAMissingCategoryIsRefused() = runBlocking<Unit> {
        val result = budgets.create(request("no-such-category", 100_000L))

        assertThat((result as BudgetResult.Failure).error)
            .isInstanceOf(BudgetError.CategoryNotFound::class.java)
    }

    /**
     * A hidden category cannot take a *new* budget.
     *
     * Existing budgets on a category the user later hides are left alone — the
     * spending happened and the history is real (`DefaultAnalyticsRepository`
     * still resolves the name). What is refused is creating a budget nobody can
     * file anything against.
     */
    @Test
    fun aBudgetOnAHiddenCategoryIsRefused() = runBlocking<Unit> {
        vault.categories.delete(transport.id, reassignTo = null).success()

        val result = budgets.create(request(transport.id, 100_000L))

        assertThat((result as BudgetResult.Failure).error)
            .isInstanceOf(BudgetError.CategoryHidden::class.java)
    }

    @Test
    fun theAmountCanBeEdited_butNotToZero() = runBlocking<Unit> {
        val budget = budgets.create(request(groceries.id, 1_200_000L)).success()

        budgets.updateAmount(budget.id, Money(1_500_000L)).success()
        assertThat(budgets.observeAll().first().single().amount.minor).isEqualTo(1_500_000L)

        val zeroed = budgets.updateAmount(budget.id, Money(0L))
        assertThat((zeroed as BudgetResult.Failure).error)
            .isEqualTo(BudgetError.AmountNotPositive)
        assertThat(budgets.observeAll().first().single().amount.minor).isEqualTo(1_500_000L)
    }

    private fun request(categoryId: String, amount: Long) = NewBudget(
        categoryId = categoryId,
        period = BudgetPeriod.MONTHLY,
        amount = Money(amount),
        startDate = today,
    )

    private fun <T> BudgetResult<T>.success(): T {
        assertThat(this).isInstanceOf(BudgetResult.Success::class.java)
        return (this as BudgetResult.Success).value
    }

    private fun <T> TaxonomyResult<T>.success(): T {
        assertThat(this).isInstanceOf(TaxonomyResult.Success::class.java)
        return (this as TaxonomyResult.Success).value
    }
}
