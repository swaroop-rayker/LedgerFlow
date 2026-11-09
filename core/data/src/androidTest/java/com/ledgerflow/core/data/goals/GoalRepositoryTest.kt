package com.ledgerflow.core.data.goals

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.ledgerflow.core.data.ledger.LedgerTestVault
import com.ledgerflow.core.domain.goals.GoalDetails
import com.ledgerflow.core.domain.goals.GoalError
import com.ledgerflow.core.domain.goals.GoalLink
import com.ledgerflow.core.domain.goals.GoalResult
import com.ledgerflow.core.domain.ledger.ApprovalRequest
import com.ledgerflow.core.domain.ledger.LedgerResult
import com.ledgerflow.core.domain.taxonomy.NewCategory
import com.ledgerflow.core.domain.taxonomy.TaxonomyResult
import com.ledgerflow.core.model.Category
import com.ledgerflow.core.model.EntryAssignment
import com.ledgerflow.core.model.LedgerType
import com.ledgerflow.core.model.Money
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Savings goals over the real database (V2-PLAN §4.1, schema v15).
 *
 * The rules worth a device test are the ones SQL carries: a linked category's
 * total is read from its own book only (Law 2) and from `countFrom` on, a
 * subcategory counts only itself, a goal follows its category's entries when
 * the category is deleted, and deleting a goal takes its contributions.
 */
@RunWith(AndroidJUnit4::class)
class GoalRepositoryTest {

    private val vault = LedgerTestVault("lf_goal_repository_test")
    private lateinit var goals: DefaultGoalRepository

    private lateinit var savings: Category
    private lateinit var deposits: Category
    private lateinit var investments: Category
    private lateinit var interest: Category

    private val day = LocalDate.of(2026, 9, 1).toEpochDay().toInt()

    @Before
    fun setUp() = runBlocking<Unit> {
        vault.open()
        goals = DefaultGoalRepository(vault.session, vault.ids, vault.clock, Dispatchers.IO)
        savings = vault.categories.create(NewCategory(LedgerType.DEBIT, "Savings")).success()
        deposits = vault.categories.create(NewCategory(LedgerType.DEBIT, "Deposits", parentId = savings.id)).success()
        investments = vault.categories.create(NewCategory(LedgerType.DEBIT, "Investments")).success()
        interest = vault.categories.create(NewCategory(LedgerType.CREDIT, "Interest")).success()
    }

    @After
    fun tearDown() = vault.close()

    @Test
    fun aLinkedCategory_countsItsBook_fromCountFrom_withItsSubcategories() = runBlocking<Unit> {
        val goal = goals.create(details(link = GoalLink(savings.id, countFrom = day))).success()
        approve(LedgerType.DEBIT, 5_000_00, savings.id, null, day - 1) // before countFrom
        approve(LedgerType.DEBIT, 2_000_00, savings.id, null, day)
        approve(LedgerType.DEBIT, 3_000_00, savings.id, deposits.id, day + 1)
        approve(LedgerType.CREDIT, 9_000_00, interest.id, null, day + 1) // the other book

        val debit = goals.observeLinkedTotals(LedgerType.DEBIT, Int.MIN_VALUE, day + 10).first()
        val credit = goals.observeLinkedTotals(LedgerType.CREDIT, Int.MIN_VALUE, day + 10).first()

        assertThat(debit[goal.id]).isEqualTo(Money(5_000_00))
        assertThat(credit).doesNotContainKey(goal.id)
    }

    @Test
    fun aLinkedSubcategory_countsOnlyItself() = runBlocking<Unit> {
        val goal = goals.create(details(link = GoalLink(deposits.id, countFrom = day))).success()
        approve(LedgerType.DEBIT, 2_000_00, savings.id, null, day)
        approve(LedgerType.DEBIT, 3_000_00, savings.id, deposits.id, day)

        val debit = goals.observeLinkedTotals(LedgerType.DEBIT, Int.MIN_VALUE, day + 10).first()

        assertThat(debit[goal.id]).isEqualTo(Money(3_000_00))
    }

    /** The pace's window: from the later of `countFrom` and the window's start. */
    @Test
    fun theWindowedTotal_startsAtTheLaterOfCountFromAndTheWindow() = runBlocking<Unit> {
        val goal = goals.create(details(link = GoalLink(savings.id, countFrom = day))).success()
        approve(LedgerType.DEBIT, 2_000_00, savings.id, null, day)
        approve(LedgerType.DEBIT, 3_000_00, savings.id, null, day + 20)

        val windowed = goals.observeLinkedTotals(LedgerType.DEBIT, day + 10, day + 30).first()

        assertThat(windowed[goal.id]).isEqualTo(Money(3_000_00))
    }

