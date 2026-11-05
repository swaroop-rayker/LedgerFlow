package com.ledgerflow.core.domain.statement

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import com.ledgerflow.core.domain.ingest.ExtractedDirection
import org.junit.Test

/** Amounts as statements write them, into minor units, never through floating point (Law 3). */
class StatementAmountTest {

    private fun minor(text: String, exponent: Int = 2): Long? = StatementAmount.parse(text, exponent)?.minor

    @Test
    fun theUsualShapes() {
        mapOf(
            "410" to 41_000L,
            "410.5" to 41_050L,
            "410.50" to 41_050L,
            "1,234.56" to 123_456L,
            "1,23,456.78" to 12_345_678L,
            "1.234,56" to 123_456L,
            "1 234,56" to 123_456L,
            "1,234" to 123_400L,
            "1.234" to 123_400L,
            "1,234,567" to 123_456_700L,
            "0.01" to 1L,
            ".5" to 50L,
        ).forEach { (text, expected) -> assertWithMessage(text).that(minor(text)).isEqualTo(expected) }
    }

    @Test
    fun signsAndBrackets() {
        assertThat(minor("-410.00")).isEqualTo(-41_000L)
        assertThat(minor("410.00-")).isEqualTo(-41_000L)
        assertThat(minor("(410.00)")).isEqualTo(-41_000L)
        assertThat(minor("+410.00")).isEqualTo(41_000L)
        assertThat(minor("${0x2212.toChar()}410.00")).isEqualTo(-41_000L)
    }

    @Test
    fun currencySymbolsAndCodesAreIgnored() {
        listOf("₹410.00", "Rs. 410.00", "Rs.410", "INR 410.00", "410.00 INR", "$410.00").forEach {
            assertWithMessage(it).that(minor(it)).isEqualTo(41_000L)
        }
    }

    @Test
    fun drAndCrMarksSayTheDirection() {
        assertThat(StatementAmount.parse("410.00 Dr", 2))
            .isEqualTo(StatementAmount.Parsed(41_000L, ExtractedDirection.DEBIT))
        assertThat(StatementAmount.parse("410.00Cr", 2))
            .isEqualTo(StatementAmount.Parsed(41_000L, ExtractedDirection.CREDIT))
        assertThat(StatementAmount.parse("CR 410", 2)?.marked).isEqualTo(ExtractedDirection.CREDIT)
        assertThat(StatementAmount.parse("410 debit", 2)?.marked).isEqualTo(ExtractedDirection.DEBIT)
    }

    /** BUG62's lesson: a digit in another script is its value, not its code point. */
    @Test
    fun digitsInAnyScript() {
        val arabicIndic = (0..9).map { (0x0660 + it).toChar() }
        val decimalMark = 0x066B.toChar()
        val text = "${arabicIndic[4]}${arabicIndic[1]}${arabicIndic[0]}$decimalMark${arabicIndic[5]}${arabicIndic[0]}"
        assertThat(minor(text)).isEqualTo(41_050L)
        val devanagari = (0..9).map { (0x0966 + it).toChar() }
        assertThat(minor("${devanagari[4]}${devanagari[1]}${devanagari[0]}")).isEqualTo(41_000L)
    }

    @Test
    fun zeroAndThreeDecimalCurrencies() {
        assertThat(minor("1,500", exponent = 0)).isEqualTo(1_500L)
        assertThat(minor("1.234", exponent = 3)).isEqualTo(1_234L)
        assertThat(minor("12.5", exponent = 3)).isEqualTo(12_500L)
    }

    /** More decimals than the currency has: only zeros are dropped, nothing is rounded. */
    @Test
    fun extraDecimalsAreNeverRounded() {
        assertThat(minor("410.5000")).isEqualTo(41_050L)
        assertThat(minor("410.5050")).isNull()
        // A lone separator before exactly three digits is a thousands group,
        // as in "1,234" — so "410.500" is four hundred thousand, not 410.50.
        assertThat(minor("410.500")).isEqualTo(41_050_000L)
    }

    @Test
    fun notAmounts() {
        listOf("", "  ", "abc", "02/10/2026", "UPI-123", "N/A", "12a34").forEach {
            assertWithMessage(it).that(minor(it)).isNull()
        }
    }
}
