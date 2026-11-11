package com.ledgerflow.core.designsystem.format

import java.text.DecimalFormatSymbols
import java.util.Locale

/**
 * An exchange rate as text, in micro-units (× 10⁶, the scale of
 * `fx_rate_micro`): the converter's "Your rate" field and its rate line
 * (ADR-0036).
 *
 * A rate is a ratio, not money, so it has no currency and no grouping; it
 * keeps up to six decimals, which `MoneyFormat` cannot. Typed text goes
 * through [TypedNumber] (BUG62), so any script's digits and the locale's
 * decimal mark both work.
 */
public object RateFormat {

    private const val MICRO_DIGITS = 6
    private const val MAX_WHOLE_DIGITS = 12
    private const val MICRO = 1_000_000L

    /** A rate of one or more reads to four places ("93.9368"); a smaller one to six ("0.010645"). */
    private const val SHORT_DIGITS = 4

    /** [text] as micro-units; `null` when empty, zero, or past six decimals. */
    public fun parse(text: String, locale: Locale = DisplayLocale.current()): Long? {
        val separator = DecimalFormatSymbols.getInstance(locale).decimalSeparator
        val cleaned = TypedNumber.normalize(text, separator)
        val whole = cleaned.substringBefore('.')
        val fraction = cleaned.substringAfter('.', "")
        val readable = cleaned.count { it == '.' } <= 1 &&
            whole.length <= MAX_WHOLE_DIGITS &&
            fraction.length <= MICRO_DIGITS &&
            (whole + fraction).isNotEmpty()
        if (!readable) return null
        val micro = (whole.ifEmpty { "0" }.toLong() * MICRO) + fraction.padEnd(MICRO_DIGITS, '0').toLong()
        return micro.takeIf { it > 0L }
    }

    /** For the rate line: four places at one or more, six below, rounded half up. */
    public fun display(micro: Long, locale: Locale = DisplayLocale.current()): String {
        val places = if (micro >= MICRO) SHORT_DIGITS else MICRO_DIGITS
        val step = pow10(MICRO_DIGITS - places)
        val rounded = (micro + step / 2) / step
        return decimal(rounded, places, locale)
    }

    /** All six places with trailing zeros dropped: a rate put back into the field. */
    public fun plain(micro: Long, locale: Locale = DisplayLocale.current()): String {
        val full = decimal(micro, MICRO_DIGITS, locale)
        val separator = DecimalFormatSymbols.getInstance(locale).decimalSeparator
        return if (separator in full) full.trimEnd('0').trimEnd(separator) else full
    }

    private fun decimal(units: Long, places: Int, locale: Locale): String {
        val separator = DecimalFormatSymbols.getInstance(locale).decimalSeparator
        val scale = pow10(places)
        val fraction = (units % scale).toString().padStart(places, '0')
        return "${units / scale}$separator$fraction"
    }

    private const val RADIX = 10L

    private fun pow10(n: Int): Long = (1..n).fold(1L) { acc, _ -> acc * RADIX }
}
