package com.ledgerflow.core.domain.analytics

import com.ledgerflow.core.model.BudgetPeriod

/**
 * Overspending alerts (`docs/V2-PLAN.md` §3.4; owner 2026-10-24).
 *
 * "At this pace you will go over": today's spend carried linearly to the end
 * of the period ([BudgetProgress.projectedSpend], the burn rate §5.6 already
 * draws) past the budget, rollover included. A warning *before* the 80% and
 * 100% alerts, about a direction rather than a level.
 *
 * Whether a warning is news, given whether one was already announced for this
 * budget in this period ([alreadyAnnounced]). All of these hold:
 *
 * - **Far enough into the period** ([minimumDays]): day 7, or day 2 of a weekly
 *   budget. On day one a single purchase projects to the whole period, which
 *   is arithmetic, not a warning.
 * - **On course to overrun and not over yet** ([BudgetProgress.onCourseToOverrun]).
 * - **Not past the top threshold.** Once 100% has been reached, or announced
 *   in this period, "you will go over" is old news.
 * - **Once per budget per period.**
 *
 * Debit only, like every budget (§5.7).
 */
public fun BudgetProgress.isOverspendNews(alreadyAnnounced: Boolean): Boolean {
    if (alreadyAnnounced) return false
    if (daysElapsed < minimumDays(budget.period)) return false
    if (!onCourseToOverrun) return false
    if ((crossedThreshold() ?: 0) >= FULL) return false
    val fullAnnouncedThisPeriod = budget.alertPeriodStart == periodStart && budget.lastAlertedThreshold >= FULL
    return !fullAnnouncedThisPeriod
}

/** The first day of a period on which a projection is worth announcing. */
public fun minimumDays(period: BudgetPeriod): Int = when (period) {
    BudgetPeriod.WEEKLY -> WEEKLY_MINIMUM_DAYS
    BudgetPeriod.MONTHLY, BudgetPeriod.QUARTERLY, BudgetPeriod.YEARLY -> MINIMUM_DAYS
}

/**
 * Which budgets have been warned about, and for which period.
 *
 * Kept in `:core:datastore` (owner, 2026-10-24: no schema change for this),
 * as opaque budget ids and period-start days only. A restore onto a new phone
 * does not carry it, so at worst one warning repeats there.
 */
public interface OverspendAnnouncements {

    public suspend fun wasAnnounced(budgetId: String, periodStart: Int): Boolean

    /** Replaces any older period's record for [budgetId]. */
    public suspend fun record(budgetId: String, periodStart: Int)
}

private const val FULL = 100
private const val MINIMUM_DAYS = 7
private const val WEEKLY_MINIMUM_DAYS = 2
