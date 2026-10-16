package com.ledgerflow.feature.dashboard

import com.google.common.truth.Truth.assertThat
import com.ledgerflow.core.common.time.Clock
import com.ledgerflow.core.common.time.ProcessUptime
import com.ledgerflow.core.domain.backup.ObserveLastBackupUseCase
import com.ledgerflow.core.domain.diagnostics.CrashSummary
import com.ledgerflow.core.domain.diagnostics.DismissCrashReportsUseCase
import com.ledgerflow.core.domain.diagnostics.ObserveCrashReportsUseCase
import com.ledgerflow.core.domain.diagnostics.SaveCrashReportsUseCase
import com.ledgerflow.core.domain.ingest.IngestSourceStatus
import com.ledgerflow.core.domain.ingest.IngestSourceType
import com.ledgerflow.core.domain.ingest.ListenerHealthRecord
import com.ledgerflow.core.domain.ingest.ListenerHealthStore
import com.ledgerflow.core.domain.ingest.TransactionIngestSource
import com.ledgerflow.core.domain.usecase.AcknowledgeCaptureGapUseCase
import com.ledgerflow.core.domain.usecase.GetHomeSummaryUseCase
import com.ledgerflow.core.domain.usecase.GetIngestSourceStatusUseCase
import com.ledgerflow.core.domain.usecase.GetNotificationCaptureHealthUseCase
import com.ledgerflow.core.domain.usecase.ObserveCaptureGapUseCase
import com.ledgerflow.core.domain.usecase.ObservePendingCountUseCase
import com.ledgerflow.core.testing.analytics.FakeAnalyticsRepository
import com.ledgerflow.core.testing.backup.FakeBackupRepository
import com.ledgerflow.core.testing.diagnostics.FakeCrashReportRepository
import com.ledgerflow.core.testing.ingest.FakeCaptureGapStore
import com.ledgerflow.core.testing.inbox.FakePendingRepository
import com.ledgerflow.core.testing.ledger.FakeLedgerRepository
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
 * BUG7(c)'s offer on Home: shown after a crash, and the reports are deleted
 * only by the user -- by a save that worked, or by Dismiss. A save that failed
 * keeps them and says so, because a report lost on a failed save was lost for
 * nothing.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CrashReportOfferTest {

    private val dispatcher = StandardTestDispatcher()
    private val crashes = FakeCrashReportRepository(CrashSummary(count = 2, latestAt = 1_790_916_240_000L))

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
        val clock = Clock { 0L }
        val gaps = FakeCaptureGapStore()
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
            ObserveCrashReportsUseCase(crashes),
            SaveCrashReportsUseCase(crashes),
            DismissCrashReportsUseCase(crashes),
            HomeReads(
                ObservePendingCountUseCase(FakePendingRepository()),
                GetHomeSummaryUseCase(FakeAnalyticsRepository()),
                FakeLedgerRepository(),
                clock,
            ),
        )
    }

    @Test
    fun afterACrash_theOfferIsShown() = runTest(dispatcher) {
        val viewModel = viewModel()
        advanceUntilIdle()
        assertThat(viewModel.state.value.crashReports?.count).isEqualTo(2)
    }

    @Test
    fun aSaveThatWorked_writesToThePickedFile_andLetsThemGo() = runTest(dispatcher) {
        val viewModel = viewModel()
        advanceUntilIdle()

        viewModel.saveCrashReports("content://picked/report.txt")
        advanceUntilIdle()

        assertThat(crashes.exportedTo).containsExactly("content://picked/report.txt")
        assertThat(viewModel.state.value.crashReports).isNull()
        assertThat(viewModel.state.value.crashSaveFailed).isFalse()
    }

    @Test
    fun aSaveThatFailed_keepsThem_andSaysSo() = runTest(dispatcher) {
        crashes.exportSucceeds = false
        val viewModel = viewModel()
        advanceUntilIdle()

        viewModel.saveCrashReports("content://picked/report.txt")
        advanceUntilIdle()

        assertThat(viewModel.state.value.crashReports).isNotNull()
        assertThat(viewModel.state.value.crashSaveFailed).isTrue()
    }

    @Test
    fun dismiss_letsThemGo() = runTest(dispatcher) {
        val viewModel = viewModel()
        advanceUntilIdle()

        viewModel.dismissCrashReports()
        advanceUntilIdle()

        assertThat(viewModel.state.value.crashReports).isNull()
    }

    @Test
    fun theWords_onceOrHowManyTimes() {
        assertThat(crashReportSentence(1, "12 Oct, 2:03 pm"))
            .startsWith("LedgerFlow stopped unexpectedly on 12 Oct, 2:03 pm.")
        assertThat(crashReportSentence(3, "12 Oct, 2:03 pm"))
            .startsWith("LedgerFlow stopped unexpectedly 3 times, most recently on 12 Oct, 2:03 pm.")
    }
}
