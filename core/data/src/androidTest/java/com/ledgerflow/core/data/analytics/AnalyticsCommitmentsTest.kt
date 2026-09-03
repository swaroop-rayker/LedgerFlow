package com.ledgerflow.core.data.analytics

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.ledgerflow.core.data.ledger.LedgerTestVault
import com.ledgerflow.core.database.entity.BudgetEntity
import com.ledgerflow.core.domain.analytics.AnalyticsRange
import com.ledgerflow.core.domain.analytics.AnalyticsWindow
import com.ledgerflow.core.domain.ledger.ApprovalRequest
import com.ledgerflow.core.domain.ledger.LedgerResult
import com.ledgerflow.core.domain.taxonomy.NewCategory
import com.ledgerflow.core.domain.taxonomy.TaxonomyResult
import com.ledgerflow.core.model.BudgetPeriod
import com.ledgerflow.core.model.Category
import com.ledgerflow.core.model.EntryAssignment
import com.ledgerflow.core.model.LedgerType
import com.ledgerflow.core.model.Merchant
import com.ledgerflow.core.model.Money
import java.time.LocalDate
import java.time.ZoneOffset
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * A6, A7, A8 and A10 against a seeded vault (`SPEC.md` §5.6, §5.7).
 *
 * The detection *algorithm* is unit-tested on the JVM
 * (`RecurringDetectionTest`); what these assert is the part only a database can
 * be wrong about — that the occurrences reaching it are the right rows, that
 * budgets read the period they belong to rather than the analytics window, and
 * that Law 2 holds across all of it.
 */
@RunWith(AndroidJUnit4::class)
class AnalyticsCommitmentsTest {

    private val vault = LedgerTestVault("lf_commitments_test")
    private lateinit var analytics: DefaultAnalyticsRepository

    private lateinit var groceries: Category
    private lateinit var streaming: Merchant

    /** Fixed, so a run near midnight cannot straddle a period boundary. */
    private val today: Int = LocalDate.of(2026, 6, 15).toEpochDay().toInt()

    @Before
    fun setUp() = runBlocking<Unit> {
        vault.open()
        analytics = DefaultAnalyticsRepository(vault.session, Dispatchers.IO)
        groceries = vault.categories.create(NewCategory(LedgerType.DEBIT, "Groceries")).success()
        streaming = vault.merchants.createOrGet("Streaming Co").success()
    }

    @After
    fun tearDown() = vault.close()

    // ── A6 ─────────────────────────────────────────────────────────────────

    @Test
    fun theHeatmap_hasOneEntryPerDayThatHadSpending() = runBlocking<Unit> {
        approve(45_000L, today, groceries.id)
        approve(12_000L, today, groceries.id)
        approve(30_000L, today - 3, groceries.id)

        val snapshot = analytics.snapshot(LedgerType.DEBIT, window(), comparePrevious = false)

        // Two days, not three entries -- the grid is per day.
        assertThat(snapshot.days).hasSize(2)
        val byDate = snapshot.days.associateBy { it.localDate }
        assertThat(byDate.getValue(today).amount.minor).isEqualTo(57_000L)
        assertThat(byDate.getValue(today).transactionCount).isEqualTo(2)
        assertThat(byDate.getValue(today - 3).amount.minor).isEqualTo(30_000L)
    }

    // ── A7 ─────────────────────────────────────────────────────────────────

    /**
     * **The budget reads its own period, not the analytics window.**
     *
     * The window here is a single day; the budget's month is 30. Reading the
     * window would report almost nothing spent and a wildly wrong projection,
     * which is the mistake this asserts against.
     */
    @Test
    fun budgetProgress_readsTheBudgetPeriodNotTheSelectedWindow() = runBlocking<Unit> {
        seedBudget(amountMinor = 1_200_000L, startDate = today - 9)
        approve(200_000L, today - 5, groceries.id)
        approve(220_000L, today, groceries.id)

        val dayWindow = AnalyticsWindow.endingOn(today, AnalyticsRange.DAY)
        val snapshot = analytics.snapshot(LedgerType.DEBIT, dayWindow, comparePrevious = false)

        val progress = snapshot.budgets.single()
        assertThat(progress.spent.minor).isEqualTo(420_000L)
        assertThat(progress.categoryName).isEqualTo("Groceries")
        // Day 10 of 30, ₹4,200 spent -> ₹12,600 projected against a ₹12,000
        // budget. Worked out by hand: 420_000 * 30 / 10.
        assertThat(progress.daysElapsed).isEqualTo(10)
        assertThat(progress.projectedSpend.minor).isEqualTo(1_260_000L)
        assertThat(progress.onCourseToOverrun).isTrue()
    }

    @Test
    fun budgetThresholds_reportTheHighestCrossed() = runBlocking<Unit> {
        seedBudget(amountMinor = 100_000L, startDate = today)
        approve(85_000L, today, groceries.id)

        val progress = analytics
            .snapshot(LedgerType.DEBIT, window(), comparePrevious = false)
            .budgets.single()

        // 85% of the budget: past the 80 threshold, not the 100.
        assertThat(progress.crossedThreshold()).isEqualTo(80)
    }

