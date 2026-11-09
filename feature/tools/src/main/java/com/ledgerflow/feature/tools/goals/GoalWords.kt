package com.ledgerflow.feature.tools.goals

import com.ledgerflow.core.designsystem.format.DisplayDates
import com.ledgerflow.core.designsystem.format.MoneyFormat
import com.ledgerflow.core.domain.goals.GoalOutlook
import com.ledgerflow.core.domain.goals.GoalProgress
import com.ledgerflow.core.model.Money
import java.time.LocalDate

/** A line of text and whether it is a warning (amber, `status.warning`). */
internal data class GoalLine(val text: String, val warning: Boolean = false)

/**
 * How a goal's progress reads (V2-PLAN §4.1, the owner's mockups of
 * 2026-11-09). One place, so the list and the goal's page never disagree, and
 * unit-tested, because a wrong word here is a wrong fact.
 */
internal object GoalWords {

    fun date(day: Int): String = DisplayDates.dayMonthYear(LocalDate.ofEpochDay(day.toLong()))

    fun money(amount: Money, currency: String): String = MoneyFormat.symbolised(amount.minor, currency)

    /** "12 days", "about 2 months", "about 3 years". */
    fun span(days: Int): String = when {
        days == 1 -> "1 day"
        days < MONTH_FROM_DAYS -> "$days days"
        days < YEARS_FROM_DAYS -> about(roundedDiv(days * TENTHS, DAYS_PER_MONTH_TENTHS), "month")
        else -> about(roundedDiv(days, DAYS_PER_YEAR), "year")
    }

    /** The list row's line under the bar: the percent of the target, then where it is heading. */
    fun caption(progress: GoalProgress, today: Int, currency: String): GoalLine {
        val percent = "${progress.percent}% of ${money(progress.goal.target, currency)}"
        val passed = progress.goal.targetDate?.let { it < today } == true
        return when (val outlook = progress.outlook) {
            GoalOutlook.Reached -> GoalLine("$percent · reached")
            GoalOutlook.TooEarly ->
                if (passed) GoalLine("$percent · target date passed", warning = true)
                else GoalLine("$percent · too early to project")
            is GoalOutlook.Stalled ->
                if (passed) GoalLine("$percent · target date passed", warning = true)
                else GoalLine("$percent · nothing added in the last ${outlook.windowDays} days")
            is GoalOutlook.Projected -> projectedCaption(percent, outlook, passed, today)
        }
    }

    /** The goal page's three facts: the target, the pace, and the outlook. */
    fun facts(progress: GoalProgress, today: Int, currency: String): List<GoalLine> {
        val target = progress.goal.targetDate?.let { GoalLine("Target ${date(it)}") } ?: GoalLine("No target date")
        val passed = progress.goal.targetDate?.let { it < today } == true && !progress.isReached
        return when (val outlook = progress.outlook) {
            GoalOutlook.Reached -> listOf(target, GoalLine("Reached"))
            GoalOutlook.TooEarly -> listOf(target, GoalLine("Too early to project a pace"))
            is GoalOutlook.Stalled -> listOfNotNull(
                target,
                GoalLine("Nothing added in the last ${outlook.windowDays} days"),
                GoalLine("Target date passed", warning = true).takeIf { passed },
            )
            is GoalOutlook.Projected -> listOf(
                target,
                GoalLine("Pace ${money(outlook.monthlyPace, currency)} a month, last ${outlook.windowDays} days"),
                projectedOutlook(outlook, passed, today),
            )
        }
    }

    private fun projectedCaption(
        percent: String,
        outlook: GoalOutlook.Projected,
        passed: Boolean,
        today: Int,
    ): GoalLine {
        val late = outlook.daysLate
        return when {
            passed -> GoalLine("$percent · target date passed", warning = true)
            late == null -> GoalLine("$percent · ${span(outlook.finish - today)} at this pace")
            late <= 0 -> GoalLine("$percent · on course for ${date(outlook.finish)}")
            else -> GoalLine("$percent · at this pace ${date(outlook.finish)}, ${span(late)} late", warning = true)
        }
    }

    private fun projectedOutlook(outlook: GoalOutlook.Projected, passed: Boolean, today: Int): GoalLine {
        val late = outlook.daysLate
        return when {
            passed -> GoalLine("Target date passed; at this pace ${date(outlook.finish)}", warning = true)
            late == null -> GoalLine("At this pace ${date(outlook.finish)}, in ${span(outlook.finish - today)}")
            late <= 0 -> GoalLine("On course for ${date(outlook.finish)}")
            else -> GoalLine("At this pace ${date(outlook.finish)}, ${span(late)} late", warning = true)
        }
    }

    private fun about(count: Int, unit: String): String = if (count == 1) "about 1 $unit" else "about $count ${unit}s"

    private fun roundedDiv(numerator: Int, denominator: Int): Int = (numerator + denominator / 2) / denominator

    private const val MONTH_FROM_DAYS = 45
    private const val YEARS_FROM_DAYS = 730
    private const val TENTHS = 10
    private const val DAYS_PER_MONTH_TENTHS = 304
    private const val DAYS_PER_YEAR = 365
}
