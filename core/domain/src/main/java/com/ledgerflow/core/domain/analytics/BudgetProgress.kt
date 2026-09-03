package com.ledgerflow.core.domain.analytics

import com.ledgerflow.core.model.BudgetPeriod
import com.ledgerflow.core.model.Money

/**
 * A budget as the user set it (`SPEC.md` §5.7).
 *
 * **No `ledger` field, and that is the whole of "debit only".** §6.1's DDL gives
 * `budget` no ledger column, so there is no such thing as a credit budget to
 * express; the obligation lands entirely on the reads, where every progress
 * query binds `ledger = 'DEBIT'` and `LedgerIsolationTest` fails the build if
 * one forgets.
 */
public data class Budget(
    val id: String,
    val categoryId: String,
    val subcategoryId: String?,
    val period: BudgetPeriod,
    val amount: Money,
    val startDate: Int,
    val rolloverEnabled: Boolean,
    val alertThresholds: List<Int>,
)

/**
 * A budget against what has actually been spent in its current period (A7).
 *
 * [projectedSpend] is the burn-rate forecast §5.6 asks for: today's pace carried
 * to the end of the period. It is a projection and the UI says so — a user who
 * front-loads a month's groceries on the first is not on course for 30× that.
 */
public data class BudgetProgress(
    val budget: Budget,
    val categoryName: String,
    val categoryColorArgb: Int?,
    val spent: Money,
    val periodStart: Int,
    val periodEnd: Int,
    val daysElapsed: Int,
    val projectedSpend: Money,
) {
    /**
     * Spend as a fraction of the budget. A chart coordinate, not money — the
     * amounts either side of the division stay `Long` (Law 3).
     */
    public val fraction: Float
        get() = if (budget.amount.minor <= 0L) {
            0f
        } else {
            (spent.minor.toDouble() / budget.amount.minor.toDouble()).toFloat()
        }

    /** The highest threshold this budget has crossed, or null. */
    public fun crossedThreshold(): Int? = budget.alertThresholds
        .sortedDescending()
        .firstOrNull { threshold -> spent.minor * PERCENT >= budget.amount.minor * threshold }

    /** True when the *projection* overruns even though today's spend does not. */
    public val onCourseToOverrun: Boolean
        get() = spent.minor <= budget.amount.minor && projectedSpend.minor > budget.amount.minor
}

/**
 * Budget period arithmetic (`SPEC.md` §5.7).
 *
 * Days-since-epoch throughout, matching `local_date` — the same reason §6.1
 * carries that column at all, so a period boundary never needs timezone maths.
 *
 * **Periods repeat from `start_date`, they do not snap to a calendar.** A
 * monthly budget started on the 10th runs the 10th to the 9th, because that is
 * what the user chose; snapping it to the 1st would silently move the period
 * they set. The cost is that "monthly" is 30 days rather than a calendar month,
 * which is the same trade `AnalyticsRange` makes and for the same reason.
 */
public object BudgetPeriods {

    /**
     * A "month" is 30 days and a "quarter" 91, because periods repeat from the
     * budget's own `start_date` rather than snapping to a calendar — snapping
     * would silently move the period the user chose. Same trade
     * `AnalyticsRange` makes, for the same reason: no timezone maths in SQL.
     */
    public fun lengthInDays(period: BudgetPeriod): Int = when (period) {
        BudgetPeriod.WEEKLY -> WEEK_DAYS
        BudgetPeriod.MONTHLY -> MONTH_DAYS
        BudgetPeriod.QUARTERLY -> QUARTER_DAYS
        BudgetPeriod.YEARLY -> YEAR_DAYS
    }

    private const val WEEK_DAYS = 7
    private const val MONTH_DAYS = 30
    private const val QUARTER_DAYS = 91
    private const val YEAR_DAYS = 365

    /**
     * The occurrence of [budget]'s period containing [today].
     *
     * A budget whose start date is in the future has not begun; its first period
     * is returned rather than a period in the past, so the UI shows "not started"
     * against real dates instead of inventing a window that never existed.
     */
    public fun currentPeriod(budget: Budget, today: Int): IntRange {
        val length = lengthInDays(budget.period)
        if (today < budget.startDate) {
            return budget.startDate until (budget.startDate + length)
        }
        val elapsed = today - budget.startDate
        val start = budget.startDate + (elapsed / length) * length
        return start until (start + length)
    }

    /**
     * Today's pace carried to the end of the period.
     *
     * Integer arithmetic: `spent * periodLength / daysElapsed`, never a `Double`
     * round-trip, because the result is money (Law 3). On the period's first day
     * `daysElapsed` is 1, so a single large purchase projects to the whole
     * period — which is arithmetically right and is exactly why the UI labels
     * this a projection rather than a forecast to be trusted.
     */
    public fun project(spent: Money, daysElapsed: Int, periodLength: Int): Money {
        if (daysElapsed <= 0) return spent
        val days = daysElapsed.coerceAtMost(periodLength)
        return Money(spent.minor * periodLength / days)
    }
}

/** Thresholds are percentages (§5.7's `80,100`). */
private const val PERCENT = 100L
