package com.ledgerflow.core.designsystem.format

import com.google.common.truth.Truth.assertThat
import java.util.Locale
import org.junit.Test

/**
 * Display formatting (SPEC.md §6.2, §9.6).
 *
 * The locale is pinned in every case here. Grouping is genuinely
 * locale-dependent — `en-IN` writes 1,24,000 where `en-US` writes 124,000 —
 * and a test that used the device default would pass on this machine and fail
 * on a CI runner set to anything else.
 */
class MoneyFormatTest {

    private val india = Locale.forLanguageTag("en-IN")
    private val us = Locale.US

    @Test
    fun plain_rendersTheExponentFromTheCurrency() {
        assertThat(MoneyFormat.plain(125_00L, "INR", us)).isEqualTo("125.00")
        // JPY has no minor unit; showing "12500.00" yen would be off by 100x.
        assertThat(MoneyFormat.plain(12_500L, "JPY", us)).isEqualTo("12,500")
        // BHD has three.
        assertThat(MoneyFormat.plain(1_234L, "BHD", us)).isEqualTo("1.234")
    }

    /**
     * Grouping is a property of the currency, not of the device.
     *
     * The first version of this test asked `NumberFormat` for `en-IN` and got
     * western grouping back on the desktop JDK -- the same platform-data
     * variance §5.8 refuses to trust for exponents. So the pattern is ours, and
     * a rupee amount groups as rupees whatever language the phone is set to.
     */
    @Test
    fun plain_groupsByCurrencyNotByDeviceLocale() {
        assertThat(MoneyFormat.plain(1_24_000_00L, "INR", india)).isEqualTo("1,24,000.00")
        assertThat(MoneyFormat.plain(1_24_000_00L, "INR", us)).isEqualTo("1,24,000.00")

        assertThat(MoneyFormat.plain(124_000_00L, "USD", india)).isEqualTo("124,000.00")
        assertThat(MoneyFormat.plain(124_000_00L, "USD", us)).isEqualTo("124,000.00")
    }

    @Test
    fun plain_groupsCroresTheIndianWay() {
        assertThat(MoneyFormat.plain(1_23_45_678_00L, "INR", us)).isEqualTo("1,23,45,678.00")
    }

    /**
     * Law 3 reaching the glass. 0.1 + 0.2 is the canonical binary-floating-point
     * failure; via `Double` this renders 8,415.79 or 8,415.80 depending on the
     * path, and the exact re-scaling of the `Long` cannot.
     */
    @Test
    fun plain_isExactForValuesADoubleWouldRound() {
        assertThat(MoneyFormat.plain(8_415_79L, "INR", us)).isEqualTo("8,415.79")
        assertThat(MoneyFormat.plain(1L, "INR", us)).isEqualTo("0.01")
        // The largest amount the ledger can hold, to the cent, nothing lost.
        assertThat(MoneyFormat.plain(Long.MAX_VALUE, "USD", us))
            .isEqualTo("92,233,720,368,547,758.07")
    }

    /**
     * `-Long.MIN_VALUE` is `Long.MIN_VALUE`. A formatter that flipped the sign
     * on the `Long` would print the most negative amount representable as a
     * positive one — a sign error that is silent and total.
     */
    @Test
    fun symbolised_handlesTheMostNegativeAmount() {
        assertThat(MoneyFormat.symbolised(Long.MIN_VALUE, "USD", us))
            .isEqualTo("-$92,233,720,368,547,758.08")
    }

    @Test
    fun plain_zeroIsNotBlank() {
        // An empty amount field looks unfocused rather than empty.
        assertThat(MoneyFormat.plain(0L, "INR", us)).isEqualTo("0.00")
    }

    @Test
    fun symbolised_putsTheSignBeforeTheSymbol() {
        assertThat(MoneyFormat.symbolised(1_240_50L, "INR", us)).isEqualTo("₹1,240.50")
        // "-₹5.00" reads as a negative amount; "₹-5.00" reads as a typo.
        assertThat(MoneyFormat.symbolised(-5_00L, "INR", us)).isEqualTo("-₹5.00")
    }

    @Test
    fun symbolised_unknownCurrencyFallsBackToItsCode() {
        assertThat(MoneyFormat.symbolised(1_00L, "XYZ", us)).isEqualTo("XYZ1.00")
    }

    /** §9.6: "1,240 rupees", never "1,240 ₹" — screen readers skip glyphs. */
    @Test
    fun spoken_namesTheUnitInWords() {
        assertThat(MoneyFormat.spoken(1_240_00L, "INR", us)).isEqualTo("1,240.00 rupees")
        assertThat(MoneyFormat.spoken(49_50L, "USD", us)).isEqualTo("49.50 dollars")
    }
}
