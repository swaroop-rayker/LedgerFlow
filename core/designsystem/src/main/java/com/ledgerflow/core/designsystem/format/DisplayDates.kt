package com.ledgerflow.core.designsystem.format

import com.ledgerflow.core.model.DateOrder
import java.text.DateFormat
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.time.format.TextStyle
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Every date the app writes on screen, in the order Settings > Currency &
 * region chose (`docs/V2-PLAN.md` §3.3; month names in English, `DisplayLocale`,
 * BUG61).
 *
 * **[DateOrder.AUTO] is, byte for byte, what each screen wrote before this
 * existed**, and that is why there are three shapes rather than one. Screens
 * had grown two families: the phone region's own medium date ([medium]: Entry,
 * Review, receipt scan, the backup rows, Diagnostics -- "Oct 24, 2026" in the
 * US, "24 Oct 2026" in India), and a day-first date built by hand
 * ([dayMonthYear], [dayMonth]: Analytics, Budgets, the Ledger's stamps).
 * Folding them into one under AUTO would have moved dates for people who never
 * opened the setting. Under an explicit order all three agree.
 *
 * Not for anything written to a file or parsed: those keep `Locale.ROOT` or a
 * fixed ISO pattern.
 */
public object DisplayDates {

    /** A screen's full date: the region's medium style under AUTO. */
    public fun medium(
        date: LocalDate,
        locale: Locale = DisplayLocale.current(),
        order: DateOrder = DisplayConventions.current.dateOrder,
    ): String = when (order) {
        DateOrder.AUTO -> DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(locale).format(date)
        DateOrder.DAY_MONTH_YEAR, DateOrder.MONTH_DAY_YEAR, DateOrder.YEAR_MONTH_DAY ->
            ordered(date, order, locale, withYear = true)
    }

    /**
     * [medium] for an instant, as the screens that used `java.text.DateFormat`
     * wrote it. Under AUTO it is that same call, so their dates do not move.
     */
    public fun medium(
        millis: Long,
        zone: ZoneId = ZoneId.systemDefault(),
        locale: Locale = DisplayLocale.current(),
        order: DateOrder = DisplayConventions.current.dateOrder,
    ): String = when (order) {
        DateOrder.AUTO -> DateFormat.getDateInstance(DateFormat.MEDIUM, locale).inZone(zone).format(Date(millis))
        DateOrder.DAY_MONTH_YEAR, DateOrder.MONTH_DAY_YEAR, DateOrder.YEAR_MONTH_DAY ->
            ordered(Instant.ofEpochMilli(millis).atZone(zone).toLocalDate(), order, locale, withYear = true)
    }

    /** A date and the time: "Oct 24, 2026, 4:12 PM" under AUTO (the restore list). */
    public fun mediumDateTime(
        millis: Long,
        zone: ZoneId = ZoneId.systemDefault(),
        locale: Locale = DisplayLocale.current(),
        order: DateOrder = DisplayConventions.current.dateOrder,
    ): String = when (order) {
        DateOrder.AUTO ->
            DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT, locale)
                .inZone(zone)
                .format(Date(millis))
        DateOrder.DAY_MONTH_YEAR, DateOrder.MONTH_DAY_YEAR, DateOrder.YEAR_MONTH_DAY ->
            medium(millis, zone, locale, order) + ", " +
                DateFormat.getTimeInstance(DateFormat.SHORT, locale).inZone(zone).format(Date(millis))
    }

    private fun DateFormat.inZone(zone: ZoneId): DateFormat = apply { timeZone = TimeZone.getTimeZone(zone) }

    /** "24 Oct 2026": day first under AUTO, as those screens always were. */
    public fun dayMonthYear(
        date: LocalDate,
        locale: Locale = DisplayLocale.current(),
        order: DateOrder = DisplayConventions.current.dateOrder,
    ): String = ordered(date, order.dayFirstWhenAuto(), locale, withYear = true)

    /** "24 Oct": day first under AUTO. */
    public fun dayMonth(
        date: LocalDate,
        locale: Locale = DisplayLocale.current(),
        order: DateOrder = DisplayConventions.current.dateOrder,
    ): String = ordered(date, order.dayFirstWhenAuto(), locale, withYear = false)

    /**
     * The `DateTimeFormatter` pattern for [dayMonth], for a caller that adds a
     * clock to it (`TimeStamp`) or caches a formatter.
     */
    public fun dayMonthPattern(order: DateOrder = DisplayConventions.current.dateOrder): String =
        when (order.dayFirstWhenAuto()) {
            DateOrder.MONTH_DAY_YEAR, DateOrder.YEAR_MONTH_DAY -> "MMM d"
            DateOrder.AUTO, DateOrder.DAY_MONTH_YEAR -> "d MMM"
        }

    private fun DateOrder.dayFirstWhenAuto(): DateOrder =
        if (this == DateOrder.AUTO) DateOrder.DAY_MONTH_YEAR else this

    /**
     * Built from parts, the way the hand-built screens always did, so a short
     * month reads the same here as there. A year-first date without its year
     * is month first ("Oct 24").
     */
    private fun ordered(date: LocalDate, order: DateOrder, locale: Locale, withYear: Boolean): String {
        val day = date.dayOfMonth
        val month = date.month.getDisplayName(TextStyle.SHORT, locale)
        val year = date.year
        return when (order) {
            DateOrder.AUTO, DateOrder.DAY_MONTH_YEAR -> if (withYear) "$day $month $year" else "$day $month"
            DateOrder.MONTH_DAY_YEAR -> if (withYear) "$month $day, $year" else "$month $day"
            DateOrder.YEAR_MONTH_DAY -> if (withYear) "$year $month $day" else "$month $day"
        }
    }
}
