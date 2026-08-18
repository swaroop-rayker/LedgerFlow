package com.ledgerflow.core.common.time

import com.google.common.truth.Truth.assertThat
import java.time.ZoneId
import org.junit.Test

class LocalDatesTest {

    private val kolkata = ZoneId.of("Asia/Kolkata")

    @Test
    fun of_epochStartInUtc_isDayZero() {
        assertThat(LocalDates.of(0L, ZoneId.of("UTC"))).isEqualTo(0)
    }

    /**
     * The reason the column exists rather than being computed from
     * `occurred_at` in SQL: 23:00 in Kolkata is already the next UTC day, and a
     * spend the user made on Tuesday night must not appear on Wednesday.
     */
    @Test
    fun of_lateEveningInIndia_staysOnTheLocalDay() {
        // 2026-08-18T23:30+05:30 == 2026-08-18T18:00Z
        val instant = 1_755_540_000_000L
        val utcDay = LocalDates.of(instant, ZoneId.of("UTC"))

        assertThat(LocalDates.of(instant, kolkata)).isEqualTo(utcDay)

        // ...and half an hour later it is Wednesday locally while UTC is still
        // on Tuesday, which is the case the naive version gets wrong.
        val afterMidnightLocal = instant + 40L * 60L * 1000L
        assertThat(LocalDates.of(afterMidnightLocal, kolkata)).isEqualTo(utcDay + 1)
        assertThat(LocalDates.of(afterMidnightLocal, ZoneId.of("UTC"))).isEqualTo(utcDay)
    }

    @Test
    fun of_isMonotonicAcrossAWeek() {
        val day = 24L * 60L * 60L * 1000L
        val base = 1_755_540_000_000L
        val days = (0..6).map { LocalDates.of(base + it * day, kolkata) }

        assertThat(days).isInOrder()
        assertThat(days.last() - days.first()).isEqualTo(6)
    }
}
