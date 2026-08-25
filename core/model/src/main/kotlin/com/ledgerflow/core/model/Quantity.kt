package com.ledgerflow.core.model

/**
 * How many of something, in **thousandths of a unit** (SPEC.md §6.1).
 *
 * The storage form is `line_item.quantity_milli`, and the scale exists so that
 * half a kilo of anything is representable without a `Double` — Law 3 bans
 * floating point for money, and a quantity that multiplies a price is on the
 * money path whether or not it is money itself.
 *
 * A value class over the raw `Long` for one specific reason: without it,
 * `price * 2` and `price * Quantity(2)` are both legal, look alike, and differ
 * by a factor of a thousand — the first means two units and the second means
 * two *thousandths* of one. That is not a mistake anyone catches in review, and
 * it silently produces an entry off by 99.8%. With the type, the ambiguous call
 * does not compile.
 *
 * Room stores the underlying `Long`, so this type stops at the boundary and
 * `LineItem.quantityMilli` stays a plain `Long`; nothing about the schema
 * changes to introduce it.
 */
@JvmInline
public value class Quantity(public val milli: Long) : Comparable<Quantity> {

    public val isPositive: Boolean get() = milli > 0L

    /**
     * The quantity as whole units, or null when it is fractional.
     *
     * For display: "×2" reads better than "×2.000", and a UI that wants the
     * short form should not have to know the scale to find out whether it
     * applies.
     */
    public val wholeUnits: Long? get() = (milli / SCALE).takeIf { milli % SCALE == 0L }

    override fun compareTo(other: Quantity): Int = milli.compareTo(other.milli)

    override fun toString(): String = "Quantity($milli milli)"

    public companion object {

        /** 1.000 = 1000. One place, referenced by everything that needs it. */
        public const val SCALE: Long = 1000L

        /** One unit — the default for a line the user has not given a quantity. */
        public val ONE: Quantity = Quantity(SCALE)

        public fun ofUnits(units: Long): Quantity = Quantity(units * SCALE)
    }
}
