package com.ledgerflow.core.domain.analytics

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Pan and zoom, tested as **window arithmetic** (ADR-0005).
 *
 * This is the whole of what a gesture does in this app. The chart holds no
 * series to transform — §11 forbids it having one — so panning and zooming are
 * two functions over a pair of dates, and the query that follows is what
 * actually redraws the screen. That makes the behaviour testable off a device,
 * which is the property the arrangement was chosen for.
 */
class AnalyticsViewportTest {

    /**
     * **A pan preserves the span.** The user asked to look elsewhere, not at
     * more or less; a shift that also stretched the window would silently
     * change the bucket width under them.
     */
    @Test
    fun panningKeepsTheSpanAndMovesBothEnds() {
        val month = AnalyticsWindow.endingOn(TODAY, AnalyticsRange.MONTH)

        val panned = month.pannedBy(-0.5f)

        assertThat(panned.spanDays).isEqualTo(month.spanDays)
        assertThat(panned.from).isEqualTo(month.from - 15)
        assertThat(panned.to).isEqualTo(month.to - 15)
    }

    @Test
    fun panningForwardMovesTheWindowLater() {
        val month = AnalyticsWindow.endingOn(TODAY, AnalyticsRange.MONTH)

        val panned = month.pannedBy(0.25f)

        assertThat(panned.from).isGreaterThan(month.from)
        assertThat(panned.spanDays).isEqualTo(month.spanDays)
    }

    /**
     * **A panned window is CUSTOM.**
     *
     * Leaving the range as MONTH would leave the "Month" chip selected while
     * the chart showed some other month — the screen would be claiming a range
     * it is not displaying, which is worse than showing no chip at all.
     */
    @Test
    fun aPannedWindowIsNoLongerThePresetRange() {
        val month = AnalyticsWindow.endingOn(TODAY, AnalyticsRange.MONTH)

        assertThat(month.pannedBy(-0.5f).range).isEqualTo(AnalyticsRange.CUSTOM)
        assertThat(month.zoomedBy(2f).range).isEqualTo(AnalyticsRange.CUSTOM)
    }

    /**
     * A shift too small to move a whole day is not a re-query.
     *
     * The composable already drops sub-threshold drags; this is the second
     * half of it, because a 3-day window panned by 10% rounds to zero days and
     * issuing a query for the identical window would flicker for nothing.
     */
    @Test
    fun aPanTooSmallToMoveADayChangesNothing() {
        val window = AnalyticsWindow.custom(TODAY - 2, TODAY)

        val panned = window.pannedBy(0.1f)

        assertThat(panned).isEqualTo(window)
    }

    /** Zooming out widens about the centre, so what was in view stays in view. */
    @Test
    fun zoomingOutWidensAboutTheCentre() {
        val window = AnalyticsWindow.custom(100, 129)

        val zoomed = window.zoomedBy(2f)

        assertThat(zoomed.spanDays).isEqualTo(60)
        assertThat(zoomed.from).isLessThan(window.from)
        assertThat(zoomed.to).isGreaterThan(window.to)
    }

    @Test
    fun zoomingInNarrowsAboutTheCentre() {
        val window = AnalyticsWindow.custom(100, 199)

        val zoomed = window.zoomedBy(0.5f)

        assertThat(zoomed.spanDays).isEqualTo(50)
        assertThat(zoomed.from).isAtLeast(window.from)
        assertThat(zoomed.to).isAtMost(window.to)
    }

    /**
     * **A window of zero days is not reachable.**
     *
     * It would produce a `BETWEEN` no row can satisfy, and the screen would
     * show an empty chart — indistinguishable from "you spent nothing" rather
     * than "you cannot zoom further".
     */
    @Test
    fun zoomingInStopsAtOneDay() {
        var window = AnalyticsWindow.custom(TODAY - 3, TODAY)

        repeat(10) { window = window.zoomedBy(0.1f) }

        assertThat(window.spanDays).isEqualTo(1)
    }

    /**
     * **Zooming out stops at 5Y**, the largest span §11 sets a budget for.
     *
     * Each pinch multiplies, so without a ceiling a handful of them takes a
     * month past a century, and the performance target stops meaning anything.
     */
    @Test
    fun zoomingOutStopsAtTheFiveYearSpan() {
        var window = AnalyticsWindow.endingOn(TODAY, AnalyticsRange.MONTH)

        repeat(20) { window = window.zoomedBy(2f) }

        assertThat(window.spanDays).isEqualTo(AnalyticsWindow.MAX_SPAN_DAYS)
    }

