package com.ledgerflow.core.designsystem.format

import com.ledgerflow.core.model.CurrencyDisplay
import com.ledgerflow.core.model.CurrencyExponent
import java.math.BigDecimal
import java.text.DecimalFormatSymbols
import java.util.Locale

/**
 * Minor units -> something a person reads (SPEC.md §6.2).
 *
 * Formatting lives in the UI layer and nowhere else. Every amount below the
 * design system is a `Long` of minor units, and the decimal point exists only
 * on screen.
 *
 * **`BigDecimal`, never `Double`.** Law 3 bans binary floating point for money
 * because it cannot represent 0.10 exactly, and that ban does not stop at
 * arithmetic: `format(aDouble)` would reintroduce the rounding it exists to
 * prevent at the last step before the user sees the number.
 * `BigDecimal.valueOf(minor, exponent)` is an exact re-scaling of the `Long` --
 * no division, no approximation -- and this runs once per rendered amount,
 * never inside a sum.
 *
 * **Grouping follows the currency; separator characters follow the locale.**
 * The two are deliberately split. Indian digit grouping writes ₹1,24,000 where
 * the western convention writes 124,000, and that is a property of the *money*:
 * a rupee amount should look like rupees on a phone set to English (US).
 *
 * The grouping is also hand-rolled, for the same reason `:core:crypto`
 * hand-rolls HKDF and §5.8 hardcodes currency exponents -- the platform cannot
 * be relied on for it. `java.text.DecimalFormat` carries a *single* grouping
 * size and structurally cannot express the 3-then-2s shape at all; asking
 * `NumberFormat` for `en-IN` returns western grouping on the desktop JDK these
 * unit tests run on, and Android's ICU implementation would answer differently
 * on the device. Fifteen lines here are worth an amount that reads the same
 * everywhere.
 */
public object MoneyFormat {

    /** `1,24,000.00` for INR, `124,000.00` for USD -- grouped, unsigned by nature. */
    public fun plain(
        minor: Long,
        currencyCode: String,
        locale: Locale = Locale.getDefault(),
    ): String {
        val symbols = DecimalFormatSymbols.getInstance(locale)
        val sign = if (minor < 0) symbols.minusSign.toString() else ""
        return sign + magnitude(minor, currencyCode, locale)
    }

    /** `₹1,240.50` -- what an amount looks like on screen. */
    public fun symbolised(
        minor: Long,
        currencyCode: String,
        locale: Locale = Locale.getDefault(),
    ): String {
        val symbol = CurrencyDisplay.symbolOf(currencyCode)
        val sign = if (minor < 0) {
            DecimalFormatSymbols.getInstance(locale).minusSign.toString()
        } else {
            ""
        }
        // The sign leads the symbol: "-₹5" reads as a negative amount where
        // "₹-5" reads as a typo.
        return sign + symbol + magnitude(minor, currencyCode, locale)
    }

    /**
     * `1,240.50 rupees` -- what TalkBack should say (§9.6).
     *
     * Screen readers announce currency glyphs inconsistently, and "₹" is
     * frequently skipped entirely, which turns an amount into a bare number.
     * Callers add the surrounding sentence ("spent ... on groceries").
     */
    public fun spoken(
        minor: Long,
        currencyCode: String,
        locale: Locale = Locale.getDefault(),
    ): String = "${plain(minor, currencyCode, locale)} ${CurrencyDisplay.spokenUnitOf(currencyCode)}"

