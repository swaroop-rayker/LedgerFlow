package com.ledgerflow.core.domain.usecase

import com.ledgerflow.core.domain.analytics.AnalyticsFilters
import com.ledgerflow.core.domain.analytics.AnalyticsRepository
import com.ledgerflow.core.domain.analytics.AnalyticsSnapshot
import com.ledgerflow.core.domain.analytics.AnalyticsWindow
import com.ledgerflow.core.model.LedgerType
import javax.inject.Inject

/**
 * A1-A5's data for one window (SPEC.md §5.6).
 *
 * One use case rather than five, because the five views share a window: issuing
 * them separately would let the screen render a donut from one range beside a
 * bar chart from another while both were still settling.
 */
public class GetAnalyticsSnapshotUseCase @Inject constructor(
    private val analytics: AnalyticsRepository,
) {
    public suspend operator fun invoke(
        ledger: LedgerType,
        window: AnalyticsWindow,
        comparePrevious: Boolean = true,
        filters: AnalyticsFilters = AnalyticsFilters.None,
    ): AnalyticsSnapshot = analytics.snapshot(ledger, window, comparePrevious, filters)
}
