package com.ledgerflow.core.domain.analytics

import com.google.common.truth.Truth.assertThat
import com.ledgerflow.core.model.EntrySource
import com.ledgerflow.core.model.Money
import org.junit.Test

/**
 * C1's arithmetic, tested away from the query and the chart.
 *
 * The percentages are the only part of capture coverage with a right answer
 * that does not depend on how it looks — the same argument `LfAxisTicksTest`
 * makes. A screenshot of "62%" cannot tell you whether 62 is correct.
 */
class CaptureCoverageTest {

    /**
     * **Value and count are computed separately, and are expected to disagree.**
     *
     * This is the case the surface exists for: one large transfer typed by hand
     * against many small captured payments. By value the app looks like it is
     * capturing a third of spending; by count it is capturing three quarters.
     * Collapsing them into one figure would report whichever flatters or
     * damns the app, depending on which was chosen.
     */
    @Test
    fun valueAndCountAreReportedSeparately_becauseTheyDisagree() {
        val coverage = CaptureCoverage.from(
            mapOf(
                EntrySource.NOTIFICATION to CaptureShare(Money(30_000L), 3),
                EntrySource.MANUAL to CaptureShare(Money(60_000L), 1),
            ),
        )

        assertThat(coverage.automaticPercentByValue).isEqualTo(33)
        assertThat(coverage.automaticPercentByCount).isEqualTo(75)
    }

    /**
     * **SMS, notification and OCR are one bucket.** All three mean the amount
     * reached the app without anyone typing it, which is the question C1 asks.
     */
    @Test
    fun everyCapturedSourceCountsAsAutomatic() {
        val coverage = CaptureCoverage.from(
            mapOf(
                EntrySource.SMS to CaptureShare(Money(10_000L), 1),
                EntrySource.NOTIFICATION to CaptureShare(Money(20_000L), 2),
                EntrySource.OCR to CaptureShare(Money(30_000L), 3),
            ),
        )

        assertThat(coverage.automatic.amount.minor).isEqualTo(60_000L)
        assertThat(coverage.automatic.count).isEqualTo(6)
        assertThat(coverage.automaticPercentByValue).isEqualTo(100)
    }

    /**
     * **An import is neither, and gets its own bucket.**
     *
     * Nobody typed it and no parser read it. Counting it as automatic would
     * inflate the one number this surface exists to report — here it would read
     * 100% captured for an app that captured a quarter of the money.
     */
    @Test
    fun animportIsNeitherAutomaticNorTypedByHand() {
        val coverage = CaptureCoverage.from(
            mapOf(
                EntrySource.SMS to CaptureShare(Money(25_000L), 1),
                EntrySource.IMPORT to CaptureShare(Money(75_000L), 3),
            ),
        )

        assertThat(coverage.imported.amount.minor).isEqualTo(75_000L)
        assertThat(coverage.manual).isEqualTo(CaptureShare.Empty)
        assertThat(coverage.automaticPercentByValue).isEqualTo(25)
    }

    /** Rounds to nearest rather than truncating: 2 of 3 is 67%, not 66%. */
    @Test
    fun percentagesRoundToNearest() {
        val coverage = CaptureCoverage.from(
            mapOf(
                EntrySource.SMS to CaptureShare(Money(2L), 2),
                EntrySource.MANUAL to CaptureShare(Money(1L), 1),
            ),
        )

        assertThat(coverage.automaticPercentByValue).isEqualTo(67)
        assertThat(coverage.automaticPercentByCount).isEqualTo(67)
    }

    /**
     * An empty window reports nothing rather than dividing by zero.
     *
     * The section hides itself on [CaptureCoverage.isEmpty], but the percentage
     * must be safe regardless — a crash behind a visibility check is a crash
     * waiting for the check to move.
     */
    @Test
    fun anEmptyWindowIsEmptyAndDoesNotDivideByZero() {
        val coverage = CaptureCoverage.from(emptyMap())

        assertThat(coverage.isEmpty).isTrue()
        assertThat(coverage.automaticPercentByValue).isEqualTo(0)
        assertThat(coverage.automaticPercentByCount).isEqualTo(0)
    }

    /** A window with only typed entries is 0%, and is *not* empty. */
    @Test
    fun aWindowOfOnlyManualEntriesReportsZeroPercentAndStillShows() {
        val coverage = CaptureCoverage.from(
            mapOf(EntrySource.MANUAL to CaptureShare(Money(5_000L), 2)),
        )

        assertThat(coverage.isEmpty).isFalse()
        assertThat(coverage.automaticPercentByValue).isEqualTo(0)
        assertThat(coverage.totalCount).isEqualTo(2)
    }
}
