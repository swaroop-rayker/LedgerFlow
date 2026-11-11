package com.ledgerflow.core.rates

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import com.ledgerflow.core.model.Money
import java.time.LocalDate
import org.junit.Test

/**
 * The ECB's file, parsed strictly, and the arithmetic over it (ADR-0036 §1),
 * against the copy of the live file taken on 2026-11-10 (the ECB's table of
 * 9 Nov 2026). Expected figures were worked in exact decimal arithmetic.
 */
class EcbRatesTest {

    private val live: ByteArray = requireNotNull(javaClass.getResource("/eurofxref-daily.xml")).readBytes()
    private val table: RateTable = requireNotNull(EcbRates.parse(live))

    @Test
    fun theLiveCopy_parses_withItsDateAndEveryCurrency() {
        assertThat(table.date).isEqualTo(LocalDate.of(2026, 11, 9).toEpochDay().toInt())
        assertThat(table.perEuroMicro).hasSize(29)
        assertThat(table.perEuroMicro["INR"]).isEqualTo(105_143_500L)
        assertThat(table.perEuroMicro["GBP"]).isEqualTo(834_300L)
        assertThat(table.perEuroMicro["IDR"]).isEqualTo(20_234_310_000L)
        assertThat(table.currencies).contains("EUR")
        assertThat(table.currencies).hasSize(30)
    }

    @Test
    fun aCrossRate_goesThroughTheEuro() {
        // 105.1435 ÷ 1.1193 = 93.936835… INR per USD.
        assertThat(table.rateMicro("USD", "INR")).isEqualTo(93_936_836L)
        // 1.1193 ÷ 105.1435 = 0.010645… USD per INR.
        assertThat(table.rateMicro("INR", "USD")).isEqualTo(10_645L)
    }

    @Test
    fun anAmount_isRoundedOnce_notThroughTheShownRate() {
        // $120.00 → ₹11,272.420262…: ₹11,272.42.
        assertThat(table.convert(Money(120_00), "USD", "INR")).isEqualTo(Money(11_272_42))
        // £1.00 → $1.341603…: $1.34.
        assertThat(table.convert(Money(1_00), "GBP", "USD")).isEqualTo(Money(1_34))
    }

    @Test
    fun theEuroItself_isOneToOne_withTheTable() {
        assertThat(table.convert(Money(100_00), "EUR", "INR")).isEqualTo(Money(10_514_35))
        assertThat(table.rateMicro("EUR", "EUR")).isEqualTo(Money.RATE_SCALE)
    }

    @Test
    fun aZeroDecimalCurrency_movesTheDecimalPoint() {
        // ¥1,000 → ₹594.736693…: ₹594.74.
        assertThat(table.convert(Money(1_000), "JPY", "INR")).isEqualTo(Money(594_74))
        // ₹500.00 → ¥840.708…: ¥841.
        assertThat(table.convert(Money(500_00), "INR", "JPY")).isEqualTo(Money(841))
    }

    @Test
    fun aCurrencyTheTableLacks_convertsNothing() {
        assertThat(table.has("AED")).isFalse()
        assertThat(table.convert(Money(100_00), "AED", "INR")).isNull()
        assertThat(table.rateMicro("INR", "AED")).isNull()
    }

    @Test
    fun microOf_isExact_andRefusesWhatIsNotAPlainDecimal() {
        assertThat(EcbRates.microOf("105.1435")).isEqualTo(105_143_500L)
        assertThat(EcbRates.microOf("176.79")).isEqualTo(176_790_000L)
        assertThat(EcbRates.microOf("2")).isEqualTo(2_000_000L)
        assertThat(EcbRates.microOf("0.000001")).isEqualTo(1L)
        assertThat(EcbRates.microOf("0.0000001")).isNull()
        assertThat(EcbRates.microOf("0")).isNull()
        assertThat(EcbRates.microOf("-1.2")).isNull()
        assertThat(EcbRates.microOf("1e3")).isNull()
        assertThat(EcbRates.microOf(".5")).isNull()
        assertThat(EcbRates.microOf("1234567890123")).isNull()
    }

    @Test
    fun anythingUnexpected_refusesTheWholeFile() {
        val text = live.decodeToString()
        val dated = "<Cube time='2026-11-09'>"
        val jpy = "<Cube currency='JPY'"
        val variants = mapOf(
            "a second date" to text.replace(dated, "$dated<Cube time='2026-11-08'>"),
            "a repeated currency" to text.replace(jpy, "<Cube currency='USD' rate='1.2'/>$jpy"),
            "the euro against itself" to text.replace(jpy, "<Cube currency='EUR' rate='1'/>$jpy"),
            "an unknown cube" to text.replace(jpy, "<Cube kind='x'/>$jpy"),
            "a signed rate" to text.replace("rate='1.1193'", "rate='-1.1193'"),
            "seven decimals" to text.replace("rate='1.1193'", "rate='1.1193001'"),
            "an impossible date" to text.replace("2026-11-09", "2026-13-40"),
            "no date" to text.replace("<Cube time='2026-11-09'>", "<Cube>"),
            "another namespace" to text.replace("http://www.ecb.int/vocabulary/2002-08-01/eurofxref", "urn:other"),
            "no rates" to text.replace(Regex("""<Cube currency=[^>]*/>"""), ""),
            "not the file at all" to "<html><body>Service unavailable</body></html>",
        )
        variants.forEach { (why, variant) ->
            assertWithMessage(why).that(EcbRates.parse(variant.toByteArray())).isNull()
        }
    }

    @Test
    fun doubleQuotes_andSpacing_areTheSameFile() {
        val text = live.decodeToString().replace('\'', '"').replace("/>", " />")
        assertThat(EcbRates.parse(text.toByteArray())).isEqualTo(table)
    }
}
