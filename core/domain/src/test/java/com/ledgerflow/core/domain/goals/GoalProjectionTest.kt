package com.ledgerflow.core.domain.goals

import com.google.common.truth.Truth.assertThat
import com.ledgerflow.core.model.Money
import org.junit.Test

/**
 * A goal's pace and finish date (V2-PLAN §4.1): arithmetic with a correct
 * answer, so it is pinned here rather than read off a screen.
 */
class GoalProjectionTest {

    private val today = 20_400

    private fun goal(target: Long, targetDate: Int? = null) = SavingsGoal(
        id = "g", name = "Emergency fund", target = Money(target), targetDate = targetDate,
        link = null, sortOrder = 0, createdAt = 0L,
    )

    @Test
    fun progress_atAnExactPace_projectsTheDayItIsReached() {
        // ₹62,000 of ₹1,00,000; ₹19,350 added in the last 90 days, so
        // ₹215 a day: ₹38,000 more takes ⌈176.7⌉ = 177 days.
        val progress = GoalProjection.progress(
            goal = goal(target = 1_00_000_00, targetDate = today + 200),
            fromHand = Money(22_000_00), fromCategory = Money(40_000_00),
            historyStart = today - 300, addedInWindow = Money(19_350_00), today = today,
        )

        val outlook = progress.outlook as GoalOutlook.Projected
        assertThat(outlook.monthlyPace).isEqualTo(Money(6_450_00))
        assertThat(outlook.windowDays).isEqualTo(90)
        assertThat(outlook.finish).isEqualTo(today + 177)
        assertThat(outlook.daysLate).isEqualTo(-23)
        assertThat(progress.percent).isEqualTo(62)
        assertThat(progress.remaining).isEqualTo(Money(38_000_00))
    }

    @Test
    fun progress_pastTheTargetDate_isLate() {
        val progress = GoalProjection.progress(
            goal = goal(target = 40_000_00, targetDate = today + 30),
            fromHand = Money(18_500_00), fromCategory = Money.ZERO,
            historyStart = today - 89, addedInWindow = Money(9_000_00), today = today,
        )

        // ₹21,500 at ₹100 a day is 215 days; the target is in 30.
        val outlook = progress.outlook as GoalOutlook.Projected
        assertThat(outlook.finish).isEqualTo(today + 215)
        assertThat(outlook.daysLate).isEqualTo(185)
    }

    @Test
    fun progress_withNoTargetDate_hasNoLateness() {
        val progress = GoalProjection.progress(
            goal = goal(target = 90_000_00),
            fromHand = Money(8_000_00), fromCategory = Money.ZERO,
            historyStart = today - 200, addedInWindow = Money(4_500_00), today = today,
        )

        assertThat((progress.outlook as GoalOutlook.Projected).daysLate).isNull()
    }

    @Test
    fun progress_aShortHistory_usesItAsTheWindow() {
        // 30 days of history: ₹3,000 added is ₹3,000 a month, not ₹1,000.
        val progress = GoalProjection.progress(
            goal = goal(target = 30_000_00),
            fromHand = Money(3_000_00), fromCategory = Money.ZERO,
            historyStart = today - 29, addedInWindow = Money(3_000_00), today = today,
        )

        val outlook = progress.outlook as GoalOutlook.Projected
        assertThat(outlook.windowDays).isEqualTo(30)
        assertThat(outlook.monthlyPace).isEqualTo(Money(3_000_00))
        assertThat(outlook.finish).isEqualTo(today + 270)
    }

    @Test
    fun progress_withUnderFourteenDaysOfHistory_isTooEarly() {
        val progress = GoalProjection.progress(
            goal = goal(target = 10_000_00),
            fromHand = Money(1_000_00), fromCategory = Money.ZERO,
            historyStart = today - 12, addedInWindow = Money(1_000_00), today = today,
        )

        assertThat(progress.outlook).isEqualTo(GoalOutlook.TooEarly)
    }

    @Test
    fun progress_withNothingAddedLately_isStalled() {
        val progress = GoalProjection.progress(
            goal = goal(target = 10_000_00),
            fromHand = Money(1_000_00), fromCategory = Money.ZERO,
            historyStart = today - 400, addedInWindow = Money(-500_00), today = today,
        )

        assertThat(progress.outlook).isEqualTo(GoalOutlook.Stalled(windowDays = 90))
    }

    @Test
    fun progress_atOrPastTheTarget_isReached() {
        val progress = GoalProjection.progress(
            goal = goal(target = 6_000_00),
            fromHand = Money(6_500_00), fromCategory = Money.ZERO,
            historyStart = today - 5, addedInWindow = Money.ZERO, today = today,
        )

        assertThat(progress.outlook).isEqualTo(GoalOutlook.Reached)
        assertThat(progress.isReached).isTrue()
        assertThat(progress.percent).isEqualTo(100)
        assertThat(progress.remaining).isEqualTo(Money.ZERO)
    }

    @Test
    fun percent_afterTakingOutMoreThanWasAdded_isZero() {
        val progress = GoalProjection.progress(
            goal = goal(target = 6_000_00),
            fromHand = Money(-200_00), fromCategory = Money.ZERO,
            historyStart = today - 100, addedInWindow = Money(-200_00), today = today,
        )

        assertThat(progress.percent).isEqualTo(0)
        assertThat(progress.remaining).isEqualTo(Money(6_200_00))
    }
}
