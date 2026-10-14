package com.ledgerflow.core.domain.analytics

import com.ledgerflow.core.model.LedgerType
import com.ledgerflow.core.model.Money
import java.time.LocalDate

/**
 * One book's month so far, beside the same days of the month before (V2-PLAN
 * BUG-C, owner 2026-10-14).
 *
 * **Per book, and never anything else.** Home shows *Spent* and *Received* as
 * two figures; this type is the reason it cannot show a third. There is no
 * field here, and none on [HomeSummary], that holds a figure drawn from both
 * ledgers (Law 2).
 */
public data class BookMonth(
    val ledger: LedgerType,
    val total: Money,
    val previousTotal: Money,
)

/**
 * Home's figures (V2-PLAN BUG-C): the calendar month to date, debit-side
 * extras, and nothing that nets one book against the other.
 *
 * Everything except [income] comes from the **debit** book. Budgets are
 * debit-only by construction (§5.7), and Home's runway, coverage and "spent
 * today" are about spending.
 */
public data class HomeSummary(
    val today: Int,
    val window: AnalyticsWindow,
    val previousWindow: AnalyticsWindow,
    val spending: BookMonth,
    val income: BookMonth,
    val spentToday: Money,
    /** The budgets closest to their limit, most spent first; at most [HOME_BUDGETS]. */
    val budgets: List<BudgetProgress>,
    /** Detected charges expected from [today] through [runwayThrough], soonest first. */
    val runway: List<RecurringMerchant>,
    val runwayThrough: Int,
    val captureCoverage: CaptureCoverage,
) {
    /** What [runway] adds up to. Law 3: `Long`. */
    public val runwayTotal: Money get() = Money(runway.sumOf { it.typicalAmount.minor })

    public companion object {
        /** Home is orientation; the Budgets screen has the rest. */
        public const val HOME_BUDGETS: Int = 3
    }
}

/**
 * The calendar month to date, and the stretch of last month it is compared with.
 *
 * **Calendar, not [AnalyticsRange.MONTH].** That range is a rolling 30 days,
 * fine for a chart whose axis states its range; Home's card says "This month",
 * and a reader takes that to mean since the 1st (owner, 2026-10-14). The windows
 * are built with [AnalyticsWindow.custom], so nothing reads the placeholder
 * constants a custom range carries.
 *
 * **"The same days" is clamped to the shorter month.** On 31 March the
 * comparison is 1 to 28 February, not a window that runs into March: a
 * comparison that overlapped the month it compares against would count some
 * days twice.
 */
public object MonthToDate {

    /** The 1st of [today]'s month through [today], inclusive. */
    public fun window(today: Int): AnalyticsWindow {
        val date = LocalDate.ofEpochDay(today.toLong())
        return AnalyticsWindow.custom(date.withDayOfMonth(1).toEpochDay().toInt(), today)
    }

    /** The 1st of last month through the same day of it, or its last day if shorter. */
    public fun previous(today: Int): AnalyticsWindow {
        val date = LocalDate.ofEpochDay(today.toLong())
        val lastMonth = date.minusMonths(1)
        // `minusMonths` already clamps the day (31 March -> 28 February).
        return AnalyticsWindow.custom(
            lastMonth.withDayOfMonth(1).toEpochDay().toInt(),
            lastMonth.toEpochDay().toInt(),
        )
    }

    /** The last day of [today]'s month. */
    public fun lastDay(today: Int): Int {
        val date = LocalDate.ofEpochDay(today.toLong())
        return date.withDayOfMonth(date.lengthOfMonth()).toEpochDay().toInt()
    }
}
