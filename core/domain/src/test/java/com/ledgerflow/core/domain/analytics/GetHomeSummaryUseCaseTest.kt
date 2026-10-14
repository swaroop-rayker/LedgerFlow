package com.ledgerflow.core.domain.analytics

import com.google.common.truth.Truth.assertThat
import com.ledgerflow.core.domain.usecase.GetHomeSummaryUseCase
import com.ledgerflow.core.model.BudgetPeriod
import com.ledgerflow.core.model.LedgerType
import com.ledgerflow.core.model.Money
import java.time.LocalDate
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * Home's figures (V2-PLAN BUG-C): which book each one is read from, and the
 * debit-side selections made over the snapshot.
 *
 * **The fake answers a different amount for every (book, window) pair** and
 * refuses any read it was not scripted for. A summary that read the credit
 * total into *Spent*, or compared against `window.previous()` instead of the
 * same days of last month, comes out as a wrong number here rather than as a
 * plausible one on screen (Law 2).
 */
class GetHomeSummaryUseCaseTest {

    private val analytics = ScriptedAnalytics()
    private val summarise = GetHomeSummaryUseCase(analytics)

    @Test
    fun spending_isTheDebitBook_againstTheSameDaysOfLastMonth() = runTest {
        val summary = summarise(TODAY)

        assertThat(summary.spending).isEqualTo(
            BookMonth(LedgerType.DEBIT, total = Money(DEBIT_MONTH), previousTotal = Money(DEBIT_LAST)),
        )
        assertThat(summary.window).isEqualTo(MonthToDate.window(TODAY))
        assertThat(summary.previousWindow).isEqualTo(MonthToDate.previous(TODAY))
    }

    @Test
    fun income_isTheCreditBook_againstTheSameDaysOfLastMonth() = runTest {
        val summary = summarise(TODAY)

        assertThat(summary.income).isEqualTo(
            BookMonth(LedgerType.CREDIT, total = Money(CREDIT_MONTH), previousTotal = Money(CREDIT_LAST)),
        )
    }

    /**
     * The credit book contributes two figures, so it is read as two totals.
     * A credit snapshot would run recurring detection and every breakdown to
     * produce them, on the screen the app opens to.
     */
    @Test
    fun theCreditBook_isNeverSnapshotted() = runTest {
        summarise(TODAY)

        assertThat(analytics.snapshots).containsExactly(LedgerType.DEBIT to MonthToDate.window(TODAY))
    }

    @Test
    fun spentToday_isTodaysDebitTotal_orZero() = runTest {
        analytics.days = listOf(
            DayTotal(TODAY - 1, Money(500_00L), transactionCount = 1),
            DayTotal(TODAY, Money(1_240_00L), transactionCount = 2),
        )
        assertThat(summarise(TODAY).spentToday).isEqualTo(Money(1_240_00L))

        analytics.days = listOf(DayTotal(TODAY - 1, Money(500_00L), transactionCount = 1))
        assertThat(summarise(TODAY).spentToday).isEqualTo(Money(0L))
    }

    @Test
    fun budgets_areTheThreeClosestToTheirLimit_mostSpentFirst() = runTest {
        analytics.budgets = listOf(
            budget("rent", spent = 10_00L, of = 100_00L),
            budget("food", spent = 95_00L, of = 100_00L),
            budget("fuel", spent = 50_00L, of = 100_00L),
            budget("fun", spent = 120_00L, of = 100_00L),
        )

        val ids = summarise(TODAY).budgets.map { it.budget.id }

        assertThat(ids).containsExactly("fun", "food", "fuel").inOrder()
    }

    /**
     * Through the month's end, from today. Not the snapshot's own runway,
     * which projects a range's nominal length past the window, and a custom
     * window's nominal length is a placeholder.
     */
    @Test
    fun runway_coversTodayThroughTheEndOfTheMonth() = runTest {
        analytics.recurring = listOf(
            recurring("past", next = TODAY - 1, amount = 1_00L),
            recurring("month-end", next = day(2026, 10, 31), amount = 499_00L),
            recurring("today", next = TODAY, amount = 3_000_00L),
            recurring("next-month", next = day(2026, 11, 1), amount = 99_00L),
        )

        val summary = summarise(TODAY)

        assertThat(summary.runway.map { it.merchantId }).containsExactly("today", "month-end").inOrder()
        assertThat(summary.runwayThrough).isEqualTo(day(2026, 10, 31))
        assertThat(summary.runwayTotal).isEqualTo(Money(3_499_00L))
    }

