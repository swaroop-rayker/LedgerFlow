package com.ledgerflow.feature.tools.calculator

import com.ledgerflow.core.model.Money
import java.math.BigInteger

/**
 * The calculator's keys (V2-PLAN §4.4; the owner's mockup of 2026-11-10).
 * Pressed only on its own keypad, so a digit here is always ASCII.
 */
public sealed interface CalcKey {
    public data class Digit(val value: Int) : CalcKey

    public data object DoubleZero : CalcKey

    public data object Point : CalcKey

    public data class Operator(val op: CalcOp) : CalcKey

    public data object Percent : CalcKey

    public data object Backspace : CalcKey

    public data object Clear : CalcKey

    public data object Equals : CalcKey
}

public enum class CalcOp(public val symbol: String, public val spoken: String) {
    PLUS("+", "plus"),
    MINUS("−", "minus"),
    TIMES("×", "times"),
    DIVIDE("÷", "divided by"),
    ;

    internal val binds: Boolean get() = this == TIMES || this == DIVIDE
}

/** What the keys have built: numbers and operators, alternating. */
public data class Expression(
    val tokens: List<Token> = emptyList(),
    /** True right after `=`: a digit starts afresh, an operator carries on from the answer. */
    val evaluated: Boolean = false,
) {
    public sealed interface Token {
        /** As typed, digits and at most one point; a leading minus only on an answer. */
        public data class Number(val text: String, val percent: Boolean = false) : Token

        public data class Op(val op: CalcOp) : Token
    }

    /** "100 + 20 × 3", the working line. */
    public val working: String
        get() = tokens.joinToString(" ") { token ->
            when (token) {
                is Token.Number -> token.text + if (token.percent) "%" else ""
                is Token.Op -> token.op.symbol
            }
        }
}

public sealed interface CalcResult {
    public data object Empty : CalcResult

    public data class Value(val amount: Money) : CalcResult

    public data object DivideByZero : CalcResult

    public data object TooLarge : CalcResult
}

/**
 * The calculator, owner's rules of 2026-11-10: **× and ÷ before + and −**, so
 * 100 + 20 × 3 is 160.
 *
 * **Exact until the end.** Every number is held as a fraction of two
 * `BigInteger`s, so 100 ÷ 3 × 3 is 100 and not 99.99; the answer is rounded
 * once, to the base currency's minor unit, half away from zero (half up for
 * the positive amounts this is for), and only then becomes [Money]. No
 * `Double` anywhere (Law 3).
 *
 * **%** follows the number it is on. Standing alone after + or −, it is a
 * share of everything before it: 100 + 10% is 110, 200 − 25% is 150. Anywhere
 * else it is that number ÷ 100: 200 × 10% is 20.
 */
public object Calculator {

    /** Twelve whole digits is a hundred thousand crore; six decimals is a gram of a kilo price. */
    internal const val MAX_WHOLE_DIGITS = 12
    internal const val MAX_FRACTION_DIGITS = 6

    /** Sixteen numbers and their operators: more than a receipt anyone adds up by hand. */
    internal const val MAX_TOKENS = 31

    public fun press(expression: Expression, key: CalcKey, exponent: Int): Expression = when (key) {
        is CalcKey.Digit -> typed(expression, key.value.toString())
        CalcKey.DoubleZero -> typed(expression, "00")
        CalcKey.Point -> point(expression)
        is CalcKey.Operator -> operator(expression, key.op)
        CalcKey.Percent -> percent(expression)
        CalcKey.Backspace -> backspace(expression)
        CalcKey.Clear -> Expression()
        CalcKey.Equals -> equals(expression, exponent)
    }

    /** The answer so far; a trailing operator is ignored, so "100 +" shows 100. */
    public fun evaluate(expression: Expression, exponent: Int): CalcResult {
        val tokens = expression.tokens.dropLastWhile { it is Expression.Token.Op }
        if (tokens.isEmpty()) return CalcResult.Empty
        val total = total(tokens) ?: return CalcResult.DivideByZero
        val minor = total.rounded(exponent)
        return if (minor.bitLength() < Long.SIZE_BITS) CalcResult.Value(Money(minor.toLong())) else CalcResult.TooLarge
    }

