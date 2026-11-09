package com.ledgerflow.feature.tools.goals

import com.google.common.truth.Truth.assertThat
import com.ledgerflow.core.domain.goals.GoalOutlook
import com.ledgerflow.core.domain.goals.GoalProgress
import com.ledgerflow.core.domain.goals.SavingsGoal
import com.ledgerflow.core.model.Money
import java.time.LocalDate
import org.junit.Test

/** What a goal's progress says (V2-PLAN §4.1). A wrong word here is a wrong fact. */
class GoalWordsTest {

    private val today = LocalDate.of(2026, 11, 9).toEpochDay().toInt()

    private fun progress(
        saved: Long,
        target: Long = 1_00_000_00,
        targetDate: Int? = null,
        outlook: GoalOutlook,
    ) = GoalProgress(
        goal = SavingsGoal("g", "Emergency fund", Money(target), targetDate, null, 0, 0L),
        fromHand = Money(saved),
        fromCategory = Money.ZERO,
        outlook = outlook,
    )

    @Test
    fun span_readsInDaysThenMonthsThenYears() {
        assertThat(GoalWords.span(1)).isEqualTo("1 day")
        assertThat(GoalWords.span(30)).isEqualTo("30 days")
        assertThat(GoalWords.span(45)).isEqualTo("about 1 month")
        assertThat(GoalWords.span(61)).isEqualTo("about 2 months")
        assertThat(GoalWords.span(425)).isEqualTo("about 14 months")
        assertThat(GoalWords.span(730)).isEqualTo("about 2 years")
    }

    @Test
    fun caption_onCourse_namesTheDay() {
        val line = GoalWords.caption(
            progress(saved = 62_000_00, targetDate = today + 200, outlook = projected(today + 97, daysLate = -103)),
            today,
            CURRENCY,
        )

        assertThat(line.text).isEqualTo("62% of ₹1,00,000.00 · on course for 14 Feb 2027")
        assertThat(line.warning).isFalse()
    }

    @Test
    fun caption_late_isAWarning_withHowLate() {
        val line = GoalWords.caption(
            progress(saved = 46_000_00, targetDate = today + 145, outlook = projected(today + 206, daysLate = 61)),
            today,
            CURRENCY,
        )

        assertThat(line.text).isEqualTo("46% of ₹1,00,000.00 · at this pace 3 Jun 2027, about 2 months late")
        assertThat(line.warning).isTrue()
    }

    @Test
    fun caption_withNoDate_saysHowLong() {
        val line = GoalWords.caption(
            progress(saved = 9_000_00, outlook = projected(today + 425, daysLate = null)),
            today,
            CURRENCY,
        )

        assertThat(line.text).isEqualTo("9% of ₹1,00,000.00 · about 14 months at this pace")
    }

    @Test
    fun caption_pastTheTargetDate_saysSo() {
        val line = GoalWords.caption(
            progress(saved = 40_000_00, targetDate = today - 1, outlook = GoalOutlook.Stalled(90)),
            today,
            CURRENCY,
        )

        assertThat(line.text).isEqualTo("40% of ₹1,00,000.00 · target date passed")
        assertThat(line.warning).isTrue()
    }

    @Test
    fun caption_tooEarlyAndStalled() {
        assertThat(GoalWords.caption(progress(saved = 0, outlook = GoalOutlook.TooEarly), today, CURRENCY).text)
            .isEqualTo("0% of ₹1,00,000.00 · too early to project")
        val stalled = progress(saved = 10_000_00, outlook = GoalOutlook.Stalled(90))
        assertThat(GoalWords.caption(stalled, today, CURRENCY).text)
            .isEqualTo("10% of ₹1,00,000.00 · nothing added in the last 90 days")
    }

    @Test
    fun facts_projected_areTargetPaceAndOutlook() {
        val facts = GoalWords.facts(
            progress(saved = 62_000_00, targetDate = today + 142, outlook = projected(today + 97, daysLate = -45)),
            today,
            currency = CURRENCY,
        )

        assertThat(facts.map { it.text }).containsExactly(
            "Target 31 Mar 2027",
            "Pace ₹6,450.00 a month, last 90 days",
            "On course for 14 Feb 2027",
        ).inOrder()
    }

    @Test
    fun countsSentence_namesTheCategoryAndItsBook() {
        assertThat(countsSentence("Savings · Expense", "1 Aug 2026")).isEqualTo(
            "Approved entries in Savings from 1 Aug 2026 add to this goal. " +
                "They stay in your Expense book and its totals.",
        )
    }

    private companion object {
        const val CURRENCY = "INR"
    }

    private fun projected(finish: Int, daysLate: Int?) =
        GoalOutlook.Projected(monthlyPace = Money(6_450_00), windowDays = 90, finish = finish, daysLate = daysLate)
}
