package com.ledgerflow.feature.ledger

import com.google.common.truth.Truth.assertThat
import com.ledgerflow.core.domain.ledger.LedgerRepository
import org.junit.Test

/**
 * The list's recency bands (SPEC.md §5.5).
 *
 * The property worth testing is not any single boundary but that the four bands
 * **tile the query's window with no gap and no overlap**. The bands and the
 * `since` bound are set in different files, and if they ever drift apart the
 * symptom is a row that reaches the screen belonging to no band the header
 * logic will emit — which renders as an entry silently filed under whichever
 * header happens to be above it.
 */
class RecencyBucketTest {

    @Test
    fun today_isToday() {
        assertThat(recencyBucketOf(TODAY, TODAY)).isEqualTo(RecencyBucket.Today)
    }

    /**
     * A back-dated receipt is a real thing; a "Tomorrow" band with one member
     * for one day is not. It sorts to the top either way.
     */
    @Test
    fun futureDated_landsInToday() {
        assertThat(recencyBucketOf(TODAY + 1, TODAY)).isEqualTo(RecencyBucket.Today)
        assertThat(recencyBucketOf(TODAY + 30, TODAY)).isEqualTo(RecencyBucket.Today)
    }

    @Test
    fun yesterday_isItsOwnBand() {
        assertThat(recencyBucketOf(TODAY - 1, TODAY)).isEqualTo(RecencyBucket.Yesterday)
    }

    @Test
    fun twoToSixDaysBack_isThisWeek() {
        (2..6).forEach { back ->
            assertThat(recencyBucketOf(TODAY - back, TODAY)).isEqualTo(RecencyBucket.ThisWeek)
        }
    }

    /** The one boundary a strict-vs-loose comparison would get wrong. */
    @Test
    fun sevenDaysBack_isThisMonth_notThisWeek() {
        assertThat(recencyBucketOf(TODAY - 7, TODAY)).isEqualTo(RecencyBucket.ThisMonth)
    }

    @Test
    fun theOldestDayInTheWindow_stillHasABand() {
        val oldest = TODAY - LedgerRepository.LIST_WINDOW_DAYS

        assertThat(recencyBucketOf(oldest, TODAY)).isEqualTo(RecencyBucket.ThisMonth)
    }

    /**
     * Every day the query can return maps to a band, and the bands run in the
     * same direction the list does.
     *
     * Written as a sweep rather than as boundary cases because the failure this
     * guards against is a *gap*, and a gap is exactly what picking sample
     * points can miss. The monotonicity half is what the sticky-header logic
     * depends on: it emits a header when a row's band differs from its
     * neighbour's, which only produces one header per band if the bands never
     * go backwards as the dates do.
     */
    @Test
    fun everyDayInTheWindow_tilesTheBandsInOrder() {
        val bands = (0..LedgerRepository.LIST_WINDOW_DAYS).map { back ->
            recencyBucketOf(TODAY - back, TODAY)
        }

        assertThat(bands).containsNoneIn(arrayOf<RecencyBucket?>(null))
        assertThat(bands.map { it.ordinal }).isInOrder()
        // All four are reachable: a band no date maps to is a header the user
        // would never see and a definition nobody would notice was wrong.
        assertThat(bands.toSet()).containsExactlyElementsIn(RecencyBucket.entries)
    }

    private companion object {
        /** An arbitrary day; every assertion here is relative to it. */
        private const val TODAY = 20_684
    }
}