    // ── Keys ────────────────────────────────────────────────────────────────

    private fun typed(expression: Expression, digits: String): Expression {
        val current = if (expression.evaluated) Expression() else expression
        val last = current.tokens.lastOrNull()
        return when {
            last is Expression.Token.Number && !last.percent ->
                current.replaceLast(last.copy(text = appended(last.text, digits)))
            last is Expression.Token.Number -> current
            current.tokens.size >= MAX_TOKENS -> current
            else -> current.append(Expression.Token.Number(appended("", digits)))
        }
    }

    /** "0" then "5" is "5"; "00" on nothing is "0"; past the digit limits a key does nothing. */
    private fun appended(text: String, digits: String): String {
        val candidate = when (text) {
            "", "0" -> digits.trimStart('0').ifEmpty { "0" }
            "-0" -> "-" + digits.trimStart('0').ifEmpty { "0" }
            else -> text + digits
        }
        val whole = candidate.substringBefore('.').trimStart('-')
        val fraction = candidate.substringAfter('.', "")
        return if (whole.length > MAX_WHOLE_DIGITS || fraction.length > MAX_FRACTION_DIGITS) text else candidate
    }

    private fun point(expression: Expression): Expression {
        val current = if (expression.evaluated) Expression() else expression
        val last = current.tokens.lastOrNull()
        return when {
            last is Expression.Token.Number && !last.percent && '.' !in last.text ->
                current.replaceLast(last.copy(text = last.text + "."))
            last is Expression.Token.Number -> current
            current.tokens.size >= MAX_TOKENS -> current
            else -> current.append(Expression.Token.Number("0."))
        }
    }

    private fun operator(expression: Expression, op: CalcOp): Expression {
        val current = expression.copy(evaluated = false)
        return when (val last = current.tokens.lastOrNull()) {
            null -> current
            is Expression.Token.Op -> current.replaceLast(Expression.Token.Op(op))
            is Expression.Token.Number -> if (current.tokens.size >= MAX_TOKENS) {
                current
            } else {
                current.replaceLast(last.copy(text = last.text.trimEnd('.'))).append(Expression.Token.Op(op))
            }
        }
    }

    private fun percent(expression: Expression): Expression {
        val current = expression.copy(evaluated = false)
        val last = current.tokens.lastOrNull()
        return if (last is Expression.Token.Number && !last.percent) {
            current.replaceLast(last.copy(text = last.text.trimEnd('.'), percent = true))
        } else {
            current
        }
    }

    private fun backspace(expression: Expression): Expression {
        val current = expression.copy(evaluated = false)
        return when (val last = current.tokens.lastOrNull()) {
            null -> current
            is Expression.Token.Op -> current.dropLast()
            is Expression.Token.Number -> {
                val shorter = last.text.dropLast(1)
                when {
                    last.percent -> current.replaceLast(last.copy(percent = false))
                    shorter.isEmpty() || shorter == "-" -> current.dropLast()
                    else -> current.replaceLast(last.copy(text = shorter))
                }
            }
        }
    }

    /** The answer becomes the one number on the line; a refused sum leaves the line as it was. */
    private fun equals(expression: Expression, exponent: Int): Expression {
        val result = evaluate(expression, exponent)
        return if (result is CalcResult.Value) {
            Expression(listOf(Expression.Token.Number(plain(result.amount.minor, exponent))), evaluated = true)
        } else {
            expression
        }
    }

    // ── Arithmetic ──────────────────────────────────────────────────────────

