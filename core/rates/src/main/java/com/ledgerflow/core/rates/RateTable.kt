package com.ledgerflow.core.rates

import com.ledgerflow.core.model.CurrencyExponent
import com.ledgerflow.core.model.Money

/**
 * One day's ECB reference rates (ADR-0036 §1): units of each currency per one
 * euro, in micro-units (× 10⁶, the scale of `fx_rate_micro`), and the date the
 * ECB stamped on the table, as an epoch day.
 *
 * The euro is not in [perEuroMicro]; it is the table's base, one to one.
 * Rates are ratios, not money, but they stay `Long` so that the arithmetic is
 * exact and on the scale the rest of the app uses.
 */
public data class RateTable(
    val date: Int,
    val perEuroMicro: Map<String, Long>,
) {

    /** Every currency the table can convert, the euro included, sorted. */
    public val currencies: List<String> get() = (perEuroMicro.keys + EURO).sorted()

    public fun has(currency: String): Boolean = currency == EURO || currency in perEuroMicro

    /**
     * [amount] in [from] as [to], through the euro, rounded once (see
     * [Money.converted]). `null` when the table lacks either currency.
     */
    public fun convert(amount: Money, from: String, to: String): Money? {
        val fromPerEuro = perEuro(from) ?: return null
        val toPerEuro = perEuro(to) ?: return null
        return amount.converted(
            rate = toPerEuro,
            per = fromPerEuro,
            fromExponent = CurrencyExponent.of(from),
            toExponent = CurrencyExponent.of(to),
        )
    }

    /**
     * Units of [to] per one [from], in micro-units, rounded half up: the rate
     * the converter shows ("1 USD = 93.9370 INR"). For display; [convert] does
     * not round through it.
     */
    public fun rateMicro(from: String, to: String): Long? {
        val fromPerEuro = perEuro(from) ?: return null
        val toPerEuro = perEuro(to) ?: return null
        return Money(Money.RATE_SCALE).converted(rate = toPerEuro, per = fromPerEuro, 0, 0)?.minor
    }

    private fun perEuro(currency: String): Long? =
        if (currency == EURO) Money.RATE_SCALE else perEuroMicro[currency]

    public companion object {
        public const val EURO: String = "EUR"
    }
}
