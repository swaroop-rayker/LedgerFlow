package com.ledgerflow.feature.analytics

import com.google.common.truth.Truth.assertThat
import com.ledgerflow.core.domain.analytics.AnalyticsRange
import com.ledgerflow.core.domain.analytics.AnalyticsSnapshot
import com.ledgerflow.core.domain.analytics.AnalyticsWindow
import com.ledgerflow.core.domain.analytics.TimeBucket
import com.ledgerflow.core.model.LedgerType
import com.ledgerflow.core.model.Money
import org.junit.Test

/**
 * Spend over time > Total (step F6): the area chart's points are the window's
 * running total, bucket by bucket, ending at the window's own total.
 */
class AnalyticsRunningTotalTest {

    @Test
    fun eachPoint_isWhatHadBeenSpentByTheEndOfItsBucket() {
        val amounts = listOf(300_00L, 0L, 1_240_50L, 0L, 60_00L)
        val snapshot = AnalyticsSnapshot(
            ledger = LedgerType.DEBIT,
            window = AnalyticsWindow.endingOn(START + amounts.size - 1, AnalyticsRange.WEEK),
            total = Money(amounts.sum()),
            previousTotal = null,
            transactionCount = 3,
            timeBuckets = amounts.mapIndexed { i, minor ->
                TimeBucket(
                    bucket = i,
                    startDate = START + i,
                    endDate = START + i,
                    amount = Money(minor),
                    byCategory = emptyList(),
                )
            },
            categories = emptyList(),
            subcategories = emptyMap(),
            merchants = emptyList(),
            paymentMethods = emptyList(),
        )

        val points = snapshot.toRunningTotal()

        assertThat(points.map { it.value }).containsExactly(300_00L, 300_00L, 1_540_50L, 1_540_50L, 1_600_50L).inOrder()
        // It ends where the window's total is: the pill and the card above agree.
        assertThat(points.last().value).isEqualTo(snapshot.total.minor)
    }

    private companion object {
        const val START = 20_667
    }
}
