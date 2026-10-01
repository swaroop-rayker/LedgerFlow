package com.ledgerflow.feature.analytics

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ledgerflow.core.designsystem.theme.LfTheme
import com.ledgerflow.core.domain.analytics.AnalyticsRange
import com.ledgerflow.core.domain.analytics.AnalyticsSnapshot
import com.ledgerflow.core.domain.analytics.AnalyticsWindow
import com.ledgerflow.core.domain.analytics.Budget
import com.ledgerflow.core.domain.analytics.BudgetProgress
import com.ledgerflow.core.domain.analytics.CaptureCoverage
import com.ledgerflow.core.domain.analytics.CaptureShare
import com.ledgerflow.core.domain.analytics.DayTotal
import com.ledgerflow.core.domain.analytics.DimensionTotal
import com.ledgerflow.core.domain.analytics.ParallelBucket
import com.ledgerflow.core.domain.analytics.ParserGap
import com.ledgerflow.core.domain.analytics.RecurringMerchant
import com.ledgerflow.core.domain.analytics.TimeBucket
import com.ledgerflow.core.model.BudgetPeriod
import com.ledgerflow.core.model.LedgerType
import com.ledgerflow.core.model.Money
import com.ledgerflow.core.testing.screenshot.GOLDEN_SDK
import com.ledgerflow.core.testing.screenshot.PHONE_2X
import com.ledgerflow.core.testing.screenshot.captureScreenGolden
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Analytics at font scale 1.0 and 2.0 (§12, P5 step 4), with §9.6's checks.
 *
 * The screen had one preview, of no data at all, so this builds what it was
 * designed for: a month of spending with **every section populated** — time
 * chart, category donut with deltas, merchants, payment methods, the heatmap,
 * a budget near its limit, recurring charges and the runway, capture coverage
 * (C1), a parser gap (C2) and the two books side by side (D1). The charts
 * themselves have component goldens in `:core:designsystem`; these are about
 * the page: section order, spacing, and whether anything clips at 2.0.
 *
 * Amounts follow a fixed arithmetic pattern rather than a random seed, so the
 * fixture reads the same in every run and in every reviewer's head.
 *
 * Analytics is the longest screen in the app, so even 1x renders in the tall
 * window; the first 1x recording at PHONE_1X stopped at Top merchants.
 *
 * **Review the diff; never re-record blind** (`CLAUDE.md` §12).
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [GOLDEN_SDK], qualifiers = PHONE_2X)
class AnalyticsScreenGoldensTest {

    @get:Rule
    val rule = createComposeRule()

    private fun capture(name: String, state: AnalyticsUiState, fontScale: Float) =
        rule.captureScreenGolden(name, fontScale) {
            LfTheme { AnalyticsScreen(state = state, onEvent = {}) }
        }

    private val window = AnalyticsWindow.endingOn(TODAY, AnalyticsRange.MONTH)

    private fun dim(id: String, name: String, minor: Long, count: Int, previous: Long?, color: Int? = null) =
        DimensionTotal(
            id = id,
            name = name,
            colorArgb = color,
            amount = Money(minor),
            transactionCount = count,
            previousAmount = previous?.let(::Money),
        )

    private val categories = listOf(
        dim("rent", "Rent", 24_000_00, 1, 24_000_00, BLUE),
        dim("groceries", "Groceries", 8_640_50, 14, 7_210_00, GREEN),
        dim("dining", "Dining out", 3_420_00, 9, 4_100_00, ORANGE),
        dim("utilities", "Electricity & internet", 2_310_00, 3, 2_310_00, TEAL),
        dim("transport", "Transport", 1_980_00, 22, 2_050_00, PURPLE),
    ) // Largest first, as the repository returns them.

    private val total = categories.sumOf { it.amount.minor }

    /** Day `i` of the window: a weekly shape plus a monthly rent spike, all integers. */
    private fun dayMinor(i: Int): Long = when {
        i == RENT_DAY -> 24_000_00L + 410_00L
        else -> 180_00L + (i % 7) * 95_00L + (i % 3) * 40_00L
    }

    /**
     * Day `i` split across categories, summing exactly to [dayMinor]. The chart
     * stacks by category, so a bucket with no split draws nothing -- the first
     * recording was an empty "Spend over time" card for exactly that reason.
     */
    private fun dayByCategory(i: Int): List<DimensionTotal> {
        val amount = dayMinor(i)
        val transport = 60_00L
        return if (i == RENT_DAY) {
            listOf(
                dim("rent", "Rent", 24_000_00L, 1, null, BLUE),
                dim("groceries", "Groceries", amount - 24_000_00L, 1, null, GREEN),
            )
        } else {
            listOf(
                dim("groceries", "Groceries", amount - transport, 1, null, GREEN),
                dim("transport", "Transport", transport, 1, null, PURPLE),
            )
        }
    }

