package com.ledgerflow.core.designsystem.format

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import com.ledgerflow.core.model.NumberGrouping
import java.util.Locale
import org.junit.After
import org.junit.Before
import org.junit.Test

/**
 * Settings > Currency & region for amounts (V2-PLAN §3.3), and SPEC §16 Q26:
 * an amount is written, and what the user types is read, in English in the
 * phone's region -- the two together.
 */
class AmountConventionsTest {

    private lateinit var hostLocale: Locale
    private lateinit var hostConventions: com.ledgerflow.core.model.RegionConventions

    @Before
    fun saveGlobals() {
        hostLocale = Locale.getDefault()
        hostConventions = DisplayConventions.current
    }

    @After
    fun restoreGlobals() {
        Locale.setDefault(hostLocale)
        DisplayConventions.current = hostConventions
    }

    @Test
    fun byCurrency_isWhatTheAppAlwaysDid() {
        val us = Locale.US
        assertThat(MoneyFormat.plain(1_234_567_00L, "INR", us, NumberGrouping.BY_CURRENCY)).isEqualTo("12,34,567.00")
        assertThat(MoneyFormat.plain(1_234_567_00L, "USD", us, NumberGrouping.BY_CURRENCY)).isEqualTo("1,234,567.00")
    }

    @Test
    fun lakhAndWestern_applyToEveryCurrency() {
        val us = Locale.US
        assertThat(MoneyFormat.plain(1_234_567_00L, "USD", us, NumberGrouping.LAKH)).isEqualTo("12,34,567.00")
        assertThat(MoneyFormat.plain(1_234_567_00L, "INR", us, NumberGrouping.WESTERN)).isEqualTo("1,234,567.00")
        assertThat(MoneyFormat.symbolised(1_234_567_00L, "INR", us, NumberGrouping.WESTERN)).isEqualTo("₹1,234,567.00")
    }

    @Test
    fun theSetting_reachesEveryAmountByDefault() {
        DisplayConventions.current = DisplayConventions.current.copy(grouping = NumberGrouping.WESTERN)
        assertThat(MoneyFormat.symbolised(1_234_567_00L, "INR", Locale.US)).isEqualTo("₹1,234,567.00")
        assertThat(MoneyFormat.plain(1_234_567_00L, "INR", Locale.US)).isEqualTo("1,234,567.00")
    }

    @Test
    fun underAnArabicPhone_amountsAreWrittenWithEnglishSeparators() {
        // SPEC §16 Q26. Arabic's own separators are U+066B and U+066C.
        Locale.setDefault(Locale.forLanguageTag("ar-EG"))
        val shown = MoneyFormat.symbolised(1_234_567_50L, "INR")
        val arabic = shown.filter { Character.UnicodeBlock.of(it) == Character.UnicodeBlock.ARABIC }
        assertWithMessage("\"$shown\" under ar-EG").that(arabic).isEmpty()
        assertThat(shown).endsWith("12,34,567.50")
    }

    @Test
    fun underAnArabicPhone_whatIsShownIsReadBackExactly() {
        // Display and parse moved together: the field reads what the screen wrote.
        Locale.setDefault(Locale.forLanguageTag("ar-EG"))
        listOf(0L, 5L, 1_240_50L, 1_234_567_89L).forEach { minor ->
            assertThat(MoneyFormat.parse(MoneyFormat.plain(minor, "INR").filterNot { it == ',' }, "INR"))
                .isEqualTo(minor)
        }
        assertThat(QuantityFormat.parse(QuantityFormat.plain(2_500L))).isEqualTo(2_500L)
        assertThat(QuantityFormat.plain(2_500L)).isEqualTo("2.5")
    }
}
