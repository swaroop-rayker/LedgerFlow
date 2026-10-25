package com.ledgerflow.core.domain.analytics

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import com.ledgerflow.core.model.BudgetPeriod
import com.ledgerflow.core.model.Money
import org.junit.Test

/**
 * Overspending alerts (V2-PLAN §3.4; the owner's rule, 2026-10-24): a linear
 * projection past the limit, not before day 7 (day 2 weekly), never once 100%
 * is reached or announced, once per budget per period.
 */
class OverspendWarningTest {

    @Test
    fun onCourseToOverrun_fromDaySeven_isNews() {
        // ₹4,000 of ₹10,000 by day 10 of 30 projects to ₹12,000.
        assertThat(progress(spent = 4_000_00, day = 10).isOverspendNews(alreadyAnnounced = false)).isTrue()
    }

    @Test
    fun beforeDaySeven_isNotNews_evenOnCourse() {
        // Day 6: a big early shop projects far over, and that is arithmetic.
        assertThat(progress(spent = 9_000_00, day = 6).isOverspendNews(false)).isFalse()
        assertThat(progress(spent = 9_000_00, day = 7).isOverspendNews(false)).isTrue()
    }

    @Test
    fun aWeeklyBudget_canWarnFromDayTwo() {
        assertThat(progress(spent = 500_00, day = 1, period = BudgetPeriod.WEEKLY).isOverspendNews(false)).isFalse()
        assertThat(progress(spent = 500_00, day = 2, period = BudgetPeriod.WEEKLY).isOverspendNews(false)).isTrue()
        assertThat(minimumDays(BudgetPeriod.QUARTERLY)).isEqualTo(7)
        assertThat(minimumDays(BudgetPeriod.YEARLY)).isEqualTo(7)
    }

    @Test
    fun notOnCourse_isNotNews() {
        // ₹2,000 by day 10 projects to ₹6,000 of ₹10,000.
        assertThat(progress(spent = 2_000_00, day = 10).isOverspendNews(false)).isFalse()
    }

    @Test
    fun onceAlreadyAnnouncedThisPeriod_isNotNewsAgain() {
        assertThat(progress(spent = 4_000_00, day = 10).isOverspendNews(alreadyAnnounced = true)).isFalse()
    }

    @Test
    fun atOrPastTheLimit_isNotNews() {
        // Reached 100%: "you will go over" is old news, the 100% alert says it.
        assertThat(progress(spent = 10_000_00, day = 20).isOverspendNews(false)).isFalse()
        assertThat(progress(spent = 11_000_00, day = 20).isOverspendNews(false)).isFalse()
    }

    @Test
    fun afterThe100PercentAlertThisPeriod_isNotNews() {
        // Spending came back under the limit (an entry deleted), the 100%
        // alert already went out this period.
        val announcedFull = progress(spent = 4_000_00, day = 10, lastAlerted = 100, alertPeriodStart = PERIOD_START)
        assertThat(announcedFull.isOverspendNews(false)).isFalse()
        // An 80% alert does not silence it, and a 100% from an older period does not either.
        val announced80 = progress(spent = 8_500_00, day = 25, lastAlerted = 80, alertPeriodStart = PERIOD_START)
        assertWithMessage("80% announced, still on course").that(announced80.isOverspendNews(false)).isTrue()
        val oldFull = progress(spent = 4_000_00, day = 10, lastAlerted = 100, alertPeriodStart = PERIOD_START - 30)
        assertThat(oldFull.isOverspendNews(false)).isTrue()
    }

    @Test
    fun rolloverRaisesTheLimit() {
        // ₹4,000 by day 10 projects to ₹12,000; with ₹3,000 rolled over the limit is ₹13,000.
        assertThat(progress(spent = 4_000_00, day = 10, rolledOver = 3_000_00).isOverspendNews(false)).isFalse()
    }

    private fun progress(
        spent: Long,
        day: Int,
        period: BudgetPeriod = BudgetPeriod.MONTHLY,
        lastAlerted: Int = 0,
        alertPeriodStart: Int = 0,
        rolledOver: Long = 0L,
    ): BudgetProgress {
        val length = BudgetPeriods.lengthInDays(period)
        val amount = if (period == BudgetPeriod.WEEKLY) WEEKLY_AMOUNT else AMOUNT
        return BudgetProgress(
            budget = Budget(
                id = "b1",
                categoryId = "groceries",
                subcategoryId = null,
                period = period,
                amount = Money(amount),
                startDate = PERIOD_START,
                rolloverEnabled = rolledOver > 0,
                alertThresholds = listOf(80, 100),
                lastAlertedThreshold = lastAlerted,
                alertPeriodStart = alertPeriodStart,
            ),
            categoryName = "Groceries",
            categoryColorArgb = null,
            spent = Money(spent),
            periodStart = PERIOD_START,
            periodEnd = PERIOD_START + length - 1,
            daysElapsed = day,
            projectedSpend = BudgetPeriods.project(Money(spent), day, length),
            rolledOver = Money(rolledOver),
        )
    }

    private companion object {
        const val PERIOD_START = 20_000
        const val AMOUNT = 10_000_00L
        const val WEEKLY_AMOUNT = 1_000_00L
    }
}
