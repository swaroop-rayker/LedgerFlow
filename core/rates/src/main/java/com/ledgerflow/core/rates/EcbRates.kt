package com.ledgerflow.core.rates

import java.time.LocalDate
import java.time.format.DateTimeParseException

/**
 * The ECB's daily reference-rate file (ADR-0036 §1), parsed **strictly**.
 *
 * The format has been stable for two decades and was checked against the live
 * file on 2026-11-10 (`src/test/resources/eurofxref-daily.xml` is that copy):
 * an envelope in the ECB's namespace, one outer `Cube`, one `Cube time=…`, and
 * one `Cube currency=… rate=…` per currency. Anything else — another tag
 * named `Cube`, a second date, a repeated currency, the euro listed against
 * itself, a rate with a sign, an exponent or more than six decimals — makes
 * the whole file unreadable, and the last good table stays in use (§2).
 *
 * Patterns rather than an XML parser: the file is a few kilobytes of fixed
 * shape, and a parser is the larger attack surface (entities, DTDs) for no
 * gain. A crude reader that refuses anything unexpected suits a file the app
 * only ever displays.
 */
public object EcbRates {

    /** The one address this app fetches rates from. HTTPS; no query string. */
    public const val ADDRESS: String = "https://www.ecb.europa.eu/stats/eurofxref/eurofxref-daily.xml"

    private const val NAMESPACE = "http://www.ecb.int/vocabulary/2002-08-01/eurofxref"
    private const val MICRO = 1_000_000L
    private const val MICRO_DIGITS = 6

    /** Twelve whole digits × 10⁶ stays well inside a `Long`; IDR per euro has five. */
    private const val MAX_WHOLE_DIGITS = 12

    /** The capture groups of [time] and [currency]: the date; the code and the rate (1 and 3 are quotes). */
    private const val TIME_GROUP = 2
    private const val CODE_GROUP = 2
    private const val RATE_GROUP = 4

    private val anyCube = Regex("""<Cube\b[^>]*>""")
    private val outer = Regex("""<Cube\s*>""")
    private val time = Regex("""<Cube\s+time=(['"])(\d{4}-\d{2}-\d{2})\1\s*>""")
    private val currency = Regex("""<Cube\s+currency=(['"])([A-Z]{3})\1\s+rate=(['"])(\d+(?:\.\d+)?)\3\s*/>""")

    /** The table in [bytes], or `null` when they are not exactly the ECB's format. */
    public fun parse(bytes: ByteArray): RateTable? {
        val text = bytes.decodeToString()
        if ("<gesmes:Envelope" !in text || NAMESPACE !in text) return null
        val tags = anyCube.findAll(text).map { classify(it.value) }.toList()
        val dates = tags.filterIsInstance<Tag.Date>()
        val rates = tags.filterIsInstance<Tag.Rate>()
        val exact = Tag.Unknown !in tags &&
            dates.size == 1 &&
            rates.isNotEmpty() &&
            rates.distinctBy { it.code }.size == rates.size &&
            rates.none { it.code == RateTable.EURO }
        return if (exact) RateTable(dates.single().epochDay, rates.associate { it.code to it.micro }) else null
    }

    /** "105.1435" as 105_143_500; `null` for zero, a sign, or more than six decimals. */
    public fun microOf(decimal: String): Long? {
        val whole = decimal.substringBefore('.')
        val fraction = decimal.substringAfter('.', "")
        val digits = whole.length in 1..MAX_WHOLE_DIGITS &&
            fraction.length <= MICRO_DIGITS &&
            whole.all(::isAsciiDigit) &&
            fraction.all(::isAsciiDigit)
        if (!digits) return null
        return (whole.toLong() * MICRO + fraction.padEnd(MICRO_DIGITS, '0').toLong()).takeIf { it > 0L }
    }

    private sealed interface Tag {
        data object Outer : Tag

        data class Date(val epochDay: Int) : Tag

        data class Rate(val code: String, val micro: Long) : Tag

        data object Unknown : Tag
    }

    private fun classify(tag: String): Tag {
        val stamped = time.matchEntire(tag)
        val listed = currency.matchEntire(tag)
        return when {
            outer.matches(tag) -> Tag.Outer
            stamped != null -> epochDayOf(stamped.groupValues[TIME_GROUP])?.let(Tag::Date) ?: Tag.Unknown
            listed != null -> microOf(listed.groupValues[RATE_GROUP])
                ?.let { Tag.Rate(listed.groupValues[CODE_GROUP], it) }
                ?: Tag.Unknown
            else -> Tag.Unknown
        }
    }

    private fun epochDayOf(iso: String): Int? = try {
        LocalDate.parse(iso).toEpochDay().toInt()
    } catch (_: DateTimeParseException) {
        null
    }

    private fun isAsciiDigit(c: Char): Boolean = c in '0'..'9'
}
