package com.ledgerflow.core.designsystem.format

import com.google.common.truth.Truth.assertThat
import com.ledgerflow.core.model.LedgerType
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

    // ── parse: what a person typed -> minor units ───────────────────────────

    @Test
    fun parse_readsWholeAmountsAsMajorUnits() {
        // The whole reason for moving off the keypad: type 125, get ₹125.
        assertThat(MoneyFormat.parse("125", "INR", us)).isEqualTo(125_00L)
        assertThat(MoneyFormat.parse("0", "INR", us)).isEqualTo(0L)
    }

    @Test
    fun parse_readsDecimals() {
        assertThat(MoneyFormat.parse("125.50", "INR", us)).isEqualTo(125_50L)
        // A single decimal digit is tenths, not hundredths.
        assertThat(MoneyFormat.parse("125.5", "INR", us)).isEqualTo(125_50L)
        assertThat(MoneyFormat.parse("0.07", "INR", us)).isEqualTo(7L)
    }

    /**
     * Law 3 at the point of entry. `"8415.79".toDouble() * 100` is
     * 841578.9999999999, which floors to 841578 -- one paise short, silently,
     * on the very first thing the app learns about the user's money.
     */
    @Test
    fun parse_isExactWhereADoubleWouldNotBe() {
        assertThat(MoneyFormat.parse("8415.79", "INR", us)).isEqualTo(841_579L)
        assertThat(MoneyFormat.parse("0.29", "INR", us)).isEqualTo(29L)
        assertThat(MoneyFormat.parse("1.15", "INR", us)).isEqualTo(115L)
    }

    /** Truncated, not rounded: the third digit is still being typed. */
    @Test
    fun parse_truncatesBeyondTheCurrencyExponent() {
        assertThat(MoneyFormat.parse("12.567", "INR", us)).isEqualTo(12_56L)
        assertThat(MoneyFormat.parse("12.999", "INR", us)).isEqualTo(12_99L)
    }

    @Test
    fun parse_honoursTheCurrencyExponent() {
        // JPY has no minor unit at all.
        assertThat(MoneyFormat.parse("1250", "JPY", us)).isEqualTo(1_250L)
        assertThat(MoneyFormat.parse("1250.9", "JPY", us)).isEqualTo(1_250L)
        // BHD has three.
        assertThat(MoneyFormat.parse("1.234", "BHD", us)).isEqualTo(1_234L)
    }

    /**
     * A keyboard is a hint, not a contract: `KeyboardType.Decimal` is advisory
     * and some OEM keyboards serve a full QWERTY anyway. Junk is discarded
     * rather than rejected, so the field never fights the user mid-keystroke.
     */
    @Test
    fun parse_discardsAnythingThatIsNotADigitOrASeparator() {
        assertThat(MoneyFormat.parse("12ab.5", "INR", us)).isEqualTo(12_50L)
        assertThat(MoneyFormat.parse("₹1,250.00", "INR", us)).isEqualTo(1_250_00L)
        assertThat(MoneyFormat.parse("  ", "INR", us)).isEqualTo(0L)
        assertThat(MoneyFormat.parse("abc", "INR", us)).isEqualTo(0L)
    }

    /** Half-typed states a live field really produces. */
    @Test
    fun parse_handlesPartialInput() {
        assertThat(MoneyFormat.parse(".", "INR", us)).isEqualTo(0L)
        assertThat(MoneyFormat.parse("12.", "INR", us)).isEqualTo(12_00L)
        assertThat(MoneyFormat.parse(".5", "INR", us)).isEqualTo(50L)
        // Only the first separator splits; the rest are noise.
        assertThat(MoneyFormat.parse("1.2.3", "INR", us)).isEqualTo(1_23L)
    }

    /**
     * A long paste must not fold past `Long.MAX_VALUE` and come back negative,
     * which would file a large expense as income.
     */
    @Test
    fun parse_cannotOverflowIntoANegativeAmount() {
        val parsed = MoneyFormat.parse("9".repeat(40), "INR", us)

        assertThat(parsed).isGreaterThan(0L)
        assertThat(parsed).isEqualTo(999_999_999_999_00L)
    }

    /** What is typed and what is displayed have to agree. */
    @Test
    fun parse_roundTripsWithPlain() {
        listOf(0L, 1L, 7L, 125_00L, 8_415_79L, 1_24_000_00L).forEach { minor ->
            val rendered = MoneyFormat.plain(minor, "INR", us)
            assertThat(MoneyFormat.parse(rendered, "INR", us)).isEqualTo(minor)
        }
    }

    /** §9.6: "1,240 rupees", never "1,240 ₹" — screen readers skip glyphs. */
    @Test
    fun spoken_namesTheUnitInWords() {
        assertThat(MoneyFormat.spoken(1_240_00L, "INR", us)).isEqualTo("1,240.00 rupees")
        assertThat(MoneyFormat.spoken(49_50L, "USD", us)).isEqualTo("49.50 dollars")
    }

    // ── Directional prefix (CHANGE#3) ───────────────────────────────────────

    @Test
    fun directional_prefixesADebitWithMinus() {
        assertThat(MoneyFormat.directional(1_240_50L, "INR", LedgerType.DEBIT, us))
            .isEqualTo("-₹1,240.50")
    }

    @Test
    fun directional_prefixesACreditWithPlus() {
        assertThat(MoneyFormat.directional(85_000_00L, "INR", LedgerType.CREDIT, us))
            .isEqualTo("+₹85,000.00")
    }

    /**
     * The sign comes from the book, not from the number.
     *
     * This is the assertion that keeps Law 2 true at the presentation layer: the
     * same positive `Long` renders with either prefix depending only on which
     * ledger it belongs to. If someone ever "simplifies" this by negating the
     * minor units for debits, a negative amount is on the path again and this
     * fails.
     */
    @Test
    fun directional_readsTheBookRatherThanTheSignOfTheAmount() {
        val minor = 500_00L

        assertThat(MoneyFormat.directional(minor, "INR", LedgerType.DEBIT, us))
            .isEqualTo("-₹500.00")
        assertThat(MoneyFormat.directional(minor, "INR", LedgerType.CREDIT, us))
            .isEqualTo("+₹500.00")
    }

    /** Grouping still follows the currency, exactly as [MoneyFormat.symbolised] does. */
    @Test
    fun directional_keepsLakhGrouping() {
        assertThat(MoneyFormat.directional(1_24_000_00L, "INR", LedgerType.DEBIT, us))
            .isEqualTo("-₹1,24,000.00")
    }

    /**
     * TalkBack gets a word, not a character.
     *
     * Screen readers skip "-" and "+" as often as they skip the currency glyph,
     * so a spoken row would otherwise carry the amount with the direction
     * silently dropped -- the one thing the prefix exists to convey (§9.6).
     */
    @Test
    fun spokenDirectional_saysTheDirectionAsAWord() {
        assertThat(MoneyFormat.spokenDirectional(1_240_50L, "INR", LedgerType.DEBIT, us))
            .isEqualTo("spent 1,240.50 rupees")
        assertThat(MoneyFormat.spokenDirectional(1_240_50L, "INR", LedgerType.CREDIT, us))
            .isEqualTo("received 1,240.50 rupees")
    }

}
