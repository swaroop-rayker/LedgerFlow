package com.ledgerflow.core.testing.analytics

import com.ledgerflow.core.domain.analytics.AnalyticsFilters
import com.ledgerflow.core.domain.analytics.AnalyticsRepository
import com.ledgerflow.core.domain.analytics.AnalyticsSnapshot
import com.ledgerflow.core.domain.analytics.AnalyticsWindow
import com.ledgerflow.core.model.LedgerType
import com.ledgerflow.core.model.Money

/**
 * A scripted analytics port, per book.
 *
 * Keyed by [LedgerType] for the reason `FakeLedgerRepository` is: a fake that
 * answered every book from one value would pass a ViewModel test whose read
 * had been pointed at the wrong ledger (Law 2). Unscripted reads answer empty
 * and zero, which is what an empty vault answers.
 */
public class FakeAnalyticsRepository : AnalyticsRepository {

    /** The snapshot each book answers, given the window it was asked for. */
    public val snapshots: MutableMap<LedgerType, (AnalyticsWindow) -> AnalyticsSnapshot> = mutableMapOf()

    /** The total each book answers, given the window it was asked for. */
    public val totals: MutableMap<LedgerType, (AnalyticsWindow) -> Money> = mutableMapOf()

    /** Every snapshot request, in order. */
    public val snapshotReads: MutableList<Pair<LedgerType, AnalyticsWindow>> = mutableListOf()

    override suspend fun snapshot(
        ledger: LedgerType,
        window: AnalyticsWindow,
        comparePrevious: Boolean,
        filters: AnalyticsFilters,
    ): AnalyticsSnapshot {
        snapshotReads += ledger to window
        return snapshots[ledger]?.invoke(window) ?: emptySnapshot(ledger, window)
    }

    override suspend fun total(ledger: LedgerType, window: AnalyticsWindow): Money =
        totals[ledger]?.invoke(window) ?: Money(0L)
}

/** A snapshot with nothing in it, for [ledger] over [window]. */
public fun emptySnapshot(ledger: LedgerType, window: AnalyticsWindow): AnalyticsSnapshot =
    AnalyticsSnapshot(
        ledger = ledger,
        window = window,
        total = Money(0L),
        previousTotal = null,
        transactionCount = 0,
        timeBuckets = emptyList(),
        categories = emptyList(),
        subcategories = emptyMap(),
        merchants = emptyList(),
        paymentMethods = emptyList(),
    )
