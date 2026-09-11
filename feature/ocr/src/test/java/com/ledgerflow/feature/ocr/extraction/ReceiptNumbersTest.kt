package com.ledgerflow.feature.ocr.extraction

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import com.ledgerflow.core.model.Money
import com.ledgerflow.core.model.Quantity
import org.junit.Test

/**
 * The numeric grammar (§5.3 step 7, Law 3).
 *
 * Half of these are **rejections**, and that half matters more. A receipt is
 * covered in digits that are not money, every one of them parses under a loose
 * rule, and each produces a plausible line item — which is precisely what §12's
 * ≥95% precision half exists to catch, at a point far too late to debug.
 */
class ReceiptNumbersTest {

    // ── Accepted ────────────────────────────────────────────────────────────

    @Test
    fun money_plainDecimal_isMinorUnits() {
        assertThat(ReceiptNumbers.money("40.00")).isEqualTo(Money(4_000L))
        assertThat(ReceiptNumbers.money("694.46")).isEqualTo(Money(69_446L))
    }

    /**
     * The case a `Double` would get wrong.
     *
     * `"45.05".toDouble() * 100` is 4504.999999999999, so a pipeline that went
     * via floating point loses a paisa here and nowhere obvious enough to
     * notice. The digits are folded separately, so it cannot.
     */
    @Test
    fun money_aValueDoubleCannotRepresent_isExact() {
        assertThat(ReceiptNumbers.money("45.05")).isEqualTo(Money(4_505L))
        assertThat(ReceiptNumbers.money("0.10")).isEqualTo(Money(10L))
        assertThat(ReceiptNumbers.money("1234.35")).isEqualTo(Money(123_435L))
    }

    @Test
    fun money_wholeRupees_scaleToMinorUnits() {
        assertThat(ReceiptNumbers.money("40")).isEqualTo(Money(4_000L))
    }

    /** Indian grouping is 2,2,3, not 3,3,3. Both have to read. */
    @Test
    fun money_indianGrouping_reads() {
        assertThat(ReceiptNumbers.money("1,23,456.78")).isEqualTo(Money(12_345_678L))
        assertThat(ReceiptNumbers.money("1,234.50")).isEqualTo(Money(123_450L))
    }

    @Test
    fun money_shopDecoration_isStripped() {
        listOf("₹40.00", "Rs.40.00", "Rs 40.00", "INR 40.00", "40.00/-", "40.00*")
            .forEach { text ->
                assertWithMessage("'%s'", text)
                    .that(ReceiptNumbers.money(text))
                    .isEqualTo(Money(4_000L))
            }
    }

    /** Three conventions mean negative, and Indian bills print all three. */
    @Test
    fun money_everyNegativeConvention_reads() {
        listOf("-50.00", "50.00-", "(50.00)").forEach { text ->
            assertWithMessage("'%s'", text)
                .that(ReceiptNumbers.money(text))
                .isEqualTo(Money(-5_000L))
        }
    }

    @Test
    fun money_aZeroDecimalCurrency_doesNotGainTwoZeros() {
        assertThat(ReceiptNumbers.money("400", currency = "JPY")).isEqualTo(Money(400L))
    }

    // ── Rejected, each for its own reason ───────────────────────────────────

    /** A rate is not an amount. `CGST 2.5%` must not add 250 paise to a bill. */
    @Test
    fun money_aPercentage_isRejected() {
        assertThat(ReceiptNumbers.money("2.5%")).isNull()
        assertThat(ReceiptNumbers.money("18%")).isNull()
    }

    /**
     * A ten-digit mobile number would otherwise read as ₹98,76,543.21 — both
     * parseable and catastrophic, and printed on most Indian receipts.
     */
    @Test
    fun money_anIdentifierLengthDigitRun_isRejected() {
        assertThat(ReceiptNumbers.money("9876543210")).isNull()
        assertThat(ReceiptNumbers.money("29AAACT2727Q1ZW")).isNull()
    }

    @Test
    fun money_aDateOrRange_isRejected() {
        assertThat(ReceiptNumbers.money("12/09/2026")).isNull()
        assertThat(ReceiptNumbers.money("10-12")).isNull()
    }

    /** Three decimals is a weight or a version, not rupees. */
    @Test
    fun money_threeDecimals_isRejected() {
        assertThat(ReceiptNumbers.money("0.500")).isNull()
        assertThat(ReceiptNumbers.money("1.2.3")).isNull()
    }

    @Test
    fun money_textWithNoNumber_isRejected() {
        listOf("", "   ", "TOMATO", "TOMATO 1KG", "CHANGE").forEach { text ->
            assertWithMessage("'%s'", text).that(ReceiptNumbers.money(text)).isNull()
        }
    }

    /**
     * No glyph correction, and that is deliberate.
     *
     * OCR reads `40.00` as `4O.00` on a faded slip. Guessing that the letter is
     * a zero is a guess about an amount, and money does not get a tolerance
     * (§12). The line reaches the user with no amount, which is correctable;
     * a silently corrected digit is not.
     */
    @Test
    fun money_aMisreadDigit_isNotGuessedAt() {
        assertThat(ReceiptNumbers.money("4O.00")).isNull()
    }

    // ── Quantity ────────────────────────────────────────────────────────────

    @Test
    fun quantity_wholeAndFractional_areThousandths() {
        assertThat(ReceiptNumbers.quantity("2")).isEqualTo(Quantity(2_000L))
        assertThat(ReceiptNumbers.quantity("0.5")).isEqualTo(Quantity(500L))
        assertThat(ReceiptNumbers.quantity("0.500")).isEqualTo(Quantity(500L))
        assertThat(ReceiptNumbers.quantity("1.25")).isEqualTo(Quantity(1_250L))
    }

    @Test
    fun quantity_printedDecoration_isStripped() {
        assertThat(ReceiptNumbers.quantity("x2")).isEqualTo(Quantity(2_000L))
        assertThat(ReceiptNumbers.quantity("2 KG")).isEqualTo(Quantity(2_000L))
    }

    @Test
    fun quantity_zeroOrText_isRejected() {
        assertThat(ReceiptNumbers.quantity("0")).isNull()
        assertThat(ReceiptNumbers.quantity("TOMATO")).isNull()
        assertThat(ReceiptNumbers.quantity("2.5%")).isNull()
    }

    @Test
    fun currencyMarker_isFoundOrAbsent() {
        assertThat(ReceiptNumbers.currencyMarkerIn("TOTAL ₹694.46")).isEqualTo("INR")
        assertThat(ReceiptNumbers.currencyMarkerIn("TOTAL Rs.694.46")).isEqualTo("INR")
        assertThat(ReceiptNumbers.currencyMarkerIn("TOTAL 694.46")).isNull()
    }
}
