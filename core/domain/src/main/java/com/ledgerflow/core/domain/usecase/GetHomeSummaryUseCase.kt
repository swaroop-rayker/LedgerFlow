package com.ledgerflow.core.domain.usecase

import com.ledgerflow.core.domain.analytics.AnalyticsRepository
import com.ledgerflow.core.domain.analytics.BookMonth
import com.ledgerflow.core.domain.analytics.HomeSummary
import com.ledgerflow.core.domain.analytics.MonthToDate
import com.ledgerflow.core.domain.analytics.RecurringDetection
import com.ledgerflow.core.model.LedgerType
import com.ledgerflow.core.model.Money
import javax.inject.Inject

/**
 * Home's figures for the month containing [today] (V2-PLAN BUG-C).
 *
 * **One snapshot and three totals, not two snapshots.** The debit snapshot
 * carries everything Home shows about spending (budgets, recurring charges,
 * coverage, the day's total). The credit book contributes one figure and its
 * comparison, and a credit snapshot would run recurring detection, parser gaps
 * and both books' parallel series to produce it.
 *
 * Each read names its own ledger and fills only that book's [BookMonth], so a
 * read pointed at the wrong book shows up as a wrong figure in a test rather
 * than as a plausible one on screen (Law 2).
 */
public class GetHomeSummaryUseCase @Inject constructor(
    private val analytics: AnalyticsRepository,
) {
    public suspend operator fun invoke(today: Int): HomeSummary {
        val window = MonthToDate.window(today)
        val previous = MonthToDate.previous(today)
        val runwayThrough = MonthToDate.lastDay(today)

        val debit = analytics.snapshot(LedgerType.DEBIT, window, comparePrevious = false)

        return HomeSummary(
            today = today,
            window = window,
            previousWindow = previous,
            spending = BookMonth(
                ledger = LedgerType.DEBIT,
                total = debit.total,
                previousTotal = analytics.total(LedgerType.DEBIT, previous),
            ),
            income = BookMonth(
                ledger = LedgerType.CREDIT,
                total = analytics.total(LedgerType.CREDIT, window),
                previousTotal = analytics.total(LedgerType.CREDIT, previous),
            ),
            spentToday = debit.days.firstOrNull { it.localDate == today }?.amount ?: Money(0L),
            // Highest fraction first. A ratio, not money (Law 3 is about the
            // amounts either side of it, which stay `Long`).
            budgets = debit.budgets
                .sortedByDescending { it.fraction }
                .take(HomeSummary.HOME_BUDGETS),
            // Not `debit.runway`: the snapshot projects its runway a range's
            // nominal length past the window, and a custom window's nominal
            // length is a placeholder. Home's runway ends with the month.
            runway = RecurringDetection.runway(
                detected = debit.recurring,
                today = today,
                through = runwayThrough,
            ),
            runwayThrough = runwayThrough,
            captureCoverage = debit.captureCoverage,
        )
    }
}
