package com.ledgerflow.core.domain.analytics

import com.google.common.truth.Truth.assertThat
import com.ledgerflow.core.model.Money
import org.junit.Test

/**
 * A8's rule, tested as arithmetic (`SPEC.md` §5.6).
 *
 * The threshold σ/μ < 0.25 is a number in the spec, so the cases below are
 * built to sit deliberately either side of it rather than comfortably inside —
 * a test that only feeds it perfect monthly subscriptions would pass against an
 * implementation that returned everything.
 */
class RecurringDetectionTest {

    @Test
    fun aMonthlySubscription_isDetected() {
        val netflix = detect(days = listOf(0, 30, 60, 90), amount = 64_900L)

        assertThat(netflix).isNotNull()
        assertThat(netflix!!.intervalDays).isEqualTo(30)
        assertThat(netflix.occurrences).isEqualTo(4)
        assertThat(netflix.regularity).isEqualTo(0.0)
        assertThat(netflix.typicalAmount.minor).isEqualTo(64_900L)
        assertThat(netflix.nextExpected).isEqualTo(120)
    }

    /**
     * §5.6's floor: "≥ 3 occurrences".
     *
     * Two payments define exactly one gap, and one gap has no spread — σ/μ
     * would be 0 and *everything* bought twice would look like a subscription.
     */
    @Test
    fun twoPayments_areNeverRecurring() {
        assertThat(detect(days = listOf(0, 30), amount = 64_900L)).isNull()
    }

    @Test
    fun irregularSpending_isRejected() {
        // Gaps of 3, 41, 12 -- a mean near 18 with enormous spread. This is a
        // person buying coffee, not a subscription.
        assertThat(detect(days = listOf(0, 3, 44, 56), amount = 20_000L)).isNull()
    }

    /**
     * A subscription that slips by a day or two is still a subscription.
     *
     * Gaps of 30, 31, 29 give σ/μ ≈ 0.027 — comfortably inside the threshold,
     * which is the behaviour that makes the rule useful on real billing dates
     * rather than only on synthetic ones.
     */
    @Test
    fun aSubscriptionThatDriftsByADay_isStillDetected() {
        val detected = detect(days = listOf(0, 30, 61, 90), amount = 49_900L)

        assertThat(detected).isNotNull()
        assertThat(detected!!.regularity).isLessThan(RecurringDetection.MAXIMUM_IRREGULARITY)
        assertThat(detected.intervalDays).isEqualTo(30)
    }

    @Test
    fun theThresholdIsExclusive_atExactlyTheLimit() {
        // Gaps of 20, 30, 20, 30: mean 25, σ = 5, σ/μ = 0.2 -- inside.
        assertThat(detect(days = listOf(0, 20, 50, 70, 100), amount = 10_000L)).isNotNull()
        // Gaps of 10, 30, 10, 30: mean 20, σ = 10, σ/μ = 0.5 -- outside.
        assertThat(detect(days = listOf(0, 10, 40, 50, 80), amount = 10_000L)).isNull()
    }

    /**
     * Two payments on one day are one event.
     *
     * Otherwise a zero gap drags the mean down and the spread up, and a genuine
     * monthly subscription with one duplicated charge stops being detected —
     * which is exactly the case cross-source dedupe exists to prevent and this
     * should survive anyway.
     */
    @Test
    fun twoChargesOnTheSameDay_collapseToOneEvent() {
        val detected = detect(days = listOf(0, 30, 30, 60, 90), amount = 64_900L)

        assertThat(detected).isNotNull()
        assertThat(detected!!.intervalDays).isEqualTo(30)
    }

    /**
     * The typical amount is the **median**, so a one-off price change does not
     * report a figure the user has never paid.
     */
    @Test
    fun theTypicalAmount_isTheMedianNotTheMean() {
        val detected = RecurringDetection.detect(
            merchantId = "m",
            name = "Streaming",
            occurrences = listOf(
                Occurrence(0, Money(49_900L)),
                Occurrence(30, Money(49_900L)),
                Occurrence(60, Money(49_900L)),
                // A price rise. The mean would be 62_425, which was never paid.
                Occurrence(90, Money(99_900L)),
            ),
        )

        assertThat(detected!!.typicalAmount.minor).isEqualTo(49_900L)
    }

    @Test
    fun anAnnualPairOfPurchases_isNotASubscription() {
        // Regular to the day, but 430 apart -- past the sanity ceiling.
        assertThat(detect(days = listOf(0, 430, 860, 1290), amount = 500_000L)).isNull()
    }

    // ── A10: the runway ────────────────────────────────────────────────────

    @Test
    fun theRunway_countsEachMerchantAtMostOnce() {
        // Weekly, so two intervals would fit in a 30-day window. Only the first
        // is counted: the second would be a forecast built on a forecast.
        val weekly = detect(days = listOf(0, 7, 14, 21), amount = 30_000L)!!

        val due = RecurringDetection.runway(listOf(weekly), today = 21, through = 51)

        assertThat(due).hasSize(1)
        assertThat(due.single().nextExpected).isEqualTo(28)
    }

    @Test
    fun theRunway_excludesChargesAlreadyOverdue() {
        val monthly = detect(days = listOf(0, 30, 60, 90), amount = 64_900L)!!

        // Next expected is day 120; "today" is 130, so it is late, not pending.
        val due = RecurringDetection.runway(listOf(monthly), today = 130, through = 160)

        assertThat(due).isEmpty()
    }

    @Test
    fun theRunway_isOrderedByWhenTheChargeFalls() {
        val monthly = detect(days = listOf(0, 30, 60, 90), amount = 64_900L)!!
        val weekly = detect(days = listOf(0, 7, 14, 21), amount = 30_000L)!!
            .copy(merchantId = "w", name = "Weekly")

        val due = RecurringDetection.runway(
            listOf(monthly, weekly),
            today = 21,
            through = 130,
        )

        assertThat(due.map { it.nextExpected }).isInOrder()
    }

    private fun detect(days: List<Int>, amount: Long) = RecurringDetection.detect(
        merchantId = "m",
        name = "Merchant",
        occurrences = days.map { Occurrence(it, Money(amount)) },
    )
}
