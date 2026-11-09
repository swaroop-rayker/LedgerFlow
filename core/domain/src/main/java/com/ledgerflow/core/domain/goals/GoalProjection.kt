package com.ledgerflow.core.domain.goals

import com.ledgerflow.core.model.Money

/**
 * One goal with what it holds and where it is heading (V2-PLAN §4.1).
 *
 * [fromHand] is the goal's hand-added amounts, signed; [fromCategory] what its
 * linked category holds from `countFrom` to today. Neither is a ledger figure,
 * and the two are added only to each other.
 */
public data class GoalProgress(
    val goal: SavingsGoal,
    val fromHand: Money,
    val fromCategory: Money,
    val outlook: GoalOutlook,
) {
    public val saved: Money get() = fromHand + fromCategory

    /** Nothing once the target is reached. */
    public val remaining: Money get() = if (saved >= goal.target) Money.ZERO else goal.target - saved

    public val isReached: Boolean get() = saved >= goal.target

    /** Whole percent of the target, floored, 0 to 100. */
    public val percent: Int
        get() = when {
            saved.minor <= 0L -> 0
            isReached -> PERCENT
            else -> (saved.minor * PERCENT / goal.target.minor).toInt()
        }

    private companion object {
        const val PERCENT = 100
    }
}

/** Where a goal is heading at the pace of its recent past. */
public sealed interface GoalOutlook {
    public data object Reached : GoalOutlook

    /** Fewer than [GoalProjection.MINIMUM_HISTORY_DAYS] days of history. */
    public data object TooEarly : GoalOutlook

    /** Nothing, or less than nothing, added over the last [windowDays]. */
    public data class Stalled(val windowDays: Int) : GoalOutlook

    /**
     * At [monthlyPace] (over the last [windowDays]), the goal is reached on
     * [finish], a day number. [daysLate] is [finish] less the target date:
     * positive is late, zero or less is on course, null when there is no date.
     */
    public data class Projected(
        val monthlyPace: Money,
        val windowDays: Int,
        val finish: Int,
        val daysLate: Int?,
    ) : GoalOutlook
}

/**
 * A goal's progress and outlook, as arithmetic with a correct answer.
 *
 * **The pace is the last 90 days**, or the goal's whole history when that is
 * shorter: what was added in that window, by hand and through the linked
 * category, as a figure per 30 days. With fewer than 14 days of history
 * there is too little to project from, and a window that added nothing (or
 * took out more than it added) projects nothing.
 *
 * **The finish** is today plus the days the remaining amount takes at the
 * window's daily rate, rounded up: a goal is reached on a day, not on part of
 * one. Integer arithmetic only (Law 3): the day count is a ratio of two
 * amounts and never passes through a `Double`.
 */
public object GoalProjection {

    public const val WINDOW_DAYS: Int = 90
    public const val MINIMUM_HISTORY_DAYS: Int = 14
    private const val DAYS_PER_MONTH = 30

    /**
     * @param historyStart the first day of the goal's history: its creation,
     *   its linked category's `countFrom`, or its earliest contribution,
     *   whichever is earliest.
     * @param addedInWindow what was added from `today - windowDays + 1` to
     *   today, where `windowDays` is [windowDays] of [historyStart] and [today].
     */
    public fun progress(
        goal: SavingsGoal,
        fromHand: Money,
        fromCategory: Money,
        historyStart: Int,
        addedInWindow: Money,
        today: Int,
    ): GoalProgress {
        val saved = fromHand + fromCategory
        val window = windowDays(historyStart, today)
        val outlook = when {
            saved >= goal.target -> GoalOutlook.Reached
            window < MINIMUM_HISTORY_DAYS -> GoalOutlook.TooEarly
            !addedInWindow.isPositive -> GoalOutlook.Stalled(window)
            else -> {
                val finish = today + daysToReach(goal.target - saved, addedInWindow, window)
                GoalOutlook.Projected(
                    monthlyPace = addedInWindow.scaled(DAYS_PER_MONTH, window),
                    windowDays = window,
                    finish = finish,
                    daysLate = goal.targetDate?.let { finish - it },
                )
            }
        }
        return GoalProgress(goal, fromHand, fromCategory, outlook)
    }

    /** Days of history the pace is read over: at most [WINDOW_DAYS], today included. */
    public fun windowDays(historyStart: Int, today: Int): Int =
        (today - historyStart + 1).coerceIn(1, WINDOW_DAYS)

    /** ⌈remaining × window ÷ added⌉, both amounts positive. */
    private fun daysToReach(remaining: Money, added: Money, window: Int): Int {
        val numerator = remaining.minor * window
        val days = numerator / added.minor + if (numerator % added.minor == 0L) 0 else 1
        return days.coerceAtMost(Int.MAX_VALUE.toLong() / 2).toInt()
    }
}
