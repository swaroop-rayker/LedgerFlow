package com.ledgerflow.core.model

/**
 * Settings > Currency & region (`docs/V2-PLAN.md` §3.3, D4; owner 2026-10-24).
 *
 * How amounts and dates are *written* on screen. Neither changes a stored
 * value: an amount is still a `Long` of base-currency minor units (Law 3) and a
 * date is still epoch millis or an epoch day. Here rather than in
 * `:core:domain` because the design system, which formats, cannot see the
 * domain, and both can see this pure-Kotlin module.
 */
public data class RegionConventions(
    val grouping: NumberGrouping = NumberGrouping.BY_CURRENCY,
    val dateOrder: DateOrder = DateOrder.AUTO,
)

/** Where the commas go in a long amount. */
public enum class NumberGrouping {
    /**
     * As the currency is written: lakh and crore for rupees (1,24,000), groups
     * of three for the rest (124,000). What LedgerFlow has always done, and so
     * the default -- an upgrade must not move anyone's commas.
     */
    BY_CURRENCY,

    /** 1,24,000 whatever the currency. */
    LAKH,

    /** 124,000 whatever the currency. */
    WESTERN,
}

/** The order of day, month and year in a date. */
public enum class DateOrder {
    /**
     * As LedgerFlow has always written dates: the phone region's own style
     * where a screen uses it ("Oct 24, 2026" in the US, "24 Oct 2026" in India),
     * day first where a screen always has. The default, so an upgrade changes
     * no date anyone sees.
     */
    AUTO,

    /** 24 Oct 2026. */
    DAY_MONTH_YEAR,

    /** Oct 24, 2026. */
    MONTH_DAY_YEAR,

    /** 2026 Oct 24. */
    YEAR_MONTH_DAY,
}
