package com.ledgerflow.feature.tools.calculator

import com.google.common.truth.Truth.assertThat
import com.ledgerflow.core.model.Money
import org.junit.Test

/**
 * The calculator's rules (owner, 2026-11-10): × and ÷ before + and −, exact
 * until the answer, which rounds once to the paisa, half up; % as on the
 * mockup. Driven key by key, as the keypad drives it.
 */
class CalculatorTest {

    private fun keys(text: String, exponent: Int = 2): Expression =
        text.filterNot { it == ' ' }.fold(Expression()) { expression, char ->
            Calculator.press(expression, key(char), exponent)
        }

    private fun key(char: Char): CalcKey = when (char) {
        in '0'..'9' -> CalcKey.Digit(char.digitToInt())
        'Z' -> CalcKey.DoubleZero
        '.' -> CalcKey.Point
        '+' -> CalcKey.Operator(CalcOp.PLUS)
        '-' -> CalcKey.Operator(CalcOp.MINUS)
        '*' -> CalcKey.Operator(CalcOp.TIMES)
        '/' -> CalcKey.Operator(CalcOp.DIVIDE)
        '%' -> CalcKey.Percent
        '<' -> CalcKey.Backspace
        'C' -> CalcKey.Clear
        '=' -> CalcKey.Equals
        else -> error("no key for $char")
    }

    private fun answer(text: String, exponent: Int = 2): CalcResult =
        Calculator.evaluate(keys(text, exponent), exponent)

    @Test
    fun timesAndDivide_bindBeforePlusAndMinus() {
        assertThat(answer("100 + 20 * 3")).isEqualTo(CalcResult.Value(Money(160_00)))
        assertThat(answer("100 - 50 / 2")).isEqualTo(CalcResult.Value(Money(75_00)))
        assertThat(answer("2 * 3 + 4 * 5")).isEqualTo(CalcResult.Value(Money(26_00)))
    }

    @Test
    fun theWorkingLine_showsTheWholeSum() {
        assertThat(keys("100 + 20 * 3").working).isEqualTo("100 + 20 × 3")
        assertThat(keys("100 + 10%").working).isEqualTo("100 + 10%")
    }

    /** Exact until the end: a third of a hundred, times three, is a hundred. */
    @Test
    fun fractionsStayExact_untilTheAnswer() {
        assertThat(answer("100 / 3 * 3")).isEqualTo(CalcResult.Value(Money(100_00)))
        assertThat(answer("100 / 3")).isEqualTo(CalcResult.Value(Money(33_33)))
        assertThat(answer("200 / 3")).isEqualTo(CalcResult.Value(Money(66_67)))
    }

    @Test
    fun theAnswer_roundsOnce_halfUp() {
        // 0.125 × 1 = ₹0.125: half a paisa rounds up.
        assertThat(answer("0.125 * 1")).isEqualTo(CalcResult.Value(Money(13)))
        assertThat(answer("0.124")).isEqualTo(CalcResult.Value(Money(12)))
        // A kilo price times grams: 0.375 kg at ₹99.99 is ₹37.49625.
        assertThat(answer("99.99 * 0.375")).isEqualTo(CalcResult.Value(Money(37_50)))
    }

    @Test
    fun aZeroDecimalCurrency_roundsToTheWholeUnit() {
        assertThat(answer("1000 / 3", exponent = 0)).isEqualTo(CalcResult.Value(Money(333)))
        assertThat(answer("2.5", exponent = 0)).isEqualTo(CalcResult.Value(Money(3)))
    }

    @Test
    fun percent_isAShareOfWhatCameBefore_whenItStandsAlone() {
        assertThat(answer("100 + 10%")).isEqualTo(CalcResult.Value(Money(110_00)))
        assertThat(answer("200 - 25%")).isEqualTo(CalcResult.Value(Money(150_00)))
        assertThat(answer("50 + 50 + 10%")).isEqualTo(CalcResult.Value(Money(110_00)))
    }

