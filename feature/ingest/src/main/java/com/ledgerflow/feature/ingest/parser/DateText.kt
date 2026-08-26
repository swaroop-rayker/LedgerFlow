package com.ledgerflow.feature.ingest.parser

import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Reads the transaction's own date out of a message, when it states one.
 *
 * **This is separate from the capture time and must stay so.** A bank SMS can
 * arrive hours after the payment, and a notification can be re-posted; using
 * capture time as the transaction time puts a delayed message in the wrong day,
 * which is a whole category of "my totals are off by one day" that no user would
 * ever diagnose. `RawIngestEvent.receivedAt` is when *this device* saw it;
 * this is when the bank says it happened.
 *
 * **Null is a good answer.** Indian bank messages use at least a dozen date
 * shapes and several state no date at all. The formats below are the ones that
 * dominate; anything else returns null and the review screen falls back to the
 * capture time, which is a small error the user can see and fix. Guessing at an
 * ambiguous format would produce a *confident* wrong date instead, which they
 * cannot.
 *
 * `dd-MM-yy` before `dd-MM-yyyy` in the list is not arbitrary — both would parse
 * `26-08-2026`, and trying the two-digit form first would read the year as 20.
 * The list is ordered most-specific-first for that reason.
 */
internal object DateText {

    /** Local time, because a bank message states local time and says nothing about a zone. */
    private val zone: ZoneId get() = ZoneId.systemDefault()

    private val dateTimeFormats: List<DateTimeFormatter> = listOf(
        "dd-MM-yyyy HH:mm:ss",
        "dd-MM-yyyy HH:mm",
        "dd/MM/yyyy HH:mm:ss",
        "dd/MM/yyyy HH:mm",
        "dd-MMM-yyyy HH:mm:ss",
        "dd-MMM-yy HH:mm:ss",
        "yyyy-MM-dd HH:mm:ss",
    ).map { pattern -> DateTimeFormatter.ofPattern(pattern, Locale.ENGLISH) }

    private val dateOnlyFormats: List<DateTimeFormatter> = listOf(
        "dd-MM-yyyy",
        "dd/MM/yyyy",
        "dd-MMM-yyyy",
        "dd-MMM-yy",
        "yyyy-MM-dd",
        "dd-MM-yy",
        "dd/MM/yy",
    ).map { pattern -> DateTimeFormatter.ofPattern(pattern, Locale.ENGLISH) }

    /**
     * Epoch millis for [text], or null when it is not a date this understands.
     *
     * One return per format family plus the guards. Restructuring to satisfy a
     * return count would replace "try each shape, take the first that parses"
     * with something less obvious about the ordering that matters.
     */
    @Suppress("ReturnCount")
    fun parse(text: String): Long? {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return null

        dateTimeFormats.forEach { format ->
            runCatching { LocalDateTime.parse(trimmed, format) }
                .getOrNull()
                ?.let { return it.atZone(zone).toInstant().toEpochMilli() }
        }
        dateOnlyFormats.forEach { format ->
            runCatching { LocalDate.parse(trimmed, format) }
                .getOrNull()
                // Midnight local. The message gave a day and no time, and
                // inventing one would be precision the source does not have.
                ?.let { return it.atStartOfDay(zone).toInstant().toEpochMilli() }
        }
        return null
    }
}
