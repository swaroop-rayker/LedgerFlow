package com.ledgerflow.core.domain.recurring

import com.google.common.truth.Truth.assertThat
import java.time.LocalDate
import org.junit.Test

/** When an item is next due (V2-PLAN §4.2; owner 2026-11-09 on the 31st). */
class RecurringScheduleTest {

    private fun day(y: Int, m: Int, d: Int) = LocalDate.of(y, m, d).toEpochDay().toInt()

    private fun next(cadence: Cadence, anchor: Int, due: Int, everyDays: Int? = null) =
        LocalDate.ofEpochDay(RecurringSchedule.following(cadence, everyDays, anchor, due).toLong())

    @Test
    fun monthly_onThe31st_clampsToShortMonths_andReturns() {
        val anchor = day(2027, 1, 31)

        val feb = next(Cadence.MONTHLY, anchor, anchor)
        val mar = next(Cadence.MONTHLY, anchor, feb.toEpochDay().toInt())
        val apr = next(Cadence.MONTHLY, anchor, mar.toEpochDay().toInt())
        val may = next(Cadence.MONTHLY, anchor, apr.toEpochDay().toInt())

        assertThat(feb).isEqualTo(LocalDate.of(2027, 2, 28))
        assertThat(mar).isEqualTo(LocalDate.of(2027, 3, 31))
        assertThat(apr).isEqualTo(LocalDate.of(2027, 4, 30))
        assertThat(may).isEqualTo(LocalDate.of(2027, 5, 31))
    }

    @Test
    fun monthly_inALeapYear_landsOnThe29th() {
        val anchor = day(2028, 1, 31)

        assertThat(next(Cadence.MONTHLY, anchor, anchor)).isEqualTo(LocalDate.of(2028, 2, 29))
    }

    @Test
    fun quarterlyAndYearly_stepFromTheAnchor() {
        val anchor = day(2026, 11, 30)

        assertThat(next(Cadence.QUARTERLY, anchor, anchor)).isEqualTo(LocalDate.of(2027, 2, 28))
        assertThat(next(Cadence.QUARTERLY, anchor, day(2027, 2, 28))).isEqualTo(LocalDate.of(2027, 5, 30))
        assertThat(next(Cadence.YEARLY, day(2028, 2, 29), day(2028, 2, 29))).isEqualTo(LocalDate.of(2029, 2, 28))
    }

    @Test
    fun weeklyAndEveryNDays_addDays() {
        val due = day(2026, 11, 10)

        assertThat(next(Cadence.WEEKLY, due, due)).isEqualTo(LocalDate.of(2026, 11, 17))
        assertThat(next(Cadence.EVERY_N_DAYS, due, due, everyDays = 28)).isEqualTo(LocalDate.of(2026, 12, 8))
    }

    /** A due date that drifted (an edit, a catch-up) still lands on the anchor's pattern. */
    @Test
    fun monthly_fromAnyDayInBetween_findsTheNextOnThePattern() {
        val anchor = day(2026, 1, 1)

        assertThat(next(Cadence.MONTHLY, anchor, day(2026, 11, 15))).isEqualTo(LocalDate.of(2026, 12, 1))
    }
}
