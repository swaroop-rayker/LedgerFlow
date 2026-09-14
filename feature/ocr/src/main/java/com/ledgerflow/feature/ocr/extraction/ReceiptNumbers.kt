package com.ledgerflow.feature.ocr.extraction

import com.ledgerflow.core.model.CurrencyExponent
import com.ledgerflow.core.model.Money
import com.ledgerflow.core.model.Quantity

/**
 * Reading a number off a receipt (SPEC.md §5.3, Law 3).
 *
 * **Integer arithmetic only, start to finish.** There is no `toDouble` on this
 * path and there must never be one: `"45.05".toDouble() * 100` is 4504.9999…,
 * and a ledger that acquires its rounding error at the OCR boundary is corrupt
 * in exactly the way Law 3 exists to prevent. The whole part and the fraction
 * are folded separately, as digits.
 *
 * ## What this deliberately refuses
 *
 * A receipt is covered in digits that are not money — a phone number, a GSTIN,
 * an invoice number, a date, a tax *rate*. Every one of them would parse if the
 * only rule were "contains digits", and each produces a plausible-looking line
 * item that §12's precision half is there to catch. So the grammar is strict
 * and the rejections are as deliberate as the acceptances:
 *
 * - a `%` anywhere means a **rate**, not an amount — `CGST 2.5%` must not
 *   contribute 250 paise to a bill
 * - more than [MAX_BARE_INTEGER_DIGITS] digits with no point and no grouping is
 *   an identifier, not a price: a ten-digit mobile number, a GSTIN — and, since
 *   BUG23, a six-digit PIN code
 * - more than two decimal places is a quantity, a weight or a version string;
 *   INR has two, and a third digit means this token is not a rupee amount
 * - a `/` or `-` *between* digits is a date or a range
 * - a **comma group that is not a real grouping** — `52,00` — is a misread
 *   decimal point, and misread punctuation is not reinterpreted (BUG23)
 *
 * **No glyph correction.** `TOMAT0` for `TOMATO` is fixed at name-comparison
 * time by §12's Jaro-Winkler threshold, but `4O.00` is not silently read as
 * `40.00`: money does not get a tolerance, and a guess about a digit is a guess
 * about an amount. An unreadable amount is a line the user corrects, which is
 * what the review screen is for.
 */
internal object ReceiptNumbers {

    /**
     * The longest whole part a **quantity** may have.
     *
     * This used to bound bare-integer money as well, at seven digits, which
     * refused a mobile number but let a six-digit PIN code through as
     * ₹5,80,020.00. Money now has its own, tighter bound,
     * [MAX_BARE_INTEGER_DIGITS]; this one is kept for quantities.
     */
    private const val MAX_UNGROUPED_DIGITS = 7

    /**
     * **BUG23 — the longest integer with no decimal point and no grouping that
     * can still be money.**
     *
     * Zepto's invoice prints its seller address as `Hubli - 580020`. The PIN
     * code sat in its own cell above the first price, parsed flat as
     * ₹5,80,020.00, became the first priced row and therefore an ITEM — a line
     * three thousand times the bill, on a ₹195 purchase. Every Indian PIN code
     * is six digits.
     *
     * A retail amount printed as a bare integer is short: `40`, `285`, a kirana
     * slip's `1250`. Five digits keeps ₹99,999 written flat and refuses every
     * PIN code. A larger sum on a real bill carries a decimal point or a
     * grouping comma, and either one takes it out of this rule.
     */
    private const val MAX_BARE_INTEGER_DIGITS = 5

    /** What a shop prints in front of an amount. Stripped before parsing. */
    private val CURRENCY_PREFIXES = listOf("₹", "RS.", "RS", "INR")

    /** And behind it. `/-` is ubiquitous on Indian bills. */
    private val VALUE_SUFFIXES = listOf("/-", "/", "CR", "DR", "*", "#")

    /** Currency markers, for [currencyMarkerIn]. */
    private val INR_MARKERS = listOf("₹", "RS.", "RS", "INR")

    /**
     * A grouped amount, **Indian or Western, and nothing in between**.
     *
     * The last group before the point is always three digits in both systems:
     * `1,23,456.78` groups 2-2-3, `1,234,567.00` groups 3-3-3. The old pattern
     * allowed any mix of two- and three-digit groups in any position, so
     * `52,00` — Zepto's `52.00` with the point misread as a comma — read as
     * ₹5,200.00 (BUG23). That is a guess about punctuation, and a guess about
     * punctuation is a guess about an amount; it is refused, not corrected.
     */
    private val GROUPED = Regex("""^\d{1,3}(?:(?:,\d{2})*,\d{3}|(?:,\d{3})+)(?:\.\d{1,2})?$""")
    private val FLAT = Regex("""^\d+(?:\.\d{1,2})?$""")

    /**
     * The token as money, or null when it is not an amount.
     *
     * [exponent] comes from [CurrencyExponent] rather than being assumed to be
     * two, so a zero-decimal install does not silently multiply every amount by
     * a hundred.
     */
    fun money(raw: String, currency: String = "INR"): Money? {
        val exponent = CurrencyExponent.of(currency)
        val (body, negative) = strip(raw) ?: return null
        if (!isAmountBody(body)) return null

        val point = body.indexOf('.')
        val whole = if (point < 0) body else body.take(point)
        val fraction = if (point < 0) "" else body.drop(point + 1)

        val minor = whole.replace(",", "").foldDigits() * pow10(exponent) +
            fraction.take(exponent).padEnd(exponent, '0').foldDigits()

        return Money(if (negative) -minor else minor)
    }

