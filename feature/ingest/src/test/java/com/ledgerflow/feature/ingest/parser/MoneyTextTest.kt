package com.ledgerflow.feature.ingest.parser

import com.google.common.truth.Truth.assertThat
import com.ledgerflow.core.model.Money
import org.junit.Test

/**
 * Law 3, at the point money enters the app from the outside.
 *
 * Every amount in LedgerFlow that did not come from a keyboard comes through
 * here, and the failure this guards is the quietest one in the codebase:
 * `"12.35".toDouble() * 100` is `1234.9999999999998`, which truncates to 1234
 * and loses a paisa. It would be wrong on a small fraction of transactions,
 * forever, and no user would ever be able to say which.
 *
 * So the assertions are on exact minor units, and several of these cases exist
 * only because they are the ones a floating-point implementation gets wrong.
 */
class MoneyTextTest {

    private fun inr(text: String): Money? = MoneyText.parse(text, "INR")

    @Test
    fun parsesAPlainAmount() {
        assertThat(inr("240.50")).isEqualTo(Money(24050))
    }

    /**
     * The cases a `Double` round-trip gets wrong.
     *
     * Each of these has a binary representation slightly below the decimal one,
     * so multiplying by 100 and truncating loses the last unit.
     */
    @Test
    fun parsesAmountsThatFloatingPointWouldRoundDown() {
        assertThat(inr("12.35")).isEqualTo(Money(1235))
        assertThat(inr("1.15")).isEqualTo(Money(115))
        assertThat(inr("4.35")).isEqualTo(Money(435))
        assertThat(inr("8.87")).isEqualTo(Money(887))
        assertThat(inr("100000.05")).isEqualTo(Money(10000005))
    }

    /** Indian grouping is not every three digits, so separators are removed rather than parsed. */
    @Test
    fun parsesIndianGrouping() {
        assertThat(inr("1,24,000.00")).isEqualTo(Money(12400000))
        assertThat(inr("1,000")).isEqualTo(Money(100000))
        assertThat(inr("12,34,56,789.99")).isEqualTo(Money(12345678999))
    }

    /** A short fraction is padded: "1240.5" in rupees is 1240.50, not 1240.05. */
    @Test
    fun padsAFractionShorterThanTheCurrencyExponent() {
        assertThat(inr("1240.5")).isEqualTo(Money(124050))
        assertThat(inr("7.1")).isEqualTo(Money(710))
    }

    /**
     * A longer fraction is truncated, never rounded.
     *
     * Rounding here would silently disagree with the bank's own figure, and the
     * bank's figure is the one the user will reconcile against.
     */
    @Test
    fun truncatesAFractionLongerThanTheCurrencyExponent() {
        assertThat(inr("1240.509")).isEqualTo(Money(124050))
        assertThat(inr("1240.999")).isEqualTo(Money(124099))
    }

    @Test
    fun parsesWholeRupees() {
        assertThat(inr("300")).isEqualTo(Money(30000))
    }

    /** A zero-decimal currency has no minor unit to pad. */
    @Test
    fun respectsTheCurrencyExponent() {
        assertThat(MoneyText.parse("1200", "JPY")).isEqualTo(Money(1200))
        assertThat(MoneyText.parse("1200", "INR")).isEqualTo(Money(120000))
        // Three-decimal.
        assertThat(MoneyText.parse("1.5", "KWD")).isEqualTo(Money(1500))
    }

    /** Some statement formats put the sign after the figure. Magnitude is what matters here. */
    @Test
    fun toleratesSurroundingSigns() {
        assertThat(inr("1,240.00-")).isEqualTo(Money(124000))
        assertThat(inr("+240.50")).isEqualTo(Money(24050))
        assertThat(inr("-240.50")).isEqualTo(Money(-24050))
    }

    /**
     * Anything that is not an amount is null, never zero.
     *
     * A silent zero would offer the user a confident ₹0.00 to approve; null
     * makes the row `needs_manual_fill`, which is the honest outcome.
     */
    @Test
    fun refusesWhatIsNotAnAmount() {
        assertThat(inr("")).isNull()
        assertThat(inr("   ")).isNull()
        assertThat(inr("abc")).isNull()
        assertThat(inr("12.34.56")).isNull()
        assertThat(inr("12a.34")).isNull()
        assertThat(inr("Rs.240")).isNull()
    }
}
