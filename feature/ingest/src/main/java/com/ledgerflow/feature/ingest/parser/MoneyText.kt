package com.ledgerflow.feature.ingest.parser

import com.ledgerflow.core.model.CurrencyExponent
import com.ledgerflow.core.model.Money

/**
 * Reads an amount out of a bank message, in integer arithmetic only.
 *
 * **Law 3 is the whole design here.** The obvious implementation —
 * `text.toDouble() * 100` — is wrong for a reason that never shows up in
 * testing: `12.35.toDouble() * 100` is `1234.9999999999998`, which truncates to
 * ₹12.34 and loses a paisa on a fraction of transactions forever. So the string
 * is split on its decimal point and the two halves are combined as `Long`s, and
 * no floating-point type appears in this file at all.
 *
 * Everything it has to survive comes from real message formats:
 * `Rs.1,240.50` · `INR 1240.5` · `Rs 1,24,000` (Indian grouping, which is not
 * every three digits) · `1240.500` (more fraction digits than the currency has).
 */
internal object MoneyText {

    /**
     * Parses [text] as an amount in [currencyCode], or null if it is not one.
     *
     * Null rather than zero: a rule whose amount group matched something
     * unparseable must produce "no amount" so the row becomes
     * `needs_manual_fill`, and a silent 0 would instead offer the user a
     * confident ₹0.00 to approve.
     *
     * The early returns are guard clauses, one per way the text can fail to be
     * an amount. Collapsing them into a nested expression to satisfy a return
     * count would make the one thing this function must get exactly right --
     * which inputs it rejects -- considerably harder to read.
     */
    @Suppress("ReturnCount")
    fun parse(text: String, currencyCode: String): Money? {
        val digitsAndPoint = text.trim()
            // Grouping separators vary and Indian grouping is not every three
            // digits, so they are removed rather than validated.
            .replace(",", "")
            .replace(" ", "")
            // A trailing minus ("1,240.00-") means debit in some formats; the
            // direction is decided elsewhere, so only the magnitude matters.
            .trimEnd('-')
            .removePrefix("+")

        if (digitsAndPoint.isEmpty()) return null

        val negative = digitsAndPoint.startsWith("-")
        val magnitude = digitsAndPoint.removePrefix("-")
        if (magnitude.isEmpty() || !magnitude.all { it.isDigit() || it == '.' }) return null

        val parts = magnitude.split('.')
        if (parts.size > 2) return null

        val whole = parts[0].ifEmpty { "0" }
        if (whole.any { !it.isDigit() }) return null

        val exponent = CurrencyExponent.of(currencyCode)
        val fraction = parts.getOrElse(1) { "" }
        if (fraction.any { !it.isDigit() }) return null

        // Padded or truncated to the currency's own exponent. "1240.5" in INR is
        // 1240.50, and "1240.509" is 1240.50 -- truncated, never rounded: a
        // rounding rule here would silently disagree with the bank's own figure.
        val normalizedFraction = fraction.padEnd(exponent, '0').take(exponent)

        val wholeMinor = whole.toLongOrNull()?.times(pow10(exponent)) ?: return null
        val fractionMinor = if (normalizedFraction.isEmpty()) 0L else normalizedFraction.toLong()

        val minor = wholeMinor + fractionMinor
        return Money(if (negative) -minor else minor)
    }

    /** Integer powers of ten. `Math.pow` returns a Double, which this file may not touch. */
    private fun pow10(exponent: Int): Long {
        var result = 1L
        repeat(exponent) { result *= DECIMAL_BASE }
        return result
    }

    private const val DECIMAL_BASE = 10L
}
