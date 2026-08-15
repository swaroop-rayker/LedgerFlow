package com.ledgerflow.core.model

/**
 * A monetary amount in **minor units** (paise, cents), always in the install's
 * base currency (SPEC.md §5.8).
 *
 * Law 3: money is `Long`. Never `Float`, never `Double`, never `BigDecimal` in
 * a hot path. Binary floating point cannot represent 0.10 exactly, and a ledger
 * that drifts by rounding is corrupt rather than merely imprecise.
 *
 * A value class, so this costs nothing at runtime -- it compiles down to the
 * `Long` it wraps while still refusing to be added to a plain number by
 * accident.
 */
@JvmInline
public value class Money(public val minor: Long) : Comparable<Money> {

    public operator fun plus(other: Money): Money = Money(minor + other.minor)

    public operator fun minus(other: Money): Money = Money(minor - other.minor)

    public operator fun times(count: Int): Money = Money(minor * count)

    public operator fun unaryMinus(): Money = Money(-minor)

    public val isZero: Boolean get() = minor == 0L
    public val isPositive: Boolean get() = minor > 0L
    public val isNegative: Boolean get() = minor < 0L

    /** Magnitude. Ledger amounts are stored positive; direction is [LedgerType]. */
    public val absolute: Money get() = if (minor < 0) Money(-minor) else this

    override fun compareTo(other: Money): Int = minor.compareTo(other.minor)

    override fun toString(): String = "Money($minor minor)"

    public companion object {
        public val ZERO: Money = Money(0)

        public fun sum(amounts: Iterable<Money>): Money =
            Money(amounts.sumOf { it.minor })
    }
}

/**
 * ISO-4217 minor-unit exponents.
 *
 * Hardcoded rather than read from `java.util.Currency` (SPEC.md §5.8): its
 * exponent data has been wrong on some OEM ROMs, and an incorrect exponent
 * silently misplaces the decimal point on every amount the user sees.
 */
public object CurrencyExponent {

    private const val DEFAULT_EXPONENT = 2

    private val EXPONENTS: Map<String, Int> = mapOf(
        // Zero-decimal currencies.
        "JPY" to 0, "KRW" to 0, "VND" to 0, "CLP" to 0, "ISK" to 0,
        "UGX" to 0, "PYG" to 0, "RWF" to 0, "XAF" to 0, "XOF" to 0, "XPF" to 0,
        // Three-decimal currencies.
        "BHD" to 3, "IQD" to 3, "JOD" to 3, "KWD" to 3,
        "LYD" to 3, "OMR" to 3, "TND" to 3,
    )

    /** Defaults to 2, which covers INR, USD, EUR and the large majority. */
    public fun of(currencyCode: String): Int =
        EXPONENTS[currencyCode.uppercase()] ?: DEFAULT_EXPONENT
}