    /**
     * What a person typed -> minor units.
     *
     * **The whole point of this function is that no `Double` is involved.**
     * `text.toDouble() * 100` is the obvious one-liner and it is wrong: it
     * cannot represent 0.10 exactly, so it turns some amounts into a value one
     * paise off, silently, at the exact moment the user's number enters the
     * app. This splits on the decimal separator and folds each side into a
     * `Long` with integer arithmetic only -- the same discipline `plain` uses
     * on the way back out.
     *
     * Deliberately forgiving, because it reads a live text field mid-keystroke
     * and because a keyboard is not a contract: `KeyboardType.Decimal` is a
     * *hint*, and some OEM keyboards serve a full QWERTY regardless. Anything
     * that is not a digit or a separator is discarded rather than rejected, so
     * "12ab.5" is 12.50 and an empty field is zero. That tolerance is what
     * makes the system keyboard safe to use here at all.
     *
     * Extra decimals are **truncated, not rounded**: rounding the third digit
     * of "12.567" would rewrite what the user is still typing.
     *
     * @return minor units, never negative. Amounts are stored positive and
     *   direction is carried by [com.ledgerflow.core.model.LedgerType].
     */
    public fun parse(
        text: String,
        currencyCode: String,
        locale: Locale = Locale.getDefault(),
    ): Long {
        val exponent = CurrencyExponent.of(currencyCode)
        val separator = DecimalFormatSymbols.getInstance(locale).decimalSeparator

        // '.' is accepted whatever the locale says, because on a physical
        // keyboard and on most IMEs that is the key the user actually presses.
        val cleaned = text.filter { it.isDigit() || it == separator || it == '.' }
        val splitAt = cleaned.indexOfFirst { it == separator || it == '.' }

        val whole = if (splitAt < 0) cleaned else cleaned.take(splitAt)
        val fraction = if (splitAt < 0) "" else cleaned.drop(splitAt + 1)

        val units = whole.filter(Char::isDigit).take(MAX_WHOLE_DIGITS).foldDigits()
        val subUnits = fraction.filter(Char::isDigit)
            .take(exponent)
            .padEnd(exponent, '0')
            .foldDigits()

        return units * POWERS_OF_TEN[exponent] + subUnits
    }

    /** Digits -> `Long`. Capped by the caller, so this cannot overflow. */
    private fun String.foldDigits(): Long =
        fold(0L) { total, character -> total * RADIX + (character - '0') }

    /**
     * The digits, without a sign.
     *
     * Goes through `BigDecimal.abs()` rather than negating the `Long`, because
     * `-Long.MIN_VALUE` is `Long.MIN_VALUE` and a sign flip that silently does
     * nothing is exactly the class of bug Law 3 is about.
     */
    private fun magnitude(minor: Long, currencyCode: String, locale: Locale): String {
        val exponent = CurrencyExponent.of(currencyCode)
        val symbols = DecimalFormatSymbols.getInstance(locale)
        val plain = BigDecimal.valueOf(minor, exponent).abs().toPlainString()

        val integer = plain.substringBefore('.')
        val fraction = plain.substringAfter('.', missingDelimiterValue = "")
        val grouped = group(
            digits = integer,
            groupSize = if (currencyCode.uppercase() in LAKH_GROUPED) LAKH_GROUP else WESTERN_GROUP,
            separator = symbols.groupingSeparator,
        )
        return if (fraction.isEmpty()) grouped else grouped + symbols.decimalSeparator + fraction
    }

    /**
     * Separates [digits] into a final group of three plus repeating groups of
     * [groupSize] above it.
     *
     * The trailing three are always three, in both conventions: 1,23,45,678 and
     * 12,345,678 differ only above the hundreds.
     */
    private fun group(digits: String, groupSize: Int, separator: Char): String {
        if (digits.length <= WESTERN_GROUP) return digits

        val chunks = ArrayDeque<String>()
        chunks.addFirst(digits.takeLast(WESTERN_GROUP))

        var head = digits.dropLast(WESTERN_GROUP)
        while (head.length > groupSize) {
            chunks.addFirst(head.takeLast(groupSize))
            head = head.dropLast(groupSize)
        }
        if (head.isNotEmpty()) chunks.addFirst(head)

        return chunks.joinToString(separator.toString())
    }

    private const val WESTERN_GROUP = 3
    private const val LAKH_GROUP = 2
    private const val RADIX = 10L

    /**
     * Ceiling on the whole part of a typed amount: 999,999,999,999 major units.
     *
     * Not a product limit. It is what stops a long paste from overflowing the
     * fold into a negative `Long`, which would turn a large expense into
     * income. Twelve digits leaves the largest plausible amount intact with
     * room to spare against `Long.MAX_VALUE` once the exponent is applied.
     */
    private const val MAX_WHOLE_DIGITS = 12

    /** Indexed by ISO-4217 exponent (0, 2 and 3 are the values that occur). */
    private val POWERS_OF_TEN = longArrayOf(1L, 10L, 100L, 1_000L)

    /**
     * Currencies written with lakh/crore grouping.
     *
     * Short on purpose: the currencies LedgerFlow offers (`CurrencyDisplay`)
     * plus their immediate neighbours, not a general table.
     */
    private val LAKH_GROUPED = setOf("INR", "PKR", "BDT", "NPR", "LKR")
}
