package com.ledgerflow.core.domain.ingest

import com.google.common.truth.Truth.assertThat
import com.ledgerflow.core.common.time.Clock
import com.ledgerflow.core.domain.usecase.AcknowledgeCaptureGapUseCase
import com.ledgerflow.core.domain.usecase.RecordCaptureGapUseCase
import com.ledgerflow.core.domain.vault.RecoveryReason
import com.ledgerflow.core.domain.vault.UpgradeBlockReason
import com.ledgerflow.core.domain.vault.VaultState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * **BUG50 (SPEC §16 Q16): a message that arrived while the vault could not open
 * was only a log line.**
 *
 * §5.1 says a financial SMS is never silently dropped, and a log line on a
 * user's phone is silent. Now the capture entry points report the refusal to
 * [RecordCaptureGapUseCase], which keeps a time window outside the vault for
 * Home to show. These are the rules that decide it.
 */
class Bug50_CaptureGapIsNeverSilentTest {

    private class InMemoryGaps : CaptureGapStore {
        val state = MutableStateFlow<CaptureGap?>(null)
        override val gap = state
        override suspend fun recordMissed(atMillis: Long) {
            state.value = state.value
                ?.let { it.copy(lastMissedAt = atMillis, missed = it.missed + 1) }
                ?: CaptureGap(firstMissedAt = atMillis, lastMissedAt = atMillis, missed = 1)
        }
        override suspend fun clear() {
            state.value = null
        }
    }

    private var now = 1_000L
    private val gaps = InMemoryGaps()
    private val record = RecordCaptureGapUseCase(gaps, Clock { now })

    @Test
    fun Bug50_aLostKeystoreWrap_isRecorded() = runTest {
        assertThat(record(VaultState.NeedsRecovery(RecoveryReason.entries.first()))).isTrue()
        assertThat(gaps.gap.first()).isEqualTo(CaptureGap(1_000L, 1_000L, 1))
    }

    @Test
    fun aMigrationInProgressOrBlocked_isRecorded_becauseTheMessageStillHadNowhereToGo() = runTest {
        assertThat(record(VaultState.Upgrading(from = 10, to = 11))).isTrue()
        assertThat(record(VaultState.UpgradeBlocked(UpgradeBlockReason.SnapshotFailed))).isTrue()
        assertThat(gaps.gap.first()?.missed).isEqualTo(2)
    }

    @Test
    fun beforeSetup_nothingIsRecorded_becauseNothingIsBeingCaptured() = runTest {
        assertThat(record(VaultState.NeedsOnboarding)).isFalse()
        assertThat(gaps.gap.first()).isNull()
    }

    @Test
    fun theWindowWidensFromTheFirstMissToTheLast() = runTest {
        record(VaultState.RestoreInterrupted)
        now = 5_000L
        record(VaultState.RestoreInterrupted)
        assertThat(gaps.gap.first())
            .isEqualTo(CaptureGap(firstMissedAt = 1_000L, lastMissedAt = 5_000L, missed = 2))
    }

    @Test
    fun onlyTheUserClosesIt_andTheNextMissStartsAFreshWindow() = runTest {
        record(VaultState.RestoreInterrupted)
        AcknowledgeCaptureGapUseCase(gaps)()
        assertThat(gaps.gap.first()).isNull()

        now = 9_000L
        record(VaultState.RestoreInterrupted)
        assertThat(gaps.gap.first()).isEqualTo(CaptureGap(9_000L, 9_000L, 1))
    }
}