    /** Sums the terms, each a product; `null` on a division by zero. */
    private fun total(tokens: List<Expression.Token>): Ratio? {
        val numbers = tokens.filterIsInstance<Expression.Token.Number>()
        val ops = tokens.filterIsInstance<Expression.Token.Op>().map { it.op }
        var sum = Ratio.ZERO
        var sign = CalcOp.PLUS
        var term = factor(numbers[0])
        for (i in ops.indices) {
            val op = ops[i]
            val number = numbers[i + 1]
            if (op.binds) {
                val right = factor(number)
                term = if (op == CalcOp.TIMES) term * right else term.dividedBy(right) ?: return null
            } else {
                sum = sum.add(term, sign)
                sign = op
                val standsAlone = i + 1 == ops.size || !ops[i + 1].binds
                term = if (number.percent && standsAlone) sum * value(number) * Ratio.HUNDREDTH else factor(number)
            }
        }
        return sum.add(term, sign)
    }

    private fun factor(number: Expression.Token.Number): Ratio =
        if (number.percent) value(number) * Ratio.HUNDREDTH else value(number)

    private fun value(number: Expression.Token.Number): Ratio {
        val text = number.text.trimEnd('.')
        val negative = text.startsWith('-')
        val digits = text.trimStart('-')
        val fraction = digits.substringAfter('.', "")
        val whole = digits.substringBefore('.').ifEmpty { "0" }
        val numerator = BigInteger(whole + fraction).let { if (negative) it.negate() else it }
        return Ratio(numerator, BigInteger.TEN.pow(fraction.length))
    }

    /** An answer in minor units as a plain number: 16000 at two digits is "160", 3333 is "33.33". */
    internal fun plain(minor: Long, exponent: Int): String {
        if (exponent == 0) return minor.toString()
        val negative = minor < 0
        val digits = (if (negative) -minor else minor).toString().padStart(exponent + 1, '0')
        val whole = digits.dropLast(exponent)
        val fraction = digits.takeLast(exponent).trimEnd('0')
        val sign = if (negative) "-" else ""
        return if (fraction.isEmpty()) "$sign$whole" else "$sign$whole.$fraction"
    }

    private fun Expression.append(token: Expression.Token) = copy(tokens = tokens + token)

    private fun Expression.replaceLast(token: Expression.Token) = copy(tokens = tokens.dropLast(1) + token)

    private fun Expression.dropLast() = copy(tokens = tokens.dropLast(1))
}

/** An exact fraction; the denominator is always positive. */
internal data class Ratio(val numerator: BigInteger, val denominator: BigInteger) {

    operator fun times(other: Ratio): Ratio =
        Ratio(numerator * other.numerator, denominator * other.denominator).reduced()

    fun dividedBy(other: Ratio): Ratio? {
        if (other.numerator.signum() == 0) return null
        val flipped = if (other.numerator.signum() < 0) {
            Ratio(other.denominator.negate(), other.numerator.negate())
        } else {
            Ratio(other.denominator, other.numerator)
        }
        return this * flipped
    }

    fun add(other: Ratio, sign: CalcOp): Ratio {
        val right = if (sign == CalcOp.MINUS) other.numerator.negate() else other.numerator
        return Ratio(numerator * other.denominator + right * denominator, denominator * other.denominator).reduced()
    }

    /** To minor units at [exponent], half away from zero. */
    fun rounded(exponent: Int): BigInteger {
        val scaled = numerator * BigInteger.TEN.pow(exponent)
        val (truncated, remainder) = scaled.divideAndRemainder(denominator).let { it[0] to it[1] }
        val roundsAway = remainder.abs().shiftLeft(1) >= denominator
        return when {
            !roundsAway -> truncated
            scaled.signum() > 0 -> truncated + BigInteger.ONE
            else -> truncated - BigInteger.ONE
        }
    }

    private fun reduced(): Ratio {
        val gcd = numerator.gcd(denominator)
        return if (gcd.signum() == 0 || gcd == BigInteger.ONE) this else Ratio(numerator / gcd, denominator / gcd)
    }

    companion object {
        val ZERO = Ratio(BigInteger.ZERO, BigInteger.ONE)
        val HUNDREDTH = Ratio(BigInteger.ONE, BigInteger.valueOf(100))
    }
}
