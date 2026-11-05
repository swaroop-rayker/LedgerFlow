package com.ledgerflow.core.domain.statement

import java.time.DateTimeException
import java.time.LocalDate

/**
 * A date as a statement writes it (V2-4 Import).
 *
 * Reads `2026-10-02`, `02/10/2026`, `02-10-26`, `2.10.2026`, `02-Oct-2026`,
 * `2 Oct 2026`, `Oct 2, 2026`, `20261002`, any of them followed by a time, and
 * a spreadsheet's serial day number (an XLSX date cell holds a number, not
 * text). An all-number date with a two-part ambiguity (03/04/26) is read in
 * [StatementDateOrder]; a four-digit first part is always a year.
 */
public object StatementDate {

    public fun parse(text: String, order: StatementDateOrder): LocalDate? {
        val trimmed = text.trim()
        return serial(trimmed) ?: fromText(TIME.replace(trimmed, "").trim(), order)
    }

    private fun fromText(datePart: String, order: StatementDateOrder): LocalDate? {
        val parts = PARTS.findAll(datePart).map { it.value }.toList()
        val monthIndex = parts.indexOfFirst { monthOf(it) != null }
        return when {
            parts.size == 1 -> compact(datePart)
            parts.size != DATE_PARTS -> null
            monthIndex >= 0 -> named(parts, monthIndex)
            else -> numeric(parts, order)
        }
    }

    /**
     * The order a column's dates must be in, if its values say: a first part
     * above 12 can only be a day, a second part above 12 only a day too. Null
     * when every value would read either way — then the user is asked.
     */
    public fun orderOf(samples: List<String>): StatementDateOrder? {
        var dayFirst = false
        var monthFirst = false
        samples.forEach { sample ->
            val parts = PARTS.findAll(TIME.replace(sample.trim(), "")).map { it.value }.toList()
            if (parts.size != DATE_PARTS || parts.any { monthOf(it) != null }) return@forEach
            val numbers = parts.map { it.toIntOrNull() ?: return@forEach }
            when {
                parts[0].length == YEAR_DIGITS -> return StatementDateOrder.YearFirst
                numbers[0] > MONTHS -> dayFirst = true
                numbers[1] > MONTHS -> monthFirst = true
            }
        }
        return when {
            dayFirst && !monthFirst -> StatementDateOrder.DayFirst
            monthFirst && !dayFirst -> StatementDateOrder.MonthFirst
            else -> null
        }
    }

    private fun named(parts: List<String>, monthIndex: Int): LocalDate? {
        val month = monthOf(parts[monthIndex]) ?: return null
        val others = parts.filterIndexed { index, _ -> index != monthIndex }.map { it.toIntOrNull() ?: return null }
        // "2 Oct 2026", "02-Oct-26" and "Oct 2, 2026" put the day before the
        // year; only a four-digit first part ("2026-Oct-02") puts it after.
        val (day, year) = if (parts[0].length == YEAR_DIGITS) others[1] to others[0] else others[0] to others[1]
        return date(year(year), month, day)
    }

    private fun numeric(parts: List<String>, order: StatementDateOrder): LocalDate? {
        val numbers = parts.map { it.toIntOrNull() ?: return null }
        if (parts[0].length == YEAR_DIGITS) return date(numbers[0], numbers[1], numbers[2])
        return when (order) {
            StatementDateOrder.DayFirst -> date(year(numbers[2]), numbers[1], numbers[0])
            StatementDateOrder.MonthFirst -> date(year(numbers[2]), numbers[0], numbers[1])
            StatementDateOrder.YearFirst -> date(year(numbers[0]), numbers[1], numbers[2])
        }
    }

    /** `20261002`. */
    private fun compact(text: String): LocalDate? {
        if (text.length != COMPACT_LENGTH || !text.all { it in '0'..'9' }) return null
        return date(
            year = text.substring(0, YEAR_DIGITS).toInt(),
            month = text.substring(YEAR_DIGITS, MONTH_END).toInt(),
            day = text.substring(MONTH_END, COMPACT_LENGTH).toInt(),
        )
    }

    /**
     * A spreadsheet date: days since 30 Dec 1899, perhaps with a time as the
     * fraction. Only a plausible range, so a stray amount is not read as 1956.
     */
    private fun serial(text: String): LocalDate? {
        val whole = SERIAL.matchEntire(text)?.groupValues?.get(1)?.toLongOrNull() ?: return null
        if (whole !in SERIAL_FIRST..SERIAL_LAST) return null
        return EXCEL_EPOCH.plusDays(whole)
    }

    private fun year(value: Int): Int = if (value < CENTURY) TWO_DIGIT_BASE + value else value

    private fun date(year: Int, month: Int, day: Int): LocalDate? =
        try {
            LocalDate.of(year, month, day)
        } catch (_: DateTimeException) {
            null
        }

    private fun monthOf(text: String): Int? {
        val lower = text.lowercase().trimEnd('.')
        if (lower.length < MONTH_PREFIX) return null
        return MONTH_NAMES.indexOfFirst { it.startsWith(lower.take(MONTH_PREFIX)) && it.startsWith(lower) }
            .takeIf { it >= 0 }?.plus(1)
    }

    private val PARTS = Regex("""[0-9]+|[A-Za-z]+""")
    private val TIME = Regex("""[ T]\d{1,2}:\d{2}(:\d{2})?(\.\d+)?\s*([AaPp][Mm])?.*$""")
    private val SERIAL = Regex("""(\d{5})(\.\d+)?""")
    private val EXCEL_EPOCH: LocalDate = LocalDate.of(1899, 12, 30)
    private val MONTH_NAMES = listOf(
        "january", "february", "march", "april", "may", "june",
        "july", "august", "september", "october", "november", "december",
    )

    private const val DATE_PARTS = 3
    private const val YEAR_DIGITS = 4
    private const val MONTHS = 12
    private const val CENTURY = 100
    private const val TWO_DIGIT_BASE = 2000
    private const val COMPACT_LENGTH = 8
    private const val MONTH_END = 6
    private const val MONTH_PREFIX = 3

    /** 1 Jan 1960 and 31 Dec 2099 as spreadsheet serials: dates a statement could hold. */
    private const val SERIAL_FIRST = 21916L
    private const val SERIAL_LAST = 73050L
}
