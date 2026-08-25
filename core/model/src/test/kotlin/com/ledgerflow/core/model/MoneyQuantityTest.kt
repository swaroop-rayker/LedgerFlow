package com.ledgerflow.core.model

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * `unit price × quantity`, which is the arithmetic every itemised line depends
 * on (SPEC.md §5.4).
 *
 * This is a Law 3 test more than an arithmetic one. The whole reason quantity
 * is stored in thousandths is that `0.5 * 9999` in binary floating point is not
 * `4999.5` and never was; what is defended here is that no input produces a
 * result a `Double` would have produced, and that the rounding a fractional
 * quantity forces is stated rather than incidental.
 */
class MoneyQuantityTest {

    private val rupee = 100L

    @Test
    fun times_wholeQuantity_isExact() {
        assertThat(Money(120_00) * Quantity.ofUnits(2)).isEqualTo(Money(240_00))
    }

    @Test
    fun times_oneUnit_isIdentity() {
        assertThat(Money(999_99) * Quantity.ONE).isEqualTo(Money(999_99))
    }

    @Test
    fun times_zeroQuantity_isZero() {
        assertThat(Money(999_99) * Quantity(0)).isEqualTo(Money.ZERO)
    }

    /** Half a kilo at ₹99.99/kg. The case the milli scale exists for. */
    @Test
    fun times_halfUnit_roundsAwayFromZero() {
        val half = Quantity(Quantity.SCALE / 2)
        assertThat(Money(99_99) * half).isEqualTo(Money(50_00))
    }

    /**
     * A discount line is negative by convention (§6.1), so it must round by the
     * same magnitude as the item it offsets. Half *up* would give −4999 here
     * against +5000 above and leave a stray paisa behind when the two cancel.
     */
    @Test
    fun times_halfUnitOfANegativeAmount_roundsToTheSameMagnitude() {
        val half = Quantity(Quantity.SCALE / 2)
        assertThat(Money(-99_99) * half).isEqualTo(Money(-50_00))
    }

    @Test
    fun times_belowHalf_roundsDown() {
        // 3 paise × 0.100 = 0.3 paise.
        assertThat(Money(3) * Quantity(100)).isEqualTo(Money.ZERO)
    }

    @Test
    fun times_aboveHalf_roundsUp() {
        // 7 paise × 0.100 = 0.7 paise.
        assertThat(Money(7) * Quantity(100)).isEqualTo(Money(1))
    }

    /**
     * Thirds do not divide, and that is fine: what must not happen is the
     * shortfall vanishing. Three at ₹33.33 is ₹99.99, and the missing paisa
     * against a ₹100 bill becomes the `UNALLOCATED` line, not a rounding fudge.
     */
    @Test
    fun times_thirds_loseNothingToRounding() {
        val line = Money(33_33) * Quantity.ofUnits(3)
        assertThat(line).isEqualTo(Money(99_99))
        assertThat(Money(100_00) - line).isEqualTo(Money(1))
    }

    /** A fractional quantity of a fractional price still never sees a `Double`. */
    @Test
    fun times_fractionalQuantityAndPrice_staysExactWhereItCan() {
        // 1.250 kg at ₹80.00/kg = ₹100.00, exactly.
        assertThat(Money(80_00) * Quantity(1_250)).isEqualTo(Money(100_00))
    }

    @Test
    fun times_largeQuantity_doesNotDriftLikeFloatingPointWould() {
        // 10,000 × ₹0.07. A Double accumulating 0.07 gets this wrong; this
        // multiplies once, in integers.
        assertThat(Money(7) * Quantity.ofUnits(10_000)).isEqualTo(Money(700_00))
    }

    @Test
    fun wholeUnits_isNullForAFractionalQuantity() {
        assertThat(Quantity.ofUnits(3).wholeUnits).isEqualTo(3)
        assertThat(Quantity(1_500).wholeUnits).isNull()
    }

    /**
     * The reason [Quantity] is a type rather than a `Long`.
     *
     * `Money(rupee) * 2` and `Money(rupee) * Quantity(2)` both read as "two",
     * and they differ by a factor of a thousand. The type is what makes the
     * wrong one impossible to write by accident; this pins the two meanings so
     * that a future change to either overload has to face the difference.
     */
    @Test
    fun times_countAndQuantity_areDifferentOperations() {
        assertThat(Money(rupee) * 2).isEqualTo(Money(200))
        assertThat(Money(rupee) * Quantity(2)).isEqualTo(Money.ZERO)
        assertThat(Money(rupee) * Quantity.ofUnits(2)).isEqualTo(Money(200))
    }
}
