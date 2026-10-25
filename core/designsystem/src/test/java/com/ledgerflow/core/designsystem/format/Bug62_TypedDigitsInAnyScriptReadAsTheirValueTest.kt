package com.ledgerflow.core.designsystem.format

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import java.util.Locale
import org.junit.Test

/**
 * **BUG62: a digit typed in Arabic-Indic, Persian or Devanagari was read as
 * its code point.**
 *
 * Found 2026-10-24 while moving amount display and parsing to `DisplayLocale`
 * (V2-3, SPEC §16 Q26), checking "what each keyboard actually sends": the
 * amount and quantity parsers kept any `isDigit` character and folded it as
 * `character - '0'`, so a keyboard in those languages turned Arabic-Indic "12" into a
 * figure in the tens of thousands. The same move would also have dropped an
 * Arabic keyboard's decimal mark (U+066B), now that the display locale's
 * separator is `.` or `,`.
 *
 * Strings are built from code points (never literal characters or escapes),
 * so what the test types is exactly what a keyboard would send.
 */
class Bug62_TypedDigitsInAnyScriptReadAsTheirValueTest {

    private val us = Locale.US

    @Test
    fun arabicIndicDigits_readAsTheirValue() {
        // Arabic-Indic 1, 2, 3, 4 -> 1234 rupees.
        assertThat(MoneyFormat.parse(digits(ARABIC_INDIC_ZERO, 1, 2, 3, 4), "INR", us)).isEqualTo(1_234_00L)
    }

    @Test
    fun persianAndDevanagariDigits_readAsTheirValue() {
        assertThat(MoneyFormat.parse(digits(PERSIAN_ZERO, 5, 0), "INR", us)).isEqualTo(50_00L)
        assertThat(MoneyFormat.parse(digits(DEVANAGARI_ZERO, 9, 9), "INR", us)).isEqualTo(99_00L)
    }

    @Test
    fun anArabicDecimalMark_isADecimalMark() {
        // "12" + U+066B + "50", ASCII digits, and again all in Arabic-Indic.
        val ascii = "12" + cp(ARABIC_DECIMAL_SEPARATOR) + "50"
        assertWithMessage(ascii).that(MoneyFormat.parse(ascii, "INR", us)).isEqualTo(12_50L)
        val arabic = digits(ARABIC_INDIC_ZERO, 1, 2) + cp(ARABIC_DECIMAL_SEPARATOR) + digits(ARABIC_INDIC_ZERO, 5)
        assertThat(MoneyFormat.parse(arabic, "INR", us)).isEqualTo(12_50L)
    }

    @Test
    fun quantities_too() {
        val twoAndAHalf = digits(ARABIC_INDIC_ZERO, 2) + cp(ARABIC_DECIMAL_SEPARATOR) + digits(ARABIC_INDIC_ZERO, 5)
        assertThat(QuantityFormat.parse(twoAndAHalf, us)).isEqualTo(2_500L)
        assertThat(QuantityFormat.parse(digits(PERSIAN_ZERO, 3), us)).isEqualTo(3_000L)
    }

    @Test
    fun asciiInput_isUnchanged() {
        assertThat(MoneyFormat.parse("1240.50", "INR", us)).isEqualTo(1_240_50L)
        assertThat(MoneyFormat.parse("12ab.5", "INR", us)).isEqualTo(12_50L)
        assertThat(QuantityFormat.parse("0.25", us)).isEqualTo(250L)
    }

    private fun cp(codePoint: Int): String = String(Character.toChars(codePoint))

    /** [values] written in the script whose zero is [zero]. */
    private fun digits(zero: Int, vararg values: Int): String = values.joinToString("") { cp(zero + it) }

    private companion object {
        const val ARABIC_INDIC_ZERO = 0x0660
        const val PERSIAN_ZERO = 0x06F0
        const val DEVANAGARI_ZERO = 0x0966
        const val ARABIC_DECIMAL_SEPARATOR = 0x066B
    }
}
