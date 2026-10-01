package com.ledgerflow.core.domain.diagnostics

import com.google.common.truth.Truth.assertThat
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * The diagnostics report's arithmetic, off-device: the percentile offset, the
 * window, and which senders count as read worst.
 */
class IngestDiagnosticsRulesTest {

    // ── NearestRank ─────────────────────────────────────────────────────────

    /** One value is every percentile of itself. */
    @Test
    fun nearestRank_ofOneValue_isThatValue() {
        assertThat(NearestRank.offset(count = 1, percent = 50)).isEqualTo(0)
        assertThat(NearestRank.offset(count = 1, percent = 90)).isEqualTo(0)
    }

    /**
     * The median of an even count is the lower middle value — a value that
     * occurred, never an average of two.
     */
    @Test
    fun nearestRank_medianOfAnEvenCount_isTheLowerMiddle() {
        // Ranks 1..4; ceil(0.5 * 4) = 2 -> offset 1.
        assertThat(NearestRank.offset(count = 4, percent = 50)).isEqualTo(1)
        // Ranks 1..5; ceil(2.5) = 3 -> offset 2, the true middle.
        assertThat(NearestRank.offset(count = 5, percent = 50)).isEqualTo(2)
    }

    @Test
    fun nearestRank_p90_pointsIntoTheSlowestTenth() {
        // Ten values: the 9th (offset 8) is where the slowest tenth starts.
        assertThat(NearestRank.offset(count = 10, percent = 90)).isEqualTo(8)
        // Eleven: ceil(9.9) = 10 -> offset 9.
        assertThat(NearestRank.offset(count = 11, percent = 90)).isEqualTo(9)
        assertThat(NearestRank.offset(count = 100, percent = 90)).isEqualTo(89)
    }

    /** The offset is always inside the list the query will read. */
    @Test
    fun nearestRank_neverPointsPastTheEnd() {
        for (count in 1..200) {
            for (percent in listOf(1, 50, 90, 100)) {
                assertThat(NearestRank.offset(count, percent)).isIn(0 until count)
            }
        }
    }

    @Test
    fun nearestRank_refusesAnEmptyList() {
        assertThrows(IllegalArgumentException::class.java) { NearestRank.offset(count = 0, percent = 50) }
    }

    // ── DiagnosticsWindow ───────────────────────────────────────────────────

    @Test
    fun window_countsBackWholeDays_andAllTimeStartsAtTheEpoch() {
        val now = 1_800_000_000_000L
        val day = 86_400_000L

        assertThat(DiagnosticsWindow.LAST_30_DAYS.sinceMillis(now)).isEqualTo(now - 30 * day)
        assertThat(DiagnosticsWindow.LAST_90_DAYS.sinceMillis(now)).isEqualTo(now - 90 * day)
        assertThat(DiagnosticsWindow.ALL_TIME.sinceMillis(now)).isEqualTo(0L)
    }

    // ── SmsSenderName ───────────────────────────────────────────────────────

    /** Rotating operator prefixes and route suffixes are one bank. */
    @Test
    fun smsSenderName_reducesADltHeaderToItsEntity() {
        assertThat(SmsSenderName.of("VM-HDFCBK-S")).isEqualTo("HDFCBK")
        assertThat(SmsSenderName.of("AD-HDFCBK-T")).isEqualTo("HDFCBK")
        assertThat(SmsSenderName.of("JD-HDFCBK")).isEqualTo("HDFCBK")
        assertThat(SmsSenderName.of("vm-sbiupi-s")).isEqualTo("SBIUPI")
    }

    /** Anything not shaped like a DLT header is kept exactly, not guessed at. */
    @Test
    fun smsSenderName_keepsAnythingElseAsItIs() {
        assertThat(SmsSenderName.of("HDFCBK")).isEqualTo("HDFCBK")
        assertThat(SmsSenderName.of("+919800000000")).isEqualTo("+919800000000")
        assertThat(SmsSenderName.of("BANK-ALERTS-LONG")).isEqualTo("BANK-ALERTS-LONG")
    }

    // ── WeakSenders ─────────────────────────────────────────────────────────

    /** Two headers of one bank arrive as two rows and are ranked as one. */
    @Test
    fun weakSenders_mergesRowsForTheSameSender() {
        val ranked = WeakSenders.rank(
            listOf(
                SenderReadRate("HDFCBK", weak = 2, total = 5),
                SenderReadRate("HDFCBK", weak = 3, total = 4),
            ),
        )

        assertThat(ranked).containsExactly(SenderReadRate("HDFCBK", weak = 5, total = 9))
    }

    /**
     * Ranked by how many were read weakly, not by the share: five fixable
     * messages are worth more than one, however small the sender.
     */
    @Test
    fun weakSenders_rankByWeakCount_thenByShare_thenByName() {
        val ranked = WeakSenders.rank(
            listOf(
                SenderReadRate("Tiny", weak = 1, total = 1),
                SenderReadRate("Busy", weak = 5, total = 50),
                SenderReadRate("Worse", weak = 3, total = 4),
                SenderReadRate("Better", weak = 3, total = 30),
                SenderReadRate("Alpha", weak = 3, total = 30),
            ),
        )

        assertThat(ranked.map { it.sender })
            .containsExactly("Busy", "Worse", "Alpha", "Better", "Tiny").inOrder()
    }

    /** A sender whose every message read well is not a problem to list. */
    @Test
    fun weakSenders_dropsSendersWithNothingWeak_andKeepsFive() {
        val rows = (1..8).map { SenderReadRate("S$it", weak = it, total = 10) } +
            SenderReadRate("Clean", weak = 0, total = 40)

        val ranked = WeakSenders.rank(rows)

        assertThat(ranked).hasSize(WeakSenders.LIMIT)
        assertThat(ranked.map { it.sender }).doesNotContain("Clean")
        assertThat(ranked.first().sender).isEqualTo("S8")
    }

    // ── The report's derived totals ─────────────────────────────────────────

    @Test
    fun capturedTotal_excludesSmsThatWasNotFromABank() {
        val captured = CaptureOutcomes(
            parsed = 4, unmatched = 1, duplicates = 2, failed = 1, waiting = 1, ignored = 50, stuck = 0,
        )

        assertThat(captured.fromFinancialSenders).isEqualTo(9)
    }

    @Test
    fun duplicates_stillInInbox_leavesOutCopiesNoLongerOnRecord() {
        val evidence = DuplicateEvidence(
            listOf(
                KeptCopy(com.ledgerflow.core.model.EntrySource.SMS, 3),
                KeptCopy(null, 2),
            ),
        )

        assertThat(evidence.total).isEqualTo(5)
        assertThat(evidence.stillInInbox).isEqualTo(3)
    }
}