    private val snapshot = AnalyticsSnapshot(
        ledger = LedgerType.DEBIT,
        window = window,
        total = Money(total),
        previousTotal = Money(39_670_00),
        transactionCount = 49,
        timeBuckets = (0 until AnalyticsRange.MONTH.days).map { i ->
            TimeBucket(
                bucket = i,
                startDate = window.from + i,
                endDate = window.from + i,
                amount = Money(dayMinor(i)),
                byCategory = dayByCategory(i),
            )
        },
        categories = categories,
        subcategories = mapOf(
            "groceries" to listOf(
                dim("staples", "Staples", 4_100_00, 5, 3_900_00),
                dim("produce", "Fruit & vegetables", 2_940_50, 7, 2_210_00),
                dim("dairy", "Dairy", 1_600_00, 2, 1_100_00),
            ),
        ),
        merchants = listOf(
            dim("landlord", "Landlord", 24_000_00, 1, 24_000_00),
            dim("bigbasket", "bigbasket", 5_210_00, 6, 4_300_00),
            dim("zepto", "Zepto", 3_430_50, 8, 2_910_00),
            dim("swiggy", "Swiggy", 2_140_00, 5, 2_900_00),
            dim("uber", "Uber", 1_120_00, 7, 1_300_00),
        ),
        paymentMethods = listOf(
            dim("netbanking", "Net banking", 24_000_00, 1, 24_000_00),
            dim("upi", "UPI", 15_810_50, 38, 14_200_00),
            dim("card", "Credit card", 580_00, 10, 1_470_00),
        ),
        days = (0 until AnalyticsRange.MONTH.days).map { i ->
            DayTotal(localDate = window.from + i, amount = Money(dayMinor(i)), transactionCount = 1 + i % 3)
        },
        budgets = listOf(
            BudgetProgress(
                budget = Budget(
                    id = "b-groceries",
                    categoryId = "groceries",
                    subcategoryId = null,
                    period = BudgetPeriod.MONTHLY,
                    amount = Money(10_000_00),
                    startDate = window.from,
                    rolloverEnabled = false,
                    alertThresholds = listOf(80, 100),
                ),
                categoryName = "Groceries",
                categoryColorArgb = GREEN,
                spent = Money(8_640_50),
                periodStart = window.from,
                periodEnd = window.to,
                daysElapsed = 24,
                projectedSpend = Money(10_800_00),
            ),
        ),
        recurring = listOf(
            RecurringMerchant("landlord", "Landlord", 6, 30, 0.02, Money(24_000_00), TODAY - 6, TODAY + 24),
            RecurringMerchant("netflix", "Netflix", 5, 30, 0.04, Money(649_00), TODAY - 27, TODAY + 3),
        ),
        runway = listOf(
            RecurringMerchant("netflix", "Netflix", 5, 30, 0.04, Money(649_00), TODAY - 27, TODAY + 3),
        ),
        captureCoverage = CaptureCoverage(
            automatic = CaptureShare(Money(31_200_00), 38),
            manual = CaptureShare(Money(9_150_50), 11),
            imported = CaptureShare(Money(0L), 0),
        ),
        parserGaps = listOf(
            ParserGap("chai", "Ramu Tea Stall", manualCount = 6, totalCount = 6, manualAmount = Money(240_00)),
        ),
        parallelBooks = (0 until 4).map { w ->
            ParallelBucket(
                bucket = w,
                startDate = window.from + w * 7,
                endDate = window.from + w * 7 + 6,
                credit = Money(if (w == 0) 85_000_00L else 1_200_00L),
                debit = Money(if (w == 0) 26_400_00L else 4_600_00L + w * 300_00L),
            )
        },
    )

    private val full = AnalyticsUiState(isLoading = false, snapshot = snapshot)

    /** A book with nothing in the window: what a new user's Analytics shows. */
    private val empty = AnalyticsUiState(
        isLoading = false,
        snapshot = AnalyticsSnapshot(
            ledger = LedgerType.DEBIT,
            window = window,
            total = Money(0L),
            previousTotal = null,
            transactionCount = 0,
            timeBuckets = emptyList(),
            categories = emptyList(),
            subcategories = emptyMap(),
            merchants = emptyList(),
            paymentMethods = emptyList(),
        ),
    )

    @Test fun full_1x() = capture("analytics-full-1x", full, 1f)

    @Config(qualifiers = TALLEST)
    @Test fun full_2x() = capture("analytics-full-2x", full, 2f)

    @Test fun empty_1x() = capture("analytics-empty-1x", empty, 1f)

    @Config(qualifiers = TALLEST)
    @Test fun empty_2x() = capture("analytics-empty-2x", empty, 2f)

    private companion object {
        const val TODAY = 20_684
        const val RENT_DAY = 5
        const val BLUE = 0xFF3E6AD6.toInt()
        const val GREEN = 0xFF2E9E6B.toInt()
        const val ORANGE = 0xFFE07B39.toInt()
        const val PURPLE = 0xFF8A5CD1.toInt()
        const val TEAL = 0xFF1F9AA8.toInt()

        /** Analytics is the longest screen in the app; PHONE_2X stopped short of its end. */
        const val TALLEST = "w360dp-h7200dp"
    }
}