    /** The grammar an amount has to satisfy once its decoration is stripped. */
    private fun isAmountBody(body: String): Boolean {
        val digitsOnly = body.replace(",", "").replace(".", "")
        if (digitsOnly.isEmpty() || !digitsOnly.all(Char::isDigit)) return false

        val grouped = body.contains(',')
        val point = body.indexOf('.')
        val whole = if (point < 0) body else body.take(point)
        if (!grouped && point < 0 && whole.length > MAX_BARE_INTEGER_DIGITS) return false

        return GROUPED.matches(body) || FLAT.matches(body)
    }

    /**
     * The token as a quantity in thousandths, or null.
     *
     * Looser than [money] in one direction and tighter in another: three
     * decimals are allowed, because `0.500` is half a kilo and a bill prints it
     * that way, but a grouping separator is not, because nobody buys 1,200 of
     * anything on a retail slip and a comma there is far more likely to be a
     * misread price.
     *
     * A trailing unit is kept out of the way rather than interpreted —
     * `2 KG` is two of whatever the line names, and this layer has no business
     * deciding what a kilogram is.
     */
    fun quantity(raw: String): Quantity? {
        val cleaned = raw.trim()
            .removePrefix("x").removePrefix("X").removePrefix("*")
            .trim()
            .let { text -> QUANTITY_UNITS.fold(text) { acc, unit -> acc.removeSuffixIgnoringCase(unit) } }
            .trim()

        // The whole-part bound lives in the pattern rather than in a separate
        // check, so there is one place where "how long may a quantity be" is
        // answered.
        if (cleaned.contains('%') || cleaned.contains(',') || !QUANTITY.matches(cleaned)) return null

        val point = cleaned.indexOf('.')
        val whole = if (point < 0) cleaned else cleaned.take(point)
        val fraction = if (point < 0) "" else cleaned.drop(point + 1)

        val milli = whole.foldDigits() * Quantity.SCALE +
            fraction.take(QUANTITY_DECIMALS).padEnd(QUANTITY_DECIMALS, '0').foldDigits()
        return Quantity(milli).takeIf { it.isPositive }
    }

    /** True when [raw] holds nothing a reader would call a number. */
    fun isTextual(raw: String): Boolean = raw.none(Char::isDigit)

    /**
     * The currency this text announces, or null.
     *
     * Only INR is recognised, and that is not a limitation being hidden: D-02
     * gives the install one base currency, and a receipt in another is the
     * foreign-spend case, where §5.8 requires the user to supply the base
     * figure anyway. Guessing a currency from a bare `$` would be a worse
     * answer than declining to.
     */
    fun currencyMarkerIn(raw: String): String? {
        val upper = raw.uppercase()
        return if (INR_MARKERS.any { upper.contains(it) }) "INR" else null
    }

    /**
     * Removes the decoration a shop prints around an amount.
     *
     * Returns the bare numeric body and whether it was negative. Three
     * conventions mean negative and all three appear on Indian bills: a leading
     * minus, a *trailing* minus (accounting packages), and parentheses.
     */
    private fun strip(raw: String): Pair<String, Boolean>? {
        var text = raw.trim().uppercase()
        // The `%` test is deliberately redundant -- the digit check below
        // already rejects `2.5%`, and a mutation sweep confirmed that removing
        // this line turns nothing red. It stays because it is the rule a
        // reader needs to see stated, and because it is the one that would
        // still hold if the grammar below were ever loosened.
        if (text.isEmpty() || text.contains('%')) return null

        var negative = false
        if (text.startsWith('(') && text.endsWith(')')) {
            negative = true
            text = text.substring(1, text.length - 1).trim()
        }

        CURRENCY_PREFIXES.firstOrNull { text.startsWith(it) }?.let { text = text.removePrefix(it).trim() }
        VALUE_SUFFIXES.forEach { text = text.removeSuffix(it).trim() }

        if (text.startsWith('-')) {
            negative = true
            text = text.drop(1).trim()
        }
        if (text.endsWith('-')) {
            negative = true
            text = text.dropLast(1).trim()
        }
        // A separator still sitting between digits is a date or a range, never
        // an amount: `12/09/2026`, `10-12`.
        if (text.any { it == '/' || it == '-' }) return null

        return text to negative
    }

    private const val QUANTITY_DECIMALS = 3
    private val QUANTITY = Regex("""^\d{1,$MAX_UNGROUPED_DIGITS}(?:\.\d{1,$QUANTITY_DECIMALS})?$""")
    private val QUANTITY_UNITS = listOf("KG", "GM", "G", "ML", "LTR", "L", "PCS", "PC", "NOS", "NO")

    private fun String.removeSuffixIgnoringCase(suffix: String): String =
        if (endsWith(suffix, ignoreCase = true)) dropLast(suffix.length) else this

    private fun String.foldDigits(): Long =
        fold(0L) { total, character -> total * RADIX + (character - '0') }

    private const val RADIX = 10L

    private fun pow10(exponent: Int): Long {
        var result = 1L
        repeat(exponent) { result *= RADIX }
        return result
    }
}
