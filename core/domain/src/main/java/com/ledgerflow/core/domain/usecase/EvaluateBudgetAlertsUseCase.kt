package com.ledgerflow.core.domain.usecase

import com.ledgerflow.core.domain.analytics.AnalyticsRange
import com.ledgerflow.core.domain.analytics.AnalyticsRepository
import com.ledgerflow.core.domain.analytics.AnalyticsWindow
import com.ledgerflow.core.domain.analytics.BudgetProgress
import com.ledgerflow.core.domain.analytics.BudgetRepository
import com.ledgerflow.core.domain.analytics.OverspendAnnouncements
import com.ledgerflow.core.domain.analytics.isOverspendNews
import com.ledgerflow.core.domain.analytics.thresholdToAnnounce
import com.ledgerflow.core.model.LedgerType
import javax.inject.Inject

/** Something a budget should tell the user now. */
public sealed interface BudgetAlert {
    public val progress: BudgetProgress

    /** Newly crossed a threshold (§5.7's 80% / 100%). */
    public data class ThresholdCrossed(
        override val progress: BudgetProgress,
        val threshold: Int,
    ) : BudgetAlert

    /** On course to go over before the period ends (V2-PLAN §3.4). */
    public data class OnCourseToOverrun(
        override val progress: BudgetProgress,
    ) : BudgetAlert
}

/**
 * What budgets have newly to say (`SPEC.md` §5.7, V2-PLAN §3.4).
 *
 * **Evaluating and recording are one operation.** The caller posts the
 * notification, but the "already announced" mark is written here, because the
 * two coming apart is the failure that matters: an alert posted and not
 * recorded repeats forever, and one recorded but not posted is silently lost.
 * Recording first is the safer half — a missed notification is a nuisance, a
 * notification every time the user approves anything is why people disable
 * them.
 *
 * **At most one alert per budget per evaluation, and a threshold wins.** Both
 * would post under the budget's one notification id, so the second would only
 * replace the first; the threshold is the firmer fact. An overspending warning
 * not announced now is reconsidered at the next evaluation.
 *
 * **Debit only** (§5.7). There are no credit budgets to evaluate.
 */
public class EvaluateBudgetAlertsUseCase @Inject constructor(
    private val analytics: AnalyticsRepository,
    private val budgets: BudgetRepository,
    private val overspend: OverspendAnnouncements,
) {
    public suspend operator fun invoke(today: Int): List<BudgetAlert> {
        val snapshot = analytics.snapshot(
            ledger = LedgerType.DEBIT,
            // The window is irrelevant to budgets — each reads its own period
            // (§5.7) — but the snapshot needs one, and a month is the cheapest
            // that still populates everything else the call returns.
            window = AnalyticsWindow.endingOn(today, AnalyticsRange.MONTH),
            comparePrevious = false,
        )

        return snapshot.budgets.mapNotNull { progress -> alertFor(progress) }
    }

    private suspend fun alertFor(progress: BudgetProgress): BudgetAlert? {
        val id = progress.budget.id
        progress.thresholdToAnnounce()?.let { threshold ->
            budgets.recordAlert(id = id, threshold = threshold, periodStart = progress.periodStart)
            return BudgetAlert.ThresholdCrossed(progress, threshold)
        }
        if (!progress.isOverspendNews(overspend.wasAnnounced(id, progress.periodStart))) return null
        overspend.record(id, progress.periodStart)
        return BudgetAlert.OnCourseToOverrun(progress)
    }
}
