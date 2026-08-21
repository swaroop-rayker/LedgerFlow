package com.ledgerflow.core.data.export

import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/**
 * RFC 4180 CSV assembly. Pure -- no streams, no Android, no clock.
 *
 * Separated from the writing so the escaping can be tested exhaustively without
 * a device or a file. Every defect this class can have is a string defect, and
 * string defects are the ones that survive a happy-path integration test: a
 * comma inside a merchant name silently shifts every column after it, and the
 * file still opens.
 */
internal object CsvWriter {

    /** RFC 4180 says CRLF, and Excel on Windows is unforgiving about it. */
    private const val EOL = "\r\n"

    private val TIMESTAMP: DateTimeFormatter =
        DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ss'Z'").withZone(ZoneOffset.UTC)

    /** One CSV document: a header row then the data rows. */
    fun document(header: List<String>, rows: List<List<String?>>): String = buildString {
        append(row(header))
        rows.forEach { append(row(it)) }
    }

    fun row(fields: List<String?>): String =
        fields.joinToString(separator = ",", postfix = EOL) { escape(it) }

    /**
     * Quotes only when it has to, and distinguishes null from empty.
     *
     * A null field is written as nothing at all; an empty string is written as
     * `""`. Without that split both arrive back as the same thing, and the
     * schema means different things by them -- `note` being absent is not the
     * same fact as a note the user cleared, and `original_currency` being null
     * is what says the entry was not foreign spend.
     *
     * The quoting rule is RFC 4180's exactly: quote if the field contains a
     * quote, a comma, CR or LF, and escape an embedded quote by doubling it.
     * **Leading and trailing spaces also force quoting**, which the RFC does not
     * require and every spreadsheet needs: unquoted, they are silently trimmed
     * on import, and a merchant named " Zepto" would come back as a different
     * string from the one exported.
     */
    fun escape(field: String?): String {
        if (field == null) return ""
        if (field.isEmpty()) return "\"\""

        val needsQuoting = field.any { it == '"' || it == ',' || it == '\r' || it == '\n' } ||
            field.first().isWhitespace() ||
            field.last().isWhitespace()

        if (!needsQuoting) return field
        return "\"" + field.replace("\"", "\"\"") + "\""
    }

    /**
     * Minor units as a decimal string, by integer arithmetic only (Law 3,
     * ADR-0017).
     *
     * **No `Double` appears anywhere in this function, and that is the point.**
     * `25500 / 100.0` is exact, so a casual test passes; the values that are not
     * exact are the ones a real ledger is full of, and a `Double` that renders
     * `8.07` as `8.069999999999999` produces a CSV that disagrees with the
     * app by a paisa on some rows and not others -- the least debuggable
     * possible defect.
     *
     * The sign is taken off before the split so that -5 minor units renders
     * `-0.05` rather than `-0.-5`, which is what naive division and remainder
     * produce for values inside the first unit. Amounts are stored positive
     * (§5.5: direction lives in `ledger`), so this is defence rather than a
     * live case -- but `fx_rate_micro` and future columns are not so
     * constrained.
     *
     * @param scale digits after the point. 2 for currency minor units.
     */
    fun decimal(minor: Long?, scale: Int = MINOR_UNIT_SCALE): String? {
        if (minor == null) return null

        val negative = minor < 0
        // `Math.abs(Long.MIN_VALUE)` is itself negative. Taking the absolute
        // value as an unsigned string instead means the one value that would
        // overflow renders correctly rather than as a negative fraction.
        val digits = java.lang.Long.toUnsignedString(if (negative) -minor else minor)

        val padded = digits.padStart(scale + 1, '0')
        val whole = padded.dropLast(scale)
        val fraction = padded.takeLast(scale)

        val sign = if (negative) "-" else ""
        return if (scale == 0) "$sign$whole" else "$sign$whole.$fraction"
    }

    /**
     * Epoch millis as ISO-8601 UTC.
     *
     * UTC rather than the device zone, deliberately: the epoch integer beside it
     * is already the authoritative value, so this column exists to be *read*,
     * and a local-time string with no offset in it is the classic way to make an
     * export ambiguous a year later in a different timezone. `Z` says what it is.
     */
    fun timestamp(epochMillis: Long?): String? =
        epochMillis?.let { TIMESTAMP.format(Instant.ofEpochMilli(it)) }

    /** SQLite has no boolean; the schema stores 0/1 and the CSV says so. */
    fun boolean(value: Boolean): String = if (value) "1" else "0"

    private const val MINOR_UNIT_SCALE = 2
}
