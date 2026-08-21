package com.ledgerflow.core.data.export

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Minor units to a decimal string, by integer arithmetic only (Law 3,
 * ADR-0017).
 *
 * The point of this file is the set of values it picks. `25500 / 100.0` is
 * exact, so a `Double` implementation passes any test written with round
 * numbers — and a real ledger is not made of round numbers. The cases below are
 * chosen so that a `Double` would visibly disagree, which is what makes this a
 * guard rather than a restatement of the code.
 *
 * A wrong implementation here does not fail loudly. It renders some rows a
 * paisa off and others exactly right, producing an export that disagrees with
 * the app on a subset of rows with no pattern the user could describe.
 */
class CsvMoneyTest {

    @Test
    fun wholeUnits_renderWithTwoDecimalPlaces() {
        assertThat(CsvWriter.decimal(25_500L)).isEqualTo("255.00")
        assertThat(CsvWriter.decimal(100L)).isEqualTo("1.00")
    }

    @Test
    fun zero_isNotBlank() {
        assertThat(CsvWriter.decimal(0L)).isEqualTo("0.00")
    }

    /** Below one unit the whole part is a literal zero, not an empty string. */
    @Test
    fun subUnitAmounts_keepTheLeadingZero() {
        assertThat(CsvWriter.decimal(5L)).isEqualTo("0.05")
        assertThat(CsvWriter.decimal(50L)).isEqualTo("0.50")
        assertThat(CsvWriter.decimal(99L)).isEqualTo("0.99")
        assertThat(CsvWriter.decimal(1L)).isEqualTo("0.01")
    }

    /**
     * The values a `Double` gets wrong.
     *
     * 807 / 100.0 is 8.069999999999999 in IEEE 754, and 1_000_003 / 100.0 is
     * 10000.029999999999. Both render correctly here because no float is
     * involved at any point.
     */
    @Test
    fun valuesADoubleWouldRoundWrong_areExact() {
        assertThat(CsvWriter.decimal(807L)).isEqualTo("8.07")
        assertThat(CsvWriter.decimal(1_000_003L)).isEqualTo("10000.03")
        assertThat(CsvWriter.decimal(2_29L)).isEqualTo("2.29")
        assertThat(CsvWriter.decimal(70_007L)).isEqualTo("700.07")
    }

    /**
     * A ledger larger than a `Double` can represent exactly.
     *
     * Past 2^53 minor units a `Double` stops being able to hold the integer at
     * all, let alone divide it. `Long` has no such ceiling and neither does
     * string assembly.
     */
    @Test
    fun veryLargeAmounts_staySignificant() {
        assertThat(CsvWriter.decimal(9_007_199_254_740_993L)).isEqualTo("90071992547409.93")
        assertThat(CsvWriter.decimal(Long.MAX_VALUE)).isEqualTo("92233720368547758.07")
    }

    /**
     * Negatives render as one number, not as a sign glued to a fraction.
     *
     * Amounts are stored positive — direction lives in `ledger` (§5.5) — so
     * this is defence rather than a live case for money. It is not defence for
     * `fx_rate_micro`, which shares this function and carries no such
     * constraint. The naive `minor / 100` and `minor % 100` on -5 give 0 and -5,
     * which assemble into "0.-5".
     */
    @Test
    fun negativeSubUnitAmounts_renderAsOneNumber() {
        assertThat(CsvWriter.decimal(-5L)).isEqualTo("-0.05")
        assertThat(CsvWriter.decimal(-807L)).isEqualTo("-8.07")
        assertThat(CsvWriter.decimal(-25_500L)).isEqualTo("-255.00")
    }

    /** `Math.abs(Long.MIN_VALUE)` is itself negative; the unsigned path is why. */
    @Test
    fun longMinValue_doesNotOverflowIntoANegativeFraction() {
        assertThat(CsvWriter.decimal(Long.MIN_VALUE)).isEqualTo("-92233720368547758.08")
    }

    @Test
    fun nullAmount_staysNull() {
        assertThat(CsvWriter.decimal(null)).isNull()
    }

    /**
     * The two non-money scales that share this function.
     *
     * `fx_rate_micro` is six places and `quantity_milli` is three. Getting the
     * scale from the column rather than from a constant is what stops "1.500 kg"
     * being exported as "1500.00".
     */
    @Test
    fun otherScales_areSupported() {
        assertThat(CsvWriter.decimal(1_500L, scale = 3)).isEqualTo("1.500")
        assertThat(CsvWriter.decimal(83_250_000L, scale = 6)).isEqualTo("83.250000")
        assertThat(CsvWriter.decimal(500L, scale = 6)).isEqualTo("0.000500")
    }

    @Test
    fun zeroScale_hasNoDecimalPoint() {
        assertThat(CsvWriter.decimal(42L, scale = 0)).isEqualTo("42")
    }
}
