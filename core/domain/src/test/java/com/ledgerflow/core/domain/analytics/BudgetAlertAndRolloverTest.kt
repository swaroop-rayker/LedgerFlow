package com.ledgerflow.core.domain.analytics

import com.google.common.truth.Truth.assertThat
import com.ledgerflow.core.model.BudgetPeriod
import com.ledgerflow.core.model.Money
import org.junit.Test

/**
 * §5.7's two pieces of real arithmetic: when an alert is news, and what carries
 * over.
 *
 * Both are pure functions, so both are tested here rather than inferred from a
 * notification appearing on a phone — the same argument `LfAxisTicks` and
 * `RecurringDetection` make.
 */
class BudgetAlertAndRolloverTest {

    // ── Alerts ─────────────────────────────────────────────────────────────

    @Test
    fun crossingAThresholdForTheFirstTime_isAnnounced() {
        val progress = progress(spent = 850_000L, amount = 1_000_000L)

        assertThat(progress.thresholdToAnnounce()).isEqualTo(80)
    }

    /**
     * **The assertion the whole v10 column exists for.**
     *
     * Once announced, the same crossing in the same period is silent. Without
     * this the alert fires on every evaluation after the crossing — a
     * notification each time the user approves anything, which is how an alert
     * becomes one that gets turned off.
     */
    @Test
    fun theSameThresholdInTheSamePeriod_isSilent() {
        val progress = progress(
            spent = 850_000L,
            amount = 1_000_000L,
            lastAlerted = 80,
            alertPeriodStart = PERIOD_START,
        )

        assertThat(progress.thresholdToAnnounce()).isNull()
    }

    /** A *higher* threshold in the same period is still news. */
    @Test
    fun ahigherThresholdInTheSamePeriod_isAnnounced() {
        val progress = progress(
            spent = 1_050_000L,
            amount = 1_000_000L,
            lastAlerted = 80,
            alertPeriodStart = PERIOD_START,
        )

        assertThat(progress.thresholdToAnnounce()).isEqualTo(100)
    }

    /**
     * **A new period resets it**, which is what the period column is for.
     *
     * 80% crossed last month must not suppress 80% crossed this month — with
     * only a threshold stored, a budget would alert once and then never again
     * for the life of the app.
     */
    @Test
    fun theSameThresholdInANewPeriod_isAnnouncedAgain() {
        val progress = progress(
            spent = 850_000L,
            amount = 1_000_000L,
            lastAlerted = 80,
            // Alerted during the *previous* period.
            alertPeriodStart = PERIOD_START - 30,
        )

        assertThat(progress.thresholdToAnnounce()).isEqualTo(80)
    }

    @Test
    fun belowEveryThreshold_saysNothing() {
        assertThat(progress(spent = 100_000L, amount = 1_000_000L).thresholdToAnnounce()).isNull()
    }

    /**
     * Only the highest crossing is announced.
     *
     * Spending that jumps from 50% to 120% in one purchase is one event, and
     * telling the user twice about it is telling them once too many.
     */
    @Test
    fun ajumpPastBothThresholds_announcesOnlyTheHigher() {
        assertThat(progress(spent = 1_200_000L, amount = 1_000_000L).thresholdToAnnounce())
            .isEqualTo(100)
    }

    // ── Rollover ───────────────────────────────────────────────────────────

    @Test
    fun unspentBudgetCarriesForward() {
        val carried = BudgetPeriods.rollover(
            budgetAmount = Money(1_000_000L),
            previousSpend = Money(400_000L),
        )

        assertThat(carried.minor).isEqualTo(600_000L)
    }

    /**
     * **An overspend does not carry.**
     *
     * Carrying a negative would silently shrink next month's budget, which is a
     * punishment nobody agreed to when they ticked a box labelled "roll over
     * unspent". §5.7 says unspent; unspent is what carries.
     */
    @Test
    fun anOverspendCarriesNothing_ratherThanANegative() {
        val carried = BudgetPeriods.rollover(
            budgetAmount = Money(1_000_000L),
            previousSpend = Money(1_400_000L),
        )

        assertThat(carried.minor).isEqualTo(0L)
    }

    @Test
    fun rolloverRaisesTheEffectiveBudget_andWithItTheThresholds() {
        val withCarry = progress(
            spent = 850_000L,
            amount = 1_000_000L,
            rolledOver = 500_000L,
        )

        // ₹8,500 against ₹15,000 available is 56% -- under 80, so silent, where
        // the same spend against the bare budget would have announced.
        assertThat(withCarry.effectiveAmount.minor).isEqualTo(1_500_000L)
        assertThat(withCarry.thresholdToAnnounce()).isNull()
        assertThat(withCarry.fraction).isWithin(TOLERANCE).of(0.5667f)
    }

    // ── Period arithmetic ──────────────────────────────────────────────────

    @Test
    fun periodsRepeatFromTheStartDate_notFromTheCalendar() {
        val budget = budget(startDate = 20_000, period = BudgetPeriod.MONTHLY)

        // Day 65 after the start is inside the third 30-day period.
        val period = BudgetPeriods.currentPeriod(budget, today = 20_065)

        assertThat(period.first).isEqualTo(20_060)
        assertThat(period.last).isEqualTo(20_089)
    }

    /** A budget starting in the future reports its first period, not a past one. */
    @Test
    fun afutureBudgetReportsItsFirstPeriod() {
        val budget = budget(startDate = 20_100, period = BudgetPeriod.MONTHLY)

        val period = BudgetPeriods.currentPeriod(budget, today = 20_000)

        assertThat(period.first).isEqualTo(20_100)
    }

    private fun budget(
        startDate: Int = PERIOD_START,
        period: BudgetPeriod = BudgetPeriod.MONTHLY,
        amount: Long = 1_000_000L,
        lastAlerted: Int = 0,
        alertPeriodStart: Int = 0,
    ) = Budget(
        id = "b",
        categoryId = "cat",
        subcategoryId = null,
        period = period,
        amount = Money(amount),
        startDate = startDate,
        rolloverEnabled = false,
        alertThresholds = listOf(80, 100),
        lastAlertedThreshold = lastAlerted,
        alertPeriodStart = alertPeriodStart,
    )

    private fun progress(
        spent: Long,
        amount: Long,
        lastAlerted: Int = 0,
        alertPeriodStart: Int = 0,
        rolledOver: Long = 0L,
    ) = BudgetProgress(
        budget = budget(
            amount = amount,
            lastAlerted = lastAlerted,
            alertPeriodStart = alertPeriodStart,
        ),
        categoryName = "Groceries",
        categoryColorArgb = null,
        spent = Money(spent),
        periodStart = PERIOD_START,
        periodEnd = PERIOD_START + 29,
        daysElapsed = 10,
        projectedSpend = Money(spent * 3),
        rolledOver = Money(rolledOver),
    )

    private companion object {
        const val PERIOD_START = 20_000
        const val TOLERANCE = 0.001f
    }
}
