package com.ledgerflow.core.domain.usecase

import com.ledgerflow.core.domain.analytics.AnalyticsRange
import com.ledgerflow.core.domain.analytics.AnalyticsRepository
import com.ledgerflow.core.domain.analytics.AnalyticsWindow
import com.ledgerflow.core.domain.analytics.BudgetProgress
import com.ledgerflow.core.domain.analytics.BudgetRepository
import com.ledgerflow.core.domain.analytics.thresholdToAnnounce
import com.ledgerflow.core.model.LedgerType
import javax.inject.Inject

/** A budget that has newly crossed a threshold, and which one. */
public data class BudgetAlert(
    val progress: BudgetProgress,
    val threshold: Int,
)

/**
 * Which budgets have newly crossed an alert threshold (`SPEC.md` §5.7).
 *
 * **Evaluating and recording are one operation.** The caller posts the
 * notification, but the "already announced" mark is written here, because the
 * two coming apart is the failure that matters: an alert posted and not
 * recorded repeats forever, and one recorded but not posted is silently lost.
 * Recording first is the safer half — a missed notification is a nuisance, a
 * notification every time the user approves anything is why people disable
 * them.
 *
 * **Debit only** (§5.7). There are no credit budgets to evaluate.
 */
public class EvaluateBudgetAlertsUseCase @Inject constructor(
    private val analytics: AnalyticsRepository,
    private val budgets: BudgetRepository,
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

        return snapshot.budgets.mapNotNull { progress ->
            val threshold = progress.thresholdToAnnounce() ?: return@mapNotNull null
            budgets.recordAlert(
                id = progress.budget.id,
                threshold = threshold,
                periodStart = progress.periodStart,
            )
            BudgetAlert(progress = progress, threshold = threshold)
        }
    }
}
