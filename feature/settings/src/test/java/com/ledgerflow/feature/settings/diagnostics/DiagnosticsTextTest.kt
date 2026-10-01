package com.ledgerflow.feature.settings.diagnostics

import com.google.common.truth.Truth.assertThat
import com.ledgerflow.core.domain.diagnostics.CaptureOutcomes
import com.ledgerflow.core.domain.diagnostics.KeptCopy
import com.ledgerflow.core.domain.diagnostics.PipelineSpeed
import com.ledgerflow.core.domain.diagnostics.ReadQualityReport
import com.ledgerflow.core.domain.diagnostics.RollupHealth
import com.ledgerflow.core.model.EntrySource
import java.util.Locale
import org.junit.Test

/** What the diagnostics screen says, pinned where a screenshot could not pin it. */
class DiagnosticsTextTest {

    private val nothing = CaptureOutcomes(0, 0, 0, 0, 0, 0, 0)

    /** A zero row is height that says nothing; the first card lists only what happened. */
    @Test
    fun capturedRows_leaveOutZeroes_inPipelineOrder() {
        val rows = capturedRows(nothing.copy(parsed = 4, duplicates = 2))

        assertThat(rows).containsExactly(
            "Read by a rule" to 4,
            "Same payment, arrived twice" to 2,
        ).inOrder()
    }

    /** "Nothing" is the finding on a fresh install, and it is said in words. */
    @Test
    fun capturedHeadline_saysNothingArrived_whenNothingDid() {
        assertThat(capturedHeadline(nothing))
            .isEqualTo("Nothing arrived from your banks or payment apps in this period.")
        assertThat(capturedHeadline(nothing.copy(parsed = 1))).startsWith("1 message ")
        assertThat(capturedHeadline(nothing.copy(parsed = 2, unmatched = 1))).startsWith("3 messages ")
    }

    /** Personal SMS is a count, never a sender, and absent entirely at zero (playSafe). */
    @Test
    fun ignoredLine_isACountOnly_andAbsentAtZero() {
        assertThat(ignoredLine(nothing)).isNull()
        assertThat(ignoredLine(nothing.copy(ignored = 112)))
            .isEqualTo("112 other SMS were checked, found not to be from a bank, and left alone.")
    }

    /** Personal SMS does not inflate the headline: it is not from a bank. */
    @Test
    fun capturedHeadline_doesNotCountIgnoredSms() {
        assertThat(capturedHeadline(nothing.copy(ignored = 40)))
            .isEqualTo("Nothing arrived from your banks or payment apps in this period.")
    }

    @Test
    fun stuckLine_appearsOnlyWhenSomethingIsStuck() {
        assertThat(stuckLine(nothing)).isNull()
        assertThat(stuckLine(nothing.copy(stuck = 3))).startsWith("3 messages have waited more than 10 minutes")
    }

    /** Every source a duplicate can have been kept from has a sentence, and so does "gone". */
    @Test
    fun keptLabel_namesEverySource_andTheErasedCase() {
        val labels = (EntrySource.entries.map { KeptCopy(it, 1) } + KeptCopy(null, 1)).map(::keptLabel)

        assertThat(labels.toSet()).hasSize(EntrySource.entries.size + 1)
        assertThat(keptLabel(KeptCopy(null, 1))).isEqualTo("Kept copy no longer in the Inbox")
    }

    // ── Reading ─────────────────────────────────────────────────────────────

    /**
     * The owner's first real report: 52 messages, bars adding to 42, and no
     * explanation. The gap is candidates erased from the Inbox.
     */
    @Test
    fun readingBasis_explainsErasedCandidates_andIsAbsentWhenNoneWere() {
        val captured = nothing.copy(parsed = 32, unmatched = 20)

        assertThat(readingBasis(captured, ReadQualityReport(12, 1, 0, 29, emptyList())))
            .isEqualTo("Out of 52 messages: 10 were erased from the Inbox, so they have no score.")
        assertThat(readingBasis(captured, ReadQualityReport(20, 1, 2, 29, emptyList()))).isNull()
    }

    /** Duplicates made candidates too, so they belong in the base. */
    @Test
    fun readingBasis_countsDuplicatesInTheBase() {
        val captured = nothing.copy(parsed = 2, duplicates = 1)

        assertThat(readingBasis(captured, ReadQualityReport(0, 1, 0, 1, emptyList())))
            .isEqualTo("Out of 3 messages: 1 was erased from the Inbox, so it has no score.")
    }

    // ── Durations ───────────────────────────────────────────────────────────

    /** Pipeline times live under a minute, so they get tenths, from integers. */
    @Test
    fun formatDuration_givesTenthsUnderAMinute() {
        assertThat(formatDuration(0L)).isEqualTo("0.0 s")
        assertThat(formatDuration(1_249L)).isEqualTo("1.2 s")
        assertThat(formatDuration(1_250L)).isEqualTo("1.3 s")
        assertThat(formatDuration(59_900L)).isEqualTo("59.9 s")
        // Tenths would round this up to "60.0 s".
        assertThat(formatDuration(59_960L)).isEqualTo("1 min")
    }

    @Test
    fun formatDuration_givesWholeUnitsAboveAMinute() {
        assertThat(formatDuration(60_000L)).isEqualTo("1 min")
        assertThat(formatDuration(59 * 60_000L)).isEqualTo("59 min")
        assertThat(formatDuration(4 * 3_600_000L)).isEqualTo("4 h")
        assertThat(formatDuration(47 * 3_600_000L)).isEqualTo("47 h")
        assertThat(formatDuration(3 * 86_400_000L)).isEqualTo("3 days")
    }

    /** A clock set back between capture and parse is not reported as negative time. */
    @Test
    fun formatDuration_neverShowsANegativeTime() {
        assertThat(formatDuration(-5_000L)).isEqualTo("0.0 s")
    }

    @Test
    fun speedCard_isHidden_whenThereIsNoTimeToReport() {
        assertThat(hasSpeed(PipelineSpeed(emptyList(), 0, null, null))).isFalse()
        assertThat(hasSpeed(PipelineSpeed(emptyList(), 0, null, oldestWaitingMillis = 1L))).isTrue()
    }

    // ── Rollup ──────────────────────────────────────────────────────────────

    @Test
    fun rollup_neverChecked_saysSo_andClaimsNothingAboutRepairs() {
        val never = RollupHealth(lastReconciledAt = null, bucketsRepaired = null)

        assertThat(rollupChecked(never, Locale.UK)).isEqualTo("Analytics totals have not been re-checked yet.")
        assertThat(rollupRepairs(never)).isNull()
    }

    /** ADR-0006: a repair on a healthy install is a bug, and the screen says so. */
    @Test
    fun rollup_anyRepair_isFlaggedAsAProblem_andZeroIsNot() {
        val checked = 1_790_000_000_000L

        assertThat(rollupRepairs(RollupHealth(checked, 0)))
            .isEqualTo("Nothing needed correcting." to false)
        assertThat(rollupRepairs(RollupHealth(checked, 3))?.second).isTrue()
        assertThat(rollupRepairs(RollupHealth(checked, 3))?.first).contains("3 daily totals")
    }
}