    @Test
    fun captureCoverage_isTheDebitSnapshots() = runTest {
        val coverage = CaptureCoverage(
            automatic = CaptureShare(Money(72_00L), count = 41),
            manual = CaptureShare(Money(28_00L), count = 16),
            imported = CaptureShare(Money(0L), count = 0),
        )
        analytics.coverage = coverage

        assertThat(summarise(TODAY).captureCoverage).isEqualTo(coverage)
    }

    // ── Fixtures ─────────────────────────────────────────────────────────────

    private class ScriptedAnalytics : AnalyticsRepository {
        val snapshots = mutableListOf<Pair<LedgerType, AnalyticsWindow>>()
        var days: List<DayTotal> = emptyList()
        var budgets: List<BudgetProgress> = emptyList()
        var recurring: List<RecurringMerchant> = emptyList()
        var coverage: CaptureCoverage = CaptureCoverage.Empty

        private val totals = mapOf(
            (LedgerType.DEBIT to MonthToDate.window(TODAY)) to DEBIT_MONTH,
            (LedgerType.DEBIT to MonthToDate.previous(TODAY)) to DEBIT_LAST,
            (LedgerType.CREDIT to MonthToDate.window(TODAY)) to CREDIT_MONTH,
            (LedgerType.CREDIT to MonthToDate.previous(TODAY)) to CREDIT_LAST,
        )

        override suspend fun snapshot(
            ledger: LedgerType,
            window: AnalyticsWindow,
            comparePrevious: Boolean,
            filters: AnalyticsFilters,
        ): AnalyticsSnapshot {
            snapshots += ledger to window
            return AnalyticsSnapshot(
                ledger = ledger,
                window = window,
                total = Money(answer(ledger, window)),
                previousTotal = null,
                transactionCount = 0,
                timeBuckets = emptyList(),
                categories = emptyList(),
                subcategories = emptyMap(),
                merchants = emptyList(),
                paymentMethods = emptyList(),
                days = days,
                budgets = budgets,
                recurring = recurring,
                captureCoverage = coverage,
            )
        }

        override suspend fun total(ledger: LedgerType, window: AnalyticsWindow): Money =
            Money(answer(ledger, window))

        private fun answer(ledger: LedgerType, window: AnalyticsWindow): Long =
            requireNotNull(totals[ledger to window]) { "unscripted read: $ledger $window" }
    }

    private fun budget(id: String, spent: Long, of: Long) = BudgetProgress(
        budget = Budget(
            id = id,
            categoryId = "category-$id",
            subcategoryId = null,
            period = BudgetPeriod.MONTHLY,
            amount = Money(of),
            startDate = 0,
            rolloverEnabled = false,
            alertThresholds = emptyList(),
        ),
        categoryName = id,
        categoryColorArgb = null,
        spent = Money(spent),
        periodStart = MonthToDate.window(TODAY).from,
        periodEnd = MonthToDate.lastDay(TODAY),
        daysElapsed = 14,
        projectedSpend = Money(spent),
    )

    private fun recurring(id: String, next: Int, amount: Long) = RecurringMerchant(
        merchantId = id,
        name = id,
        occurrences = 3,
        intervalDays = 30,
        regularity = 0.1,
        typicalAmount = Money(amount),
        lastSeen = next - 30,
        nextExpected = next,
    )

    private companion object {
        val TODAY: Int = day(2026, 10, 14)

        const val DEBIT_MONTH = 42_310_00L
        const val DEBIT_LAST = 37_000_00L
        const val CREDIT_MONTH = 85_000_00L
        const val CREDIT_LAST = 88_000_00L

        fun day(year: Int, month: Int, dayOfMonth: Int): Int =
            LocalDate.of(year, month, dayOfMonth).toEpochDay().toInt()
    }
}