    @Test
    fun percent_isAHundredth_anywhereElse() {
        assertThat(answer("200 * 10%")).isEqualTo(CalcResult.Value(Money(20_00)))
        assertThat(answer("10%")).isEqualTo(CalcResult.Value(Money(10)))
        // The 10% belongs to the × term, not to the sum: 100 + 2.
        assertThat(answer("100 + 20 * 10%")).isEqualTo(CalcResult.Value(Money(102_00)))
    }

    @Test
    fun aTrailingOperator_isIgnoredInTheAnswer() {
        assertThat(answer("100 +")).isEqualTo(CalcResult.Value(Money(100_00)))
        assertThat(answer("")).isEqualTo(CalcResult.Empty)
    }

    @Test
    fun dividingByZero_isRefused_andEqualsLeavesTheLine() {
        assertThat(answer("5 / 0")).isEqualTo(CalcResult.DivideByZero)
        assertThat(keys("5 / 0 =").working).isEqualTo("5 ÷ 0")
    }

    @Test
    fun anAnswerTooLargeForALong_isRefused() {
        assertThat(answer("999999999999 * 999999999999")).isEqualTo(CalcResult.TooLarge)
    }

    @Test
    fun equals_makesTheAnswerTheLine_andADigitStartsAfresh() {
        val answered = keys("100 + 20 * 3 =")
        assertThat(answered.working).isEqualTo("160")
        assertThat(Calculator.press(answered, CalcKey.Digit(7), 2).working).isEqualTo("7")
        assertThat(Calculator.press(answered, CalcKey.Operator(CalcOp.PLUS), 2).working).isEqualTo("160 +")
        assertThat(keys("10 / 4 =").working).isEqualTo("2.5")
        assertThat(keys("1 - 3 =").working).isEqualTo("-2")
    }

    @Test
    fun typing_isTidy() {
        assertThat(keys("0 0 5").working).isEqualTo("5")
        assertThat(keys("Z").working).isEqualTo("0")
        assertThat(keys("5 Z").working).isEqualTo("500")
        assertThat(keys(".5").working).isEqualTo("0.5")
        assertThat(keys("1..2").working).isEqualTo("1.2")
        // A second operator replaces the first; an operator on nothing does nothing.
        assertThat(keys("5 + *").working).isEqualTo("5 ×")
        assertThat(keys("+ 5").working).isEqualTo("5")
        // A dangling point is dropped when the number is finished.
        assertThat(keys("5. +").working).isEqualTo("5 +")
    }

    @Test
    fun theDigitLimits_holdTheNumber() {
        assertThat(keys("1234567890123").working).isEqualTo("123456789012")
        assertThat(keys("1.12345678").working).isEqualTo("1.123456")
    }

    @Test
    fun backspace_undoesOneKeyAtATime() {
        assertThat(keys("12 + 3 <").working).isEqualTo("12 +")
        assertThat(keys("12 + 3 < <").working).isEqualTo("12")
        assertThat(keys("12% <").working).isEqualTo("12")
        assertThat(keys("1 <").working).isEqualTo("")
    }

    @Test
    fun backspace_onANegativeAnswer_neverLeavesALoneMinus() {
        assertThat(keys("1 - 3 = <").tokens).isEmpty()
    }

    @Test
    fun clear_emptiesEverything() {
        assertThat(keys("12 + 3 C").tokens).isEmpty()
    }

    @Test
    fun plain_writesAnAnswerAsTheKeysWould() {
        assertThat(Calculator.plain(16_000, 2)).isEqualTo("160")
        assertThat(Calculator.plain(3_333, 2)).isEqualTo("33.33")
        assertThat(Calculator.plain(5, 2)).isEqualTo("0.05")
        assertThat(Calculator.plain(-250, 2)).isEqualTo("-2.5")
        assertThat(Calculator.plain(841, 0)).isEqualTo("841")
    }
}
