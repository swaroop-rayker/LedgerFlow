package com.ledgerflow.feature.dashboard

import com.google.common.truth.Truth.assertThat
import com.ledgerflow.core.common.time.Clock
import com.ledgerflow.core.common.time.ProcessUptime
import com.ledgerflow.core.domain.backup.BackupReminder
import com.ledgerflow.core.domain.backup.ObserveLastBackupUseCase
import com.ledgerflow.core.domain.ingest.IngestSourceStatus
import com.ledgerflow.core.domain.ingest.IngestSourceType
import com.ledgerflow.core.domain.ingest.ListenerHealthRecord
import com.ledgerflow.core.domain.ingest.ListenerHealthStore
import com.ledgerflow.core.domain.ingest.TransactionIngestSource
import com.ledgerflow.core.domain.usecase.GetIngestSourceStatusUseCase
import com.ledgerflow.core.domain.usecase.GetNotificationCaptureHealthUseCase
import com.ledgerflow.core.testing.backup.FakeBackupRepository
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
 * Home's backup reminder (owner, 2026-09-19): shown with no backup or none in
 * over seven days, gone the moment a backup lands, and re-judged on resume —
 * because the same date grows stale while nothing emits.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DashboardBackupReminderTest {

    private val dispatcher = StandardTestDispatcher()
    private val day = 24L * 60L * 60L * 1000L
    private var now = 1_790_000_000_000L
    private lateinit var backups: FakeBackupRepository

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
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        backups = FakeBackupRepository()
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun viewModel(): DashboardViewModel {
        val clock = Clock { now }
        val health = GetNotificationCaptureHealthUseCase(
            getIngestSourceStatus = GetIngestSourceStatusUseCase(setOf(Source())),
            healthStore = HealthyStore(),
            clock = clock,
            processUptime = ProcessUptime { 0L },
        )
        return DashboardViewModel(health, ObserveLastBackupUseCase(backups, clock))
    }

    @Test
    fun noBackupAtAll_isReminded() = runTest(dispatcher) {
        backups.lastBackup.value = null
        val vm = viewModel()
        advanceUntilIdle()

        assertThat(vm.state.value.backupReminder).isEqualTo(BackupReminder.NeverBackedUp)
    }

    /**
     * Nothing is claimed before the date is read: no first frame of "No backup
     * yet". The resume effect's refresh can run before the observation's first
     * emission, and judging then would read "never read" as "never backed up".
     */
    @Test
    fun beforeTheDateIsRead_nothingIsShown_evenOnAResume() = runTest(dispatcher) {
        backups.lastBackup.value = now - day
        val vm = viewModel()

        vm.refresh()

        assertThat(vm.state.value.backupReminder).isNull()
        advanceUntilIdle()
        assertThat(vm.state.value.backupReminder).isNull()
    }

    @Test
    fun aRecentBackup_isNotReminded() = runTest(dispatcher) {
        backups.lastBackup.value = now - 2 * day
        val vm = viewModel()
        advanceUntilIdle()

        assertThat(vm.state.value.backupReminder).isNull()
    }

    /** Backing up on the Back up now screen clears Home without a resume. */
    @Test
    fun aBackupMadeWhileHomeIsOpen_clearsIt() = runTest(dispatcher) {
        backups.lastBackup.value = now - 10 * day
        val vm = viewModel()
        advanceUntilIdle()
        assertThat(vm.state.value.backupReminder).isEqualTo(BackupReminder.Stale(10))

        backups.lastBackup.value = now
        advanceUntilIdle()

        assertThat(vm.state.value.backupReminder).isNull()
    }

    /** Six days old when Home opened, eight on the next resume: the same date, re-judged. */
    @Test
    fun timePassing_makesItStale_onTheNextResume() = runTest(dispatcher) {
        backups.lastBackup.value = now - 6 * day
        val vm = viewModel()
        advanceUntilIdle()
        assertThat(vm.state.value.backupReminder).isNull()

        now += 2 * day
        vm.refresh()
        advanceUntilIdle()

        assertThat(vm.state.value.backupReminder).isEqualTo(BackupReminder.Stale(8))
    }
}
