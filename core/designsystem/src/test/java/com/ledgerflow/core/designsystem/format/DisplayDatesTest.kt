package com.ledgerflow.core.designsystem.format

import com.google.common.truth.Truth.assertThat
import com.ledgerflow.core.model.DateOrder
import java.text.DateFormat
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.time.format.TextStyle
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import org.junit.Test

/**
 * Settings > Currency & region > Date order (V2-PLAN §3.3).
 *
 * The AUTO cases restate, in the test, the code each screen ran before
 * `DisplayDates` existed: if AUTO drifts from it, a user who never opened the
 * setting sees their dates move on upgrade.
 */
class DisplayDatesTest {

    private val date = LocalDate.of(2026, 10, 24)
    private val zone: ZoneId = ZoneOffset.UTC
    private val millis = date.atStartOfDay(zone).toInstant().toEpochMilli() + NOON_MS

    @Test
    fun auto_isWhatEachScreenWroteBefore_inSeveralRegions() {
        listOf(Locale.US, Locale.UK, Locale.forLanguageTag("en-IN"), Locale.forLanguageTag("en-DE")).forEach { locale ->
            // Entry, Review, receipt scan.
            assertThat(DisplayDates.medium(date, locale, DateOrder.AUTO)).isEqualTo(
                DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(locale).format(date),
            )
            // The backup rows and Diagnostics.
            assertThat(DisplayDates.medium(millis, zone, locale, DateOrder.AUTO)).isEqualTo(
                DateFormat.getDateInstance(DateFormat.MEDIUM, locale)
                    .apply { timeZone = TimeZone.getTimeZone(zone) }
                    .format(Date(millis)),
            )
            // The restore list.
            assertThat(DisplayDates.mediumDateTime(millis, zone, locale, DateOrder.AUTO)).isEqualTo(
                DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT, locale)
                    .apply { timeZone = TimeZone.getTimeZone(zone) }
                    .format(Date(millis)),
            )
            // Analytics and Budgets, built by hand, day first.
            val month = date.month.getDisplayName(TextStyle.SHORT, locale)
            assertThat(DisplayDates.dayMonthYear(date, locale, DateOrder.AUTO)).isEqualTo("24 $month 2026")
            assertThat(DisplayDates.dayMonth(date, locale, DateOrder.AUTO)).isEqualTo("24 $month")
        }
        // The Ledger's and Categories' stamps.
        assertThat(DisplayDates.dayMonthPattern(DateOrder.AUTO)).isEqualTo("d MMM")
    }

    @Test
    fun anExplicitOrder_isTheSameOnEveryKindOfScreen() {
        val us = Locale.US
        val expected = mapOf(
            DateOrder.DAY_MONTH_YEAR to listOf("24 Oct 2026", "24 Oct", "d MMM"),
            DateOrder.MONTH_DAY_YEAR to listOf("Oct 24, 2026", "Oct 24", "MMM d"),
            DateOrder.YEAR_MONTH_DAY to listOf("2026 Oct 24", "Oct 24", "MMM d"),
        )
        expected.forEach { (order, want) ->
            val (full, dayMonth, pattern) = want
            assertThat(DisplayDates.medium(date, us, order)).isEqualTo(full)
            assertThat(DisplayDates.medium(millis, zone, us, order)).isEqualTo(full)
            assertThat(DisplayDates.dayMonthYear(date, us, order)).isEqualTo(full)
            assertThat(DisplayDates.dayMonth(date, us, order)).isEqualTo(dayMonth)
            assertThat(DisplayDates.dayMonthPattern(order)).isEqualTo(pattern)
            assertThat(DisplayDates.mediumDateTime(millis, zone, us, order)).startsWith("$full, ")
        }
    }

    @Test
    fun anInstant_isDatedInTheZoneGiven() {
        // 23:30 UTC on the 24th is already the 25th in India.
        val lateUtc = date.atStartOfDay(zone).toInstant().toEpochMilli() + LATE_MS
        val india = ZoneId.of("Asia/Kolkata")
        assertThat(DisplayDates.medium(lateUtc, india, Locale.US, DateOrder.DAY_MONTH_YEAR)).isEqualTo("25 Oct 2026")
        assertThat(DisplayDates.medium(lateUtc, zone, Locale.US, DateOrder.DAY_MONTH_YEAR)).isEqualTo("24 Oct 2026")
    }

    @Test
    fun theDefaults_readTheSetting() {
        val saved = DisplayConventions.current
        try {
            DisplayConventions.current = saved.copy(dateOrder = DateOrder.YEAR_MONTH_DAY)
            assertThat(DisplayDates.dayMonthYear(date, Locale.US)).isEqualTo("2026 Oct 24")
            assertThat(DisplayDates.dayMonthPattern()).isEqualTo("MMM d")
        } finally {
            DisplayConventions.current = saved
        }
    }

    private companion object {
        const val NOON_MS = 12L * 60 * 60 * 1000
        const val LATE_MS = 23L * 60 * 60 * 1000 + 30L * 60 * 1000
    }
}
