package com.ledgerflow.core.domain.receipts

import com.google.common.truth.Truth.assertThat
import java.time.LocalDateTime
import java.time.ZoneId
import org.junit.Test

/**
 * The Receipts page's windows (V2-5): rolling days counted back from today, in
 * the phone's zone, from the start of the first day. Not calendar weeks or
 * months, for `RecencyBucket`'s reason.
 */
class ReceiptPeriodTest {

    private val kolkata = ZoneId.of("Asia/Kolkata")

    /** 8 Nov 2026, 15:30 in Kolkata. */
    private val now = LocalDateTime.of(2026, 11, 8, 15, 30).atZone(kolkata).toInstant().toEpochMilli()

    private fun startOf(year: Int, month: Int, day: Int): Long =
        LocalDateTime.of(year, month, day, 0, 0).atZone(kolkata).toInstant().toEpochMilli()

    @Test
    fun today_startsAtLocalMidnight() {
        assertThat(ReceiptPeriod.Today.since(now, kolkata)).isEqualTo(startOf(2026, 11, 8))
    }

    @Test
    fun week_isTodayAndTheSixDaysBefore() {
        assertThat(ReceiptPeriod.Week.since(now, kolkata)).isEqualTo(startOf(2026, 11, 2))
    }

    @Test
    fun month_isTodayAndThe29DaysBefore_acrossAMonthBoundary() {
        assertThat(ReceiptPeriod.Month.since(now, kolkata)).isEqualTo(startOf(2026, 10, 10))
    }

    @Test
    fun all_includesEverything() {
        assertThat(ReceiptPeriod.All.since(now, kolkata)).isEqualTo(0L)
    }

    /** Just after midnight is still today, in the phone's zone and not in UTC. */
    @Test
    fun theZoneDecidesWhichDayItIs() {
        val earlyMorning = LocalDateTime.of(2026, 11, 8, 0, 10).atZone(kolkata).toInstant().toEpochMilli()

        assertThat(ReceiptPeriod.Today.since(earlyMorning, kolkata)).isEqualTo(startOf(2026, 11, 8))
    }
}