    /**
     * **The bucket count survives any gesture** — §11's rule that a chart never
     * receives more points than it has horizontal pixels.
     *
     * This is the one property a pan or zoom could break invisibly: the query
     * would still return, the chart would still draw, and it would be handing
     * a phone thousands of columns to fit in a few hundred pixels.
     */
    @Test
    fun noReachableWindowExceedsTheBucketCap() {
        var window = AnalyticsWindow.endingOn(TODAY, AnalyticsRange.DAY)

        repeat(30) {
            window = window.zoomedBy(2f).pannedBy(-0.5f)
            assertThat(window.bucketCount).isAtMost(AnalyticsWindow.MAX_BUCKETS)
            assertThat(window.bucketDays).isAtLeast(1)
        }
    }

    /** A pan is reversible — the user can get back to where they were. */
    @Test
    fun panningBackAndForthReturnsToTheSameWindow() {
        val month = AnalyticsWindow.endingOn(TODAY, AnalyticsRange.MONTH)

        val there = month.pannedBy(-0.5f)
        val back = there.pannedBy(0.5f)

        assertThat(back.from).isEqualTo(month.from)
        assertThat(back.to).isEqualTo(month.to)
    }

    // ── A typed period, rather than two picked dates ───────────────────────

    /**
     * **A typed month is the calendar one.**
     *
     * `AnalyticsRange.MONTH` is 30 days because a bucket divisor has to be
     * fixed; somebody typing "1 month" means the calendar month, and August has
     * 31 days. Getting this wrong is invisible — the chart still draws — so it
     * is asserted against a named date rather than a formula.
     */
    @Test
    fun oneTypedMonth_isTheCalendarMonth_inclusiveOfToday() {
        // 4 September 2026.
        val today = java.time.LocalDate.of(2026, 9, 4).toEpochDay().toInt()

        val window = requireNotNull(AnalyticsWindow.lastPeriod(today, months = 1))

        assertThat(java.time.LocalDate.ofEpochDay(window.from.toLong()))
            .isEqualTo(java.time.LocalDate.of(2026, 8, 5))
        assertThat(java.time.LocalDate.ofEpochDay(window.to.toLong()))
            .isEqualTo(java.time.LocalDate.of(2026, 9, 4))
        assertThat(window.spanDays).isEqualTo(31)
    }

    /** February is short, and the arithmetic has to know that. */
    @Test
    fun oneTypedMonth_acrossFebruary_isShorter() {
        val today = java.time.LocalDate.of(2026, 3, 10).toEpochDay().toInt()

        val window = requireNotNull(AnalyticsWindow.lastPeriod(today, months = 1))

        assertThat(java.time.LocalDate.ofEpochDay(window.from.toLong()))
            .isEqualTo(java.time.LocalDate.of(2026, 2, 11))
        assertThat(window.spanDays).isEqualTo(28)
    }

    /** The three units combine, which is the point of typing one. */
    @Test
    fun yearsMonthsAndDaysCombineIntoOneWindow() {
        val today = java.time.LocalDate.of(2026, 9, 4).toEpochDay().toInt()

        val window = requireNotNull(
            AnalyticsWindow.lastPeriod(today, years = 1, months = 2, days = 10),
        )

        // 4 Sept 2026 less a year is 4 Sept 2025, less two months is 4 July,
        // less ten days is 24 June -- and the window opens the day after, so
        // that today is the last of exactly that many.
        assertThat(java.time.LocalDate.ofEpochDay(window.from.toLong()))
            .isEqualTo(java.time.LocalDate.of(2025, 6, 25))
        assertThat(window.range).isEqualTo(AnalyticsRange.CUSTOM)
    }

    @Test
    fun aTypedPeriodOfSevenDaysMatchesTheWeekPreset() {
        val week = AnalyticsWindow.endingOn(TODAY, AnalyticsRange.WEEK)

        val typed = requireNotNull(AnalyticsWindow.lastPeriod(TODAY, days = 7))

        assertThat(typed.from).isEqualTo(week.from)
        assertThat(typed.to).isEqualTo(week.to)
    }

    /**
     * **An unfilled form is not a window.**
     *
     * Returning a zero-length range would render as an empty chart, which reads
     * as "you spent nothing" rather than "you have not typed anything yet".
     */
    @Test
    fun aPeriodOfNothingIsNotAWindow() {
        assertThat(AnalyticsWindow.lastPeriod(TODAY)).isNull()
        assertThat(AnalyticsWindow.lastPeriod(TODAY, years = 0, months = 0, days = 0)).isNull()
        assertThat(AnalyticsWindow.lastPeriod(TODAY, days = -5)).isNull()
    }

    /** Clamped at the same 5Y ceiling a pinch is, so there is one maximum. */
    @Test
    fun aTypedPeriodIsCappedAtTheFiveYearSpan() {
        val window = requireNotNull(AnalyticsWindow.lastPeriod(TODAY, years = 20))

        assertThat(window.spanDays).isEqualTo(AnalyticsWindow.MAX_SPAN_DAYS)
        assertThat(window.to).isEqualTo(TODAY)
    }

    private companion object {
        /** 2026-09-03, the day this was written. Any epoch day would do. */
        const val TODAY = 20_699
    }
}