    @Test
    fun deletingACategoryWithReassignment_movesItsGoals() = runBlocking<Unit> {
        val goal = goals.create(details(link = GoalLink(savings.id, countFrom = day))).success()
        approve(LedgerType.DEBIT, 2_000_00, savings.id, null, day)

        vault.categories.delete(savings.id, reassignTo = investments.id).success()

        val moved = goals.observeGoals().first().single()
        assertThat(moved.link).isEqualTo(GoalLink(investments.id, countFrom = day))
        val debit = goals.observeLinkedTotals(LedgerType.DEBIT, Int.MIN_VALUE, day + 10).first()
        assertThat(debit[goal.id]).isEqualTo(Money(2_000_00))
    }

    @Test
    fun deletingAGoal_takesItsContributions_andNoEntry() = runBlocking<Unit> {
        val goal = goals.create(details(link = GoalLink(savings.id, countFrom = day))).success()
        approve(LedgerType.DEBIT, 2_000_00, savings.id, null, day)
        goals.addContribution(goal.id, Money(1_500_00), day, "Bonus").success()
        goals.addContribution(goal.id, Money(-200_00), day + 1, null).success()

        goals.delete(goal.id).success()

        assertThat(goals.observeGoals().first()).isEmpty()
        assertThat(goals.observeContributions().first()).isEmpty()
        assertThat(vault.database.ledgerEntryDao().allForLedger(LedgerType.DEBIT)).hasSize(1)
    }

    @Test
    fun aContributionIsSigned_andZeroIsRefused() = runBlocking<Unit> {
        val goal = goals.create(details()).success()

        goals.addContribution(goal.id, Money(-200_00), day, "  ").success()
        val zero = goals.addContribution(goal.id, Money.ZERO, day, null)

        val stored = goals.observeContributions().first().single()
        assertThat(stored.amount).isEqualTo(Money(-200_00))
        assertThat(stored.note).isNull()
        assertThat((zero as GoalResult.Failure).error).isEqualTo(GoalError.AmountZero)
    }

    @Test
    fun aGoalNeedsANameAPositiveTargetAndALiveCategory() = runBlocking<Unit> {
        val blank = goals.create(details(name = "  "))
        val zero = goals.create(details(target = 0L))
        vault.categories.delete(investments.id, reassignTo = null).success()
        val hidden = goals.create(details(link = GoalLink(investments.id, countFrom = day)))

        assertThat((blank as GoalResult.Failure).error).isEqualTo(GoalError.NameBlank)
        assertThat((zero as GoalResult.Failure).error).isEqualTo(GoalError.TargetNotPositive)
        assertThat((hidden as GoalResult.Failure).error).isEqualTo(GoalError.CategoryNotFound(investments.id))
        assertThat(goals.observeGoals().first()).isEmpty()
    }

    /** An edit may keep a link whose category was hidden since, so the goal can still be renamed. */
    @Test
    fun anEditMayKeepALinkWhoseCategoryWasHidden() = runBlocking<Unit> {
        val goal = goals.create(details(link = GoalLink(investments.id, countFrom = day))).success()
        vault.categories.delete(investments.id, reassignTo = null).success()

        val renamed = goals.update(goal.id, details(name = "Rainy day", link = GoalLink(investments.id, day)))

        assertThat(renamed).isInstanceOf(GoalResult.Success::class.java)
        assertThat(goals.observeGoals().first().single().name).isEqualTo("Rainy day")
    }

    private fun details(name: String = "Emergency fund", target: Long = 1_00_000_00, link: GoalLink? = null) =
        GoalDetails(name = name, target = Money(target), targetDate = null, link = link)

    private suspend fun approve(book: LedgerType, amount: Long, categoryId: String, subcategoryId: String?, on: Int) {
        val occurredAt = LocalDate.ofEpochDay(on.toLong()).atTime(12, 0)
            .atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        val result = vault.ledger.approve(
            ApprovalRequest(
                ledger = book,
                amount = Money(amount),
                occurredAt = occurredAt,
                assignment = EntryAssignment(categoryId = categoryId, subcategoryId = subcategoryId),
            ),
        )
        assertThat(result).isInstanceOf(LedgerResult.Success::class.java)
    }

    private fun <T> TaxonomyResult<T>.success(): T {
        assertThat(this).isInstanceOf(TaxonomyResult.Success::class.java)
        return (this as TaxonomyResult.Success).value
    }

    private fun <T> GoalResult<T>.success(): T {
        assertThat(this).isInstanceOf(GoalResult.Success::class.java)
        return (this as GoalResult.Success).value
    }
}
