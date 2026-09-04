package com.ledgerflow.core.domain.analytics

import com.google.common.truth.Truth.assertThat
import com.ledgerflow.core.model.Money
import org.junit.Test

/**
 * C2's thresholds and ranking, tested away from the query and the screen.
 *
 * Every assertion here is a product decision about *what counts as a gap*, and
 * the point of keeping the rule out of SQL is that a reader can argue with it
 * here. A screenshot of a list cannot tell you whether the right merchants are
 * on it, or whether they are in the right order.
 */
class ParserGapDetectionTest {

    /**
     * **Ranked by how often, not by how much.**
     *
     * Every row is a candidate parser rule, and the value of writing one is the
     * typing it saves — a frequency. Ranking by money would put a once-a-year
     * rent transfer above a chaiwala typed twelve times, and the rent rule would
     * pay back once.
     */
    @Test
    fun gapsAreRankedByFrequency_notByAmount() {
        val gaps = ParserGapDetection.detect(
            listOf(
                gap("rent", "Landlord", manual = 3, total = 3, manualMinor = 4_000_000L),
                gap("chai", "Chai Point", manual = 12, total = 12, manualMinor = 240_000L),
            ),
        )

        assertThat(gaps.map { it.name }).containsExactly("Chai Point", "Landlord").inOrder()
    }

    /** Money breaks a tie, so two equally frequent gaps still have an order. */
    @Test
    fun amountBreaksATieOnFrequency() {
        val gaps = ParserGapDetection.detect(
            listOf(
                gap("small", "Small", manual = 4, total = 4, manualMinor = 10_000L),
                gap("large", "Large", manual = 4, total = 4, manualMinor = 90_000L),
            ),
        )

        assertThat(gaps.map { it.name }).containsExactly("Large", "Small").inOrder()
    }

    /**
     * **Twice is not a habit.**
     *
     * A merchant typed once or twice is a one-off, and a gap list that reported
     * every one-off would be a list of everything the user has ever done by
     * hand — which names no rule worth writing.
     */
    @Test
    fun amerchantBelowTheMinimumIsNotAGap() {
        val gaps = ParserGapDetection.detect(
            listOf(gap("once", "One Off", manual = 2, total = 2, manualMinor = 50_000L)),
        )

        assertThat(gaps).isEmpty()
    }

    /**
     * **A merchant that is partly captured is not blind.**
     *
     * Half its messages parse, so a rule already exists and matches sometimes;
     * the fix there is a different job from writing a rule that does not exist.
     * The list is for merchants where capture is essentially absent.
     */
    @Test
    fun amerchantCapturedHalfTheTimeIsNotAGap() {
        val gaps = ParserGapDetection.detect(
            listOf(gap("half", "Half Caught", manual = 5, total = 10, manualMinor = 50_000L)),
        )

        assertThat(gaps).isEmpty()
    }

    /** ...but one captured only occasionally still is. */
    @Test
    fun amerchantCapturedOnlyOccasionallyIsStillAGap() {
        val gaps = ParserGapDetection.detect(
            listOf(gap("mostly", "Mostly Typed", manual = 8, total = 10, manualMinor = 80_000L)),
        )

        assertThat(gaps.map { it.name }).containsExactly("Mostly Typed")
        assertThat(gaps.single().manualPercent).isEqualTo(80)
    }

    /**
     * The threshold matches [RecurringDetection.MINIMUM_OCCURRENCES] on purpose.
     *
     * Both surfaces ask "is this a pattern or an accident", and answering that
     * with two different numbers in one app would be arbitrary. Pinned so the
     * two cannot drift apart silently.
     */
    @Test
    fun theHabitThresholdMatchesRecurringDetection() {
        assertThat(ParserGapDetection.MINIMUM_ENTRIES)
            .isEqualTo(RecurringDetection.MINIMUM_OCCURRENCES)
    }

    @Test
    fun aFullyCapturedMerchantNeverAppears() {
        val gaps = ParserGapDetection.detect(
            listOf(gap("clean", "Zepto", manual = 0, total = 9, manualMinor = 0L)),
        )

        assertThat(gaps).isEmpty()
    }

    private fun gap(id: String, name: String, manual: Int, total: Int, manualMinor: Long) =
        ParserGap(
            merchantId = id,
            name = name,
            manualCount = manual,
            totalCount = total,
            manualAmount = Money(manualMinor),
        )
}
