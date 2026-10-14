package com.ledgerflow.core.domain.analytics

import com.google.common.truth.Truth.assertThat
import java.time.LocalDate
import org.junit.Test

/**
 * Home's month-to-date windows (V2-PLAN BUG-C, owner 2026-10-14).
 *
 * Calendar arithmetic with a correct answer, so it is pinned here rather than
 * read off a golden: "1–14 Sep" against "1–14 Oct" looks right in a screenshot
 * whether or not the clamp at the end of a short month works.
 */
class MonthToDateTest {

    @Test
    fun window_runsFromTheFirstThroughToday() {
        val window = MonthToDate.window(day(2026, 10, 14))

        assertThat(window.from).isEqualTo(day(2026, 10, 1))
        assertThat(window.to).isEqualTo(day(2026, 10, 14))
        // Built as CUSTOM, so nothing downstream reads MONTH's rolling 30 days.
        assertThat(window.range).isEqualTo(AnalyticsRange.CUSTOM)
        assertThat(window.bucketDays).isEqualTo(1)
    }

    @Test
    fun previous_isTheSameDaysOfLastMonth() {
        val previous = MonthToDate.previous(day(2026, 10, 14))

        assertThat(previous.from).isEqualTo(day(2026, 9, 1))
        assertThat(previous.to).isEqualTo(day(2026, 9, 14))
    }

    /**
     * The 31st has no counterpart in a 30-day month. The comparison stops at
     * that month's last day rather than running into the month it is compared
     * with, which would count those days on both sides.
     */
    @Test
    fun previous_onThe31st_stopsAtTheShorterMonthsLastDay() {
        val previous = MonthToDate.previous(day(2026, 10, 31))

        assertThat(previous.from).isEqualTo(day(2026, 9, 1))
        assertThat(previous.to).isEqualTo(day(2026, 9, 30))
    }

    @Test
    fun previous_inMarch_clampsToFebruary() {
        assertThat(MonthToDate.previous(day(2027, 3, 30)).to).isEqualTo(day(2027, 2, 28))
        // A leap year has the 29th, and uses it.
        assertThat(MonthToDate.previous(day(2028, 3, 30)).to).isEqualTo(day(2028, 2, 29))
    }

    @Test
    fun previous_inJanuary_isDecemberOfTheYearBefore() {
        val previous = MonthToDate.previous(day(2027, 1, 15))

        assertThat(previous.from).isEqualTo(day(2026, 12, 1))
        assertThat(previous.to).isEqualTo(day(2026, 12, 15))
    }

    /** The 1st is a one-day month so far, compared with last month's 1st. */
    @Test
    fun onTheFirst_bothWindowsAreOneDay() {
        val today = day(2026, 11, 1)

        assertThat(MonthToDate.window(today).spanDays).isEqualTo(1)
        assertThat(MonthToDate.previous(today).from).isEqualTo(day(2026, 10, 1))
        assertThat(MonthToDate.previous(today).spanDays).isEqualTo(1)
    }

    @Test
    fun lastDay_isTheEndOfTodaysMonth() {
        assertThat(MonthToDate.lastDay(day(2026, 10, 14))).isEqualTo(day(2026, 10, 31))
        assertThat(MonthToDate.lastDay(day(2026, 9, 1))).isEqualTo(day(2026, 9, 30))
        assertThat(MonthToDate.lastDay(day(2027, 2, 10))).isEqualTo(day(2027, 2, 28))
        assertThat(MonthToDate.lastDay(day(2028, 2, 10))).isEqualTo(day(2028, 2, 29))
    }

    private fun day(year: Int, month: Int, dayOfMonth: Int): Int =
        LocalDate.of(year, month, dayOfMonth).toEpochDay().toInt()
}
