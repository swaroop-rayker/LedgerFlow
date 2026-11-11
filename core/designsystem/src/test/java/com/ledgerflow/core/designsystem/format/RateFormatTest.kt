package com.ledgerflow.core.designsystem.format

import com.google.common.truth.Truth.assertThat
import java.util.Locale
import org.junit.Test

/**
 * A rate as text (ADR-0036): six decimals, no currency, no grouping, and typed
 * digits in any script read as their value (BUG62). Code points are built
 * from numbers, as in the BUG62 test.
 */
class RateFormatTest {

    private val us = Locale.US
    private val germany = Locale.GERMANY

    @Test
    fun parse_readsSixDecimals_exactly() {
        assertThat(RateFormat.parse("88.027", us)).isEqualTo(88_027_000L)
        assertThat(RateFormat.parse("0.010645", us)).isEqualTo(10_645L)
        assertThat(RateFormat.parse("93", us)).isEqualTo(93_000_000L)
        assertThat(RateFormat.parse(".5", us)).isEqualTo(500_000L)
    }

    @Test
    fun parse_refusesWhatIsNotARate() {
        assertThat(RateFormat.parse("", us)).isNull()
        assertThat(RateFormat.parse("0", us)).isNull()
        assertThat(RateFormat.parse("1.0000001", us)).isNull()
        assertThat(RateFormat.parse("1.2.3", us)).isNull()
    }

    @Test
    fun parse_takesTheLocalesMark_andAnyScriptsDigits() {
        assertThat(RateFormat.parse("88,027", germany)).isEqualTo(88_027_000L)
        val arabicIndicZero = 0x0660
        val typed = buildString {
            append(Character.toChars(arabicIndicZero + 8))
            append(Character.toChars(arabicIndicZero + 8))
            append(Character.toChars(0x066B))
            append(Character.toChars(arabicIndicZero + 5))
        }
        assertThat(RateFormat.parse(typed, us)).isEqualTo(88_500_000L)
    }

    @Test
    fun display_isFourPlacesAtOneOrMore_sixBelow_halfUp() {
        assertThat(RateFormat.display(93_936_836L, us)).isEqualTo("93.9368")
        assertThat(RateFormat.display(93_936_850L, us)).isEqualTo("93.9369")
        assertThat(RateFormat.display(1_000_000L, us)).isEqualTo("1.0000")
        assertThat(RateFormat.display(10_645L, us)).isEqualTo("0.010645")
        assertThat(RateFormat.display(93_936_836L, germany)).isEqualTo("93,9368")
    }

    @Test
    fun plain_dropsTrailingZeros() {
        assertThat(RateFormat.plain(88_027_000L, us)).isEqualTo("88.027")
        assertThat(RateFormat.plain(93_000_000L, us)).isEqualTo("93")
        assertThat(RateFormat.plain(10_645L, us)).isEqualTo("0.010645")
    }
}
