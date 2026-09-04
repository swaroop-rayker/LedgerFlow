package com.ledgerflow.core.domain.analytics

import com.ledgerflow.core.model.Money

/**
 * C2 — one merchant the ruleset is blind to (`docs/DATAVIZ-PLAN.md` Family C).
 *
 * Not "a merchant you typed once". A single typed entry is a one-off; a
 * *habit* of typing the same merchant is a missing parser rule, and each row
 * here is a candidate for one — and, per `CLAUDE.md` §11, for a permanent
 * corpus fixture. This is the surface that closes the loop: the chart improves
 * the product that draws the chart.
 */
public data class ParserGap(
    val merchantId: String,
    val name: String,
    /** Entries at this merchant the user typed by hand, in the window. */
    val manualCount: Int,
    /** Every entry at this merchant in the window, typed or captured. */
    val totalCount: Int,
    /** What the typed ones came to — the effort, priced. */
    val manualAmount: Money,
) {
    /** 0..100. Whole percent, rounded to nearest. */
    public val manualPercent: Int
        get() = if (totalCount <= 0) 0 else (manualCount * PERCENT + totalCount / 2) / totalCount

    private companion object {
        const val PERCENT = 100
    }
}

/**
 * Which merchants count as a gap, and in what order.
 *
 * Kept out of the query so the thresholds are testable arithmetic rather than
 * SQL — the same split `RecurringDetection` uses, and for the same reason: a
 * rule about *what counts as a pattern* is a product decision, and it should be
 * legible somewhere a reader can argue with it.
 */
public object ParserGapDetection {

    /**
     * Fewer than this and it is not a habit.
     *
     * Matches [RecurringDetection.MINIMUM_OCCURRENCES] deliberately: both
     * surfaces are asking "is this a pattern or an accident", and answering it
     * with two different numbers would be arbitrary. Two typed entries at a
     * shop is a fortnight; three is behaviour.
     */
    public const val MINIMUM_ENTRIES: Int = 3

    /**
     * "Almost always", as a number.
     *
     * A merchant split evenly between typed and captured is *partly* covered —
     * some of its messages parse — and the ruleset is not blind to it. The list
     * is for merchants where capture is essentially absent, because those are
     * the ones where a new rule pays for itself.
     */
    public const val MINIMUM_MANUAL_PERCENT: Int = 67

    /**
     * The gaps worth showing, worst first.
     *
     * **Ranked by how often it happens, not by how much it cost.** Every row is
     * a candidate parser rule, and the value of writing one is the typing it
     * saves in future — which is a frequency, not an amount. A ₹40,000 rent
     * typed once a year is a worse *ranking* than a ₹200 chaiwala typed twelve
     * times, and money is the tiebreaker only when the counts are equal.
     *
     * Entries with no merchant are dropped before this is called: a parser rule
     * cannot target the absence of a payee.
     */
    public fun detect(candidates: List<ParserGap>): List<ParserGap> = candidates
        .filter { it.totalCount >= MINIMUM_ENTRIES }
        .filter { it.manualPercent >= MINIMUM_MANUAL_PERCENT }
        .sortedWith(
            compareByDescending<ParserGap> { it.manualCount }
                .thenByDescending { it.manualAmount.minor }
                .thenBy { it.name },
        )
}
