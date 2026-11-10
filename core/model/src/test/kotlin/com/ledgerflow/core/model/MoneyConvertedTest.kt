package com.ledgerflow.core.model

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** `amount × rate ÷ per` across exponents: the converter's figure (ADR-0036). Integer only (Law 3). */
class MoneyConvertedTest {

    @Test
    fun aTypedRate_convertsAtItsScale() {
        // $120.00 at 88.0270 is ₹10,563.24 exactly.
        assertThat(Money(120_00).converted(88_027_000, Money.RATE_SCALE, 2, 2)).isEqualTo(Money(10_563_24))
    }

    @Test
    fun roundsOnce_halfAwayFromZero() {
        assertThat(Money(1).converted(1, 2, 0, 0)).isEqualTo(Money(1))
        assertThat(Money(3).converted(1, 2, 0, 0)).isEqualTo(Money(2))
        assertThat(Money(-1).converted(1, 2, 0, 0)).isEqualTo(Money(-1))
        // 10 × 1 ÷ 3 = 3.33…
        assertThat(Money(10).converted(1, 3, 0, 0)).isEqualTo(Money(3))
    }

    @Test
    fun movesTheDecimalPointBetweenExponents() {
        // ¥1,000 (no minor unit) at 0.594737 INR per yen is ₹594.74 (two).
        assertThat(Money(1_000).converted(594_737, Money.RATE_SCALE, 0, 2)).isEqualTo(Money(594_74))
        // ₹500.00 at 1.681 yen per rupee is ¥841 (840.5 rounds up).
        assertThat(Money(500_00).converted(1_681_000, Money.RATE_SCALE, 2, 0)).isEqualTo(Money(841))
    }

    /** The product outgrows a `Long` long before the answer does. */
    @Test
    fun aLargeProduct_doesNotOverflow() {
        val trillion = Money(1_000_000_000_000L)
        assertThat(trillion.converted(20_234_310_000L, 20_234_310_000L, 2, 2)).isEqualTo(trillion)
    }

    @Test
    fun anAnswerTooLargeForALong_isNull() {
        assertThat(Money(Long.MAX_VALUE).converted(2, 1, 2, 2)).isNull()
    }

    @Test
    fun aNonPositiveRatio_isNull() {
        assertThat(Money(100).converted(0, Money.RATE_SCALE, 2, 2)).isNull()
        assertThat(Money(100).converted(1, 0, 2, 2)).isNull()
        assertThat(Money(100).converted(-1, Money.RATE_SCALE, 2, 2)).isNull()
    }
}
