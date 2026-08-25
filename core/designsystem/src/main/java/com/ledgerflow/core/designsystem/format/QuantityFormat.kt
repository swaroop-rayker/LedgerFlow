package com.ledgerflow.core.designsystem.format

import com.ledgerflow.core.model.Quantity
import java.text.DecimalFormatSymbols
import java.util.Locale

/**
 * How many of an item, typed and displayed (SPEC.md §5.4, ADR-0018).
 *
 * The sibling of [MoneyFormat] and deliberately built the same way, because it
 * sits in the same row of the same form and the two fields must not behave
 * differently under the same thumb: digits and separators are kept, everything
 * else is discarded rather than rejected, extra decimals are truncated rather
 * than rounded, and the result is an integer in thousandths — never a `Double`.
 *
 * Quantity is not money, so Law 3 does not literally cover it. It multiplies
 * money, which is the same thing in practice: a quantity that arrived through a
 * `Double` would put the error back into the line total by the back door.
 */
public object QuantityFormat {

    /**
     * A quantity field's worth of digits.
     *
     * Six is a deliberate cap rather than a round number: it allows 999,999 of
     * anything, which is past any real bill, and it keeps `unitPrice × quantity`
     * inside a `Long` for any amount the money field will accept.
     */
    private const val MAX_WHOLE_DIGITS = 6

    /** Thousandths, so the scale is fixed by [Quantity.SCALE] rather than chosen here. */
    private const val DECIMALS = 3

    private const val RADIX = 10L

    /**
     * Parses what is in the field.
     *
     * Returns thousandths as a bare `Long`, not a `Quantity`, for the reason
     * [MoneyFormat.parse] returns minor units rather than a `Money`:
     * `:core:designsystem` takes `:core:model` as an `implementation`
     * dependency, so a model type in a public signature here would be one this
     * module's consumers cannot see. The caller wraps it at the point the
     * arithmetic happens, which is where the type earns its keep.
     *
     * An empty or unreadable field is one unit, not zero. A line the user has
     * not given a quantity to is one of that thing — zero would silently make
     * the line worth nothing and take the amount with it, which is a surprising
     * answer to a field somebody simply left alone.
     */
    public fun parse(text: String, locale: Locale = Locale.getDefault()): Long {
        val separator = DecimalFormatSymbols.getInstance(locale).decimalSeparator
        val cleaned = text.filter { it.isDigit() || it == separator || it == '.' }
        if (cleaned.none(Char::isDigit)) return Quantity.SCALE

        val splitAt = cleaned.indexOfFirst { it == separator || it == '.' }
        val whole = if (splitAt < 0) cleaned else cleaned.take(splitAt)
        val fraction = if (splitAt < 0) "" else cleaned.drop(splitAt + 1)

        val units = whole.filter(Char::isDigit).take(MAX_WHOLE_DIGITS).foldDigits()
        val thousandths = fraction.filter(Char::isDigit)
            .take(DECIMALS)
            .padEnd(DECIMALS, '0')
            .foldDigits()

        return units * Quantity.SCALE + thousandths
    }

    /**
     * The short form: "2", "0.5", "1.25".
     *
     * Trailing zeros are dropped because the scale is a storage detail. A field
     * showing "2.000" invites the user to think the decimals mean something,
     * and a chip reading "×2.000" is three characters of noise on the one line
     * a collapsed row has.
     */
    public fun plain(quantityMilli: Long, locale: Locale = Locale.getDefault()): String {
        val separator = DecimalFormatSymbols.getInstance(locale).decimalSeparator
        val units = quantityMilli / Quantity.SCALE
        val thousandths = (quantityMilli % Quantity.SCALE).toInt()
        if (thousandths == 0) return units.toString()

        val fraction = thousandths.toString().padStart(DECIMALS, '0').trimEnd('0')
        return "$units$separator$fraction"
    }

    /** Digits -> `Long`. Capped by the caller, so this cannot overflow. */
    private fun String.foldDigits(): Long =
        fold(0L) { total, character -> total * RADIX + (character - '0') }
}
