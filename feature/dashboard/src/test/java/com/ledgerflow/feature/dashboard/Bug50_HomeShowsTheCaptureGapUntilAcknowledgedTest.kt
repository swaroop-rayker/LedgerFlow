package com.ledgerflow.feature.dashboard

import com.google.common.truth.Truth.assertThat
import com.ledgerflow.core.common.time.Clock
import com.ledgerflow.core.common.time.ProcessUptime
import com.ledgerflow.core.domain.backup.ObserveLastBackupUseCase
import com.ledgerflow.core.domain.ingest.CaptureGap
import com.ledgerflow.core.domain.ingest.IngestSourceStatus
import com.ledgerflow.core.domain.ingest.IngestSourceType
import com.ledgerflow.core.domain.ingest.ListenerHealthRecord
import com.ledgerflow.core.domain.ingest.ListenerHealthStore
import com.ledgerflow.core.domain.ingest.TransactionIngestSource
import com.ledgerflow.core.domain.usecase.AcknowledgeCaptureGapUseCase
import com.ledgerflow.core.domain.usecase.GetIngestSourceStatusUseCase
import com.ledgerflow.core.domain.usecase.GetNotificationCaptureHealthUseCase
import com.ledgerflow.core.domain.usecase.ObserveCaptureGapUseCase
import com.ledgerflow.core.testing.backup.FakeBackupRepository
import com.ledgerflow.core.testing.ingest.FakeCaptureGapStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test

/**
 * **BUG50 (§16 Q16), the half the user sees:** a window recorded while Home is
 * open appears without a resume, stays until "Got it", and goes when pressed.
 * The words themselves are pinned too, because "between" with two equal stamps
 * reads as a bug of its own.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class Bug50_HomeShowsTheCaptureGapUntilAcknowledgedTest {

    private val dispatcher = StandardTestDispatcher()
    private val gaps = FakeCaptureGapStore()

    private class HealthyStore : ListenerHealthStore {
        override val record: Flow<ListenerHealthRecord> = MutableStateFlow(ListenerHealthRecord())
        override suspend fun current(): ListenerHealthRecord = ListenerHealthRecord()
        override suspend fun recordConnected(atMillis: Long) = Unit
        override suspend fun recordDisconnected(atMillis: Long) = Unit
        override suspend fun recordGrantObserved(atMillis: Long) = Unit
    }

    private class Source : TransactionIngestSource {
        override val sourceType: IngestSourceType = IngestSourceType.NOTIFICATION
        override suspend fun status(): IngestSourceStatus = IngestSourceStatus.READY
    }

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() = Dispatchers.resetMain()

    private fun viewModel(): DashboardViewModel {
        val clock = Clock { NOW }
        return DashboardViewModel(
            GetNotificationCaptureHealthUseCase(
                getIngestSourceStatus = GetIngestSourceStatusUseCase(setOf(Source())),
                healthStore = HealthyStore(),
                clock = clock,
                processUptime = ProcessUptime { 0L },
            ),
            ObserveLastBackupUseCase(FakeBackupRepository(), clock),
            ObserveCaptureGapUseCase(gaps),
            AcknowledgeCaptureGapUseCase(gaps),
        )
    }

    @Test
    fun Bug50_aGapRecordedWhileHomeIsOpen_appearsWithoutAResume() = runTest(dispatcher) {
        val viewModel = viewModel()
        advanceUntilIdle()
        assertThat(viewModel.state.value.captureGap).isNull()

        gaps.recordMissed(NOW)
        advanceUntilIdle()

        assertThat(viewModel.state.value.captureGap).isEqualTo(CaptureGap(NOW, NOW, 1))
    }

    @Test
    fun gotIt_closesIt() = runTest(dispatcher) {
        gaps.recordMissed(NOW)
        val viewModel = viewModel()
        advanceUntilIdle()

        viewModel.acknowledgeCaptureGap()
        advanceUntilIdle()

        assertThat(viewModel.state.value.captureGap).isNull()
        assertThat(gaps.state.value).isNull()
    }

    @Test
    fun theWords_nameTheWindow_andOneMomentIsNotABetween() {
        assertThat(captureGapSentence("2 Oct, 10:14 am", "3 Oct, 8:02 am"))
            .startsWith("Between 2 Oct, 10:14 am and 3 Oct, 8:02 am, LedgerFlow couldn't record messages")
        assertThat(captureGapSentence("2 Oct, 10:14 am", "2 Oct, 10:14 am"))
            .startsWith("At 2 Oct, 10:14 am, LedgerFlow couldn't record messages")
    }

    private companion object {
        const val NOW = 1_790_916_240_000L
    }
}
