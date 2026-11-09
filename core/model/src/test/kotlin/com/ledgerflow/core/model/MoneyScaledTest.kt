package com.ledgerflow.core.model

import com.google.common.truth.Truth.assertThat
import org.junit.Assert.assertThrows
import org.junit.Test

/** `amount × n ÷ d`, a savings goal's monthly pace (V2-PLAN §4.1). Integer only (Law 3). */
class MoneyScaledTest {

    @Test
    fun scaled_exactRatio_isExact() {
        // ₹19,350 over 90 days is ₹6,450 a month of 30 days.
        assertThat(Money(19_350_00).scaled(30, 90)).isEqualTo(Money(6_450_00))
    }

    @Test
    fun scaled_roundsHalfAwayFromZero() {
        assertThat(Money(5).scaled(1, 2)).isEqualTo(Money(3))
        assertThat(Money(-5).scaled(1, 2)).isEqualTo(Money(-3))
    }

    @Test
    fun scaled_belowHalf_truncates() {
        // 100 × 30 ÷ 7 = 428.57…
        assertThat(Money(100).scaled(30, 7)).isEqualTo(Money(429))
        // 10 × 1 ÷ 3 = 3.33…
        assertThat(Money(10).scaled(1, 3)).isEqualTo(Money(3))
    }

    @Test
    fun scaled_byZeroNumerator_isZero() {
        assertThat(Money(12_345).scaled(0, 9)).isEqualTo(Money.ZERO)
    }

    @Test
    fun scaled_refusesANonPositiveDenominator() {
        assertThrows(IllegalArgumentException::class.java) { Money(100).scaled(1, 0) }
    }
}
