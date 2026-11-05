package com.ledgerflow.core.domain.statement

import com.ledgerflow.core.domain.ingest.ExtractedDirection

/**
 * An amount as a statement writes it, read into minor units (V2-4 Import).
 *
 * **Law 3: no floating point anywhere on this path.** The digits are collected
 * as text and assembled into a `Long` with integer arithmetic; a value with
 * more decimals than the currency has is refused rather than rounded, because
 * a rounded amount is a wrong amount that looks right.
 *
 * What it reads: `1,234.56`, `1.234,56`, `1 234,56`, Indian grouping
 * `1,23,456.78`, `(1,234.56)` and a leading or trailing minus as negative,
 * `1,234.56 Dr` / `Cr` (and `Debit` / `Credit`), currency symbols and codes
 * (`₹`, `Rs.`, `INR`), and digits in any script (`Character.digit`, BUG62's
 * lesson: never `c - '0'`).
 */
public object StatementAmount {

    /** A signed amount in minor units, and the direction a Dr/Cr mark stated, if any. */
    public data class Parsed(val minor: Long, val marked: ExtractedDirection?)

    /** Null when [text] holds no amount, or one this reader will not guess at. */
    public fun parse(text: String, exponent: Int): Parsed? {
        val trimmed = text.trim()
        val mark = MARK.find(trimmed)
        val marked = mark?.let { markOf(it.groupValues[1]) }
        val rest = CURRENCY_WORD.replace(mark?.let { trimmed.removeRange(it.range) } ?: trimmed, " ")
        val scan = scan(rest)?.takeIf { it.digits.isNotEmpty() && it.digits.length <= MAX_DIGITS }
        val minor = scan?.let { minorUnits(it, exponent) } ?: return null
        return Parsed(minor = if (scan.negative) -minor else minor, marked = marked)
    }

    /** The characters of an amount, sorted: its digits, where its separators were, and its sign. */
    private class Scan(val digits: String, val separators: List<Pair<Int, Char>>, val negative: Boolean)

    /**
     * Null on anything that is not part of an amount — letters left over, a
     * stray symbol. Better to say "couldn't read" than to guess.
     */
    private fun scan(text: String): Scan? {
        var negative = false
        val digits = StringBuilder()
        val separators = mutableListOf<Pair<Int, Char>>()
        for (char in text) {
            val digit = Character.digit(char, RADIX)
            when {
                digit >= 0 -> digits.append(digit)
                char in NEGATIVE -> negative = true
                char in SEPARATORS -> separators += digits.length to char
                char in IGNORED || char.isWhitespace() || isCurrencySymbol(char) -> Unit
                else -> return null
            }
        }
        return Scan(digits.toString(), separators, negative)
    }

    /**
     * The digits as minor units. More decimals than the currency has are
     * accepted only when they are zeros (2.500 for a two-decimal currency):
     * never rounded.
     */
    private fun minorUnits(scan: Scan, exponent: Int): Long? {
        val fractionDigits = decimalPlaces(scan.separators, scan.digits.length, exponent)
        val surplus = fractionDigits - exponent
        val kept = if (surplus > 0) scan.digits.dropLast(surplus) else scan.digits
        val dropped = if (surplus > 0) scan.digits.takeLast(surplus) else ""
        return if (dropped.any { it != '0' }) null else (kept + "0".repeat(maxOf(-surplus, 0))).toLong()
    }

    private fun isCurrencySymbol(char: Char): Boolean = Character.getType(char) == Character.CURRENCY_SYMBOL.toInt()

    private fun markOf(word: String): ExtractedDirection =
        if (word.lowercase().startsWith("d")) ExtractedDirection.DEBIT else ExtractedDirection.CREDIT

    /**
     * How many of the digits follow the decimal mark. The last separator is
     * the decimal one when both kinds appear; a lone separator that repeats,
     * or that is followed by exactly three digits, is a thousands separator —
     * except in a three-decimal currency, where `1.234` is one and a bit.
     */
    private fun decimalPlaces(separators: List<Pair<Int, Char>>, digitCount: Int, exponent: Int): Int {
        if (separators.isEmpty()) return 0
        val (position, mark) = separators.last()
        val after = digitCount - position
        val kinds = separators.map { it.second }.toSet()
        return when {
            mark == ARABIC_DECIMAL -> after
            kinds.size > 1 -> after
            separators.size > 1 -> 0
            after == THOUSANDS_GROUP && exponent != THOUSANDS_GROUP -> 0
            else -> after
        }
    }

    private const val RADIX = 10
    private const val MAX_DIGITS = 17
    private const val THOUSANDS_GROUP = 3
    private val MINUS_SIGN = 0x2212.toChar()
    private val ARABIC_DECIMAL = 0x066B.toChar()
    private val ARABIC_THOUSANDS = 0x066C.toChar()
    private val NEGATIVE = setOf('-', MINUS_SIGN, '(')
    private val SEPARATORS = setOf('.', ',', ARABIC_DECIMAL)
    /** Thousands marks and the rest of a sign: present, and meaning nothing for the value. */
    private val IGNORED = setOf(ARABIC_THOUSANDS, '\'', ')', '+')

    /**
     * A Dr/Cr mark before or after the number. Letters on neither side, not
     * `\b`: `1,234.56Dr` has no word boundary before the D.
     */
    private val MARK = Regex("""(?i)(?<![a-z])(dr|cr|debit|credit)(?![a-z])\.?""")

    /** Currency names a statement writes in letters; symbols are dropped by category. */
    private val CURRENCY_WORD = Regex("""(?i)(?<![a-z])(rs|inr|usd|eur|gbp|aed|sgd)(?![a-z])\.?""")
}
