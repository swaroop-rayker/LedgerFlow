package com.ledgerflow.core.domain.ingest

import com.ledgerflow.core.model.LineItemKind
import com.ledgerflow.core.model.Money
import kotlin.math.absoluteValue

/**
 * Whether a bill's parts add up to its total (SPEC.md §5.3, §5.4).
 *
 * ```
 * |Σ(items) + Σ(tax) − Σ(discount) − total| ≤ max(₹1, 0.5% of total)
 * ```
 *
 * ## Why this lives in `:core:domain` and not in `:feature:ocr`
 *
 * Two surfaces need the same answer and they are in different modules, so a
 * feature cannot own it (CLAUDE.md §3). The extractor computes it to set a
 * candidate's confidence; the **review screen** recomputes it on every
 * keystroke, because §5.3's banner has to turn green when the user fixes the
 * line that was wrong. A stored verdict would go stale the moment they typed.
 *
 * That is also why nothing about this is persisted. It is a pure function of
 * the lines and the total, both of which are already in `extracted_json`
 * (ADR-0022), so recomputing costs nothing and can never disagree with what is
 * on screen.
 *
 * ## Unbalanced is a state, not a refusal
 *
 * §5.3 and §5.4 are explicit that the user may save an unbalanced bill. The
 * difference becomes an `UNALLOCATED` line at approval so the parts still sum
 * to the whole. This type reports; it never blocks.
 *
 * ## Tax may be *inside* the prices, and the bill does not say which
 *
 * §5.3's formula adds tax to the items. That is right for a bill that prints
 * a net subtotal and then adds GST — and **wrong for an Indian GST tax
 * invoice**, where the per-item NET AMT already contains the tax and the
 * `S GST 9% / C GST 9%` rows under each item are a *breakdown* of what is
 * already there. Found on a real Food Bazaar receipt: six items summing to
 * ₹1,075.46, exactly the printed total, with ₹171.36 of GST rows that an
 * additive reading would have added on top.
 *
 * Nothing on the paper distinguishes the two formats in words, so the
 * arithmetic decides: **both readings are computed and the one that closes
 * wins.** That is the same self-validating principle `ReceiptColumns` uses
 * for `unit x quantity` — a reading that reproduces a number the extractor
 * did not itself produce has checked itself.
 *
 * When neither closes, the *additive* delta is reported, because it is the
 * formula §5.3 states and the figure a user checking by hand will arrive at.
 *
 * ## The arithmetic is integers throughout (Law 3)
 *
 * `0.5% of total` is `total × 5 / 1000` in `Long`, truncated. Truncation makes
 * the tolerance very slightly *tighter* than the real half percent, which is
 * the right direction for a check whose job is to notice a discrepancy.
 */
public sealed interface Reconciliation {

    /** The delta, `Σparts − total`. Signed: positive means the parts overshoot. */
    public val delta: Money

    /** Within tolerance. §5.3's green banner. */
    public data class Balanced(override val delta: Money) : Reconciliation

    /** Outside tolerance. §5.3's amber banner, which states [delta]. */
    public data class Unbalanced(
        override val delta: Money,
        val tolerance: Money,
    ) : Reconciliation

    /**
     * No total was read, so there is nothing to reconcile against.
     *
     * **Distinct from [Unbalanced], and the distinction is the point.** A bill
     * whose total the recogniser could not read has not failed a check — it has
     * not been checked. Showing amber with a delta computed against zero would
     * report a discrepancy the receipt does not have, and the number would be
     * the sum of the items, which reads like a real finding.
     */
    public data object NotPossible : Reconciliation {
        override val delta: Money get() = Money.ZERO
    }

    public companion object {

        /** §5.3's floor, in minor units: ₹1. */
        private const val FLOOR_MINOR = 100L

        /** §5.3's proportional term: 0.5%, as `× 5 / 1000`. */
        private const val PROPORTION_NUMERATOR = 5L
        private const val PROPORTION_DENOMINATOR = 1000L

        /**
         * Weighs [lines] against [total].
         *
         * `SUBTOTAL` and `TOTAL` lines must not be in [lines]; they are the
         * sum, not parts of it, and including one would double the bill.
         * `UNALLOCATED` is excluded here too — it is the *output* of this check
         * at approval time, so counting it as an input would make every
         * approved entry trivially balanced.
         *
         * `DISCOUNT` amounts are negative by the convention `line_item`
         * already uses (`Ledger.kt`), so the spec's `− Σ(discount)` is a plain
         * addition here. Writing it as a subtraction of positive magnitudes
         * would mean two sign conventions in one codebase, and the one that
         * lost would surface as a discount that *increased* the bill.
         */
        public fun of(lines: List<ExtractedLineItem>, total: Money?): Reconciliation {
            if (total == null) return NotPossible

            val base = Money.sum(lines.filter { it.kind in BASE }.mapNotNull { it.total })
            val tax = Money.sum(lines.filter { it.kind == LineItemKind.TAX }.mapNotNull { it.total })
            val tolerance = toleranceFor(total)

            // The spec's literal formula: tax is added to the item prices.
            val additive = (base + tax) - total
            // The same bill read as tax-INCLUSIVE: the tax rows restate what
            // is already inside the prices rather than adding to them.
            val inclusive = base - total

            return when {
                additive.minor.absoluteValue <= tolerance.minor -> Balanced(additive)
                inclusive.minor.absoluteValue <= tolerance.minor -> Balanced(inclusive)
                // Neither closes. Report the *additive* delta, because that is
                // §5.3's stated formula and the number a reader will check by
                // hand.
                else -> Unbalanced(additive, tolerance)
            }
        }

        /** `max(₹1, 0.5% of total)`, on the magnitude of the total. */
        public fun toleranceFor(total: Money): Money = Money(
            maxOf(
                FLOOR_MINOR,
                // Multiply BEFORE dividing. `minor / 1000 * 5` on a 473.00
                // bill is 47 * 5 = 235 paise where the real half percent is
                // 236 -- a coarser tolerance than the spec's, arrived at by
                // accident rather than by choice.
                total.absolute.minor * PROPORTION_NUMERATOR / PROPORTION_DENOMINATOR,
            ),
        )

        /**
         * The parts that are always additive: purchases and discounts.
         *
         * `TAX` is deliberately absent and handled separately — see [of].
         */
        private val BASE = setOf(LineItemKind.ITEM, LineItemKind.DISCOUNT)
    }
}