    /** §5.7: budgets are debit-only, so the credit screen has none. */
    @Test
    fun theCreditScreenHasNoBudgets() = runBlocking<Unit> {
        seedBudget(amountMinor = 100_000L, startDate = today)

        val credit = analytics.snapshot(LedgerType.CREDIT, window(), comparePrevious = false)

        assertThat(credit.budgets).isEmpty()
    }

    // ── A8 and A10 ─────────────────────────────────────────────────────────

    @Test
    fun aMonthlyChargeToOneMerchant_isDetected() = runBlocking<Unit> {
        listOf(90, 60, 30, 0).forEach { ago ->
            approve(64_900L, today - ago, groceries.id, merchantId = streaming.id)
        }

        val snapshot = analytics.snapshot(LedgerType.DEBIT, window(), comparePrevious = false)

        val detected = snapshot.recurring.single()
        assertThat(detected.name).isEqualTo("Streaming Co")
        assertThat(detected.intervalDays).isEqualTo(30)
        assertThat(detected.typicalAmount.minor).isEqualTo(64_900L)
        assertThat(detected.nextExpected).isEqualTo(today + 30)
    }

    /**
     * Detection looks back further than the selected window.
     *
     * A monthly subscription needs three months of dates to be visible; if it
     * were detected only inside a one-month view, the feature would find
     * nothing on exactly the range people look at most and would appear broken.
     */
    @Test
    fun detectionSeesHistoryOlderThanTheWindow() = runBlocking<Unit> {
        listOf(90, 60, 30, 0).forEach { ago ->
            approve(64_900L, today - ago, groceries.id, merchantId = streaming.id)
        }

        val dayWindow = AnalyticsWindow.endingOn(today, AnalyticsRange.DAY)
        val snapshot = analytics.snapshot(LedgerType.DEBIT, dayWindow, comparePrevious = false)

        assertThat(snapshot.recurring).hasSize(1)
    }

    @Test
    fun entriesWithNoMerchant_neverBecomeASubscription() = runBlocking<Unit> {
        // Perfectly regular, but unattributed. Clustering the dates of
        // everything without a merchant would manufacture a subscription out of
        // unrelated spending.
        listOf(90, 60, 30, 0).forEach { ago ->
            approve(20_000L, today - ago, groceries.id, merchantId = null)
        }

        val snapshot = analytics.snapshot(LedgerType.DEBIT, window(), comparePrevious = false)

        assertThat(snapshot.recurring).isEmpty()
    }

    @Test
    fun theRunway_sumsWhatIsExpectedBeforeTheWindowEnds() = runBlocking<Unit> {
        listOf(90, 60, 30, 0).forEach { ago ->
            approve(64_900L, today - ago, groceries.id, merchantId = streaming.id)
        }

        val snapshot = analytics.snapshot(LedgerType.DEBIT, window(), comparePrevious = false)

        // Next expected is today + 30, inside a 30-day forward horizon.
        assertThat(snapshot.runway).hasSize(1)
        assertThat(snapshot.runwayTotal.minor).isEqualTo(64_900L)
    }

    @Test
    fun aBinnedEntry_isInvisibleToDetection() = runBlocking<Unit> {
        val ids = listOf(90, 60, 30, 0).map { ago ->
            approveReturningId(64_900L, today - ago, groceries.id, merchantId = streaming.id)
        }
        // Bin one, leaving three occurrences with a 60-day gap in the middle --
        // gaps of 60, 30, which is irregular enough to fall out.
        vault.ledger.softDeleteEntry(LedgerType.DEBIT, ids[1])

        val snapshot = analytics.snapshot(LedgerType.DEBIT, window(), comparePrevious = false)

        assertThat(snapshot.recurring).isEmpty()
    }

    // ── Helpers ────────────────────────────────────────────────────────────

    private fun window() = AnalyticsWindow.endingOn(today, AnalyticsRange.MONTH)

    private suspend fun seedBudget(amountMinor: Long, startDate: Int) {
        vault.session.requireDatabase().budgetDao().insert(
            BudgetEntity(
                id = "budget-groceries",
                categoryId = groceries.id,
                subcategoryId = null,
                period = BudgetPeriod.MONTHLY,
                amountMinor = Money(amountMinor),
                startDate = startDate,
            ),
        )
    }

    private suspend fun approve(
        amount: Long,
        localDate: Int,
        categoryId: String?,
        merchantId: String? = null,
    ) {
        approveReturningId(amount, localDate, categoryId, merchantId)
    }

    private suspend fun approveReturningId(
        amount: Long,
        localDate: Int,
        categoryId: String?,
        merchantId: String? = null,
    ): String {
        val occurredAt = LocalDate.ofEpochDay(localDate.toLong())
            .atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli()
        val result = vault.ledger.approve(
            ApprovalRequest(
                ledger = LedgerType.DEBIT,
                amount = Money(amount),
                occurredAt = occurredAt,
                assignment = EntryAssignment(categoryId = categoryId, merchantId = merchantId),
            ),
        )
        assertThat(result).isInstanceOf(LedgerResult.Success::class.java)
        return (result as LedgerResult.Success).value.id
    }

    private fun <T> TaxonomyResult<T>.success(): T {
        assertThat(this).isInstanceOf(TaxonomyResult.Success::class.java)
        return (this as TaxonomyResult.Success).value
    }
}
