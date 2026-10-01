package com.ledgerflow.feature.settings.diagnostics

import com.google.common.truth.Truth.assertThat
import com.ledgerflow.core.common.time.Clock
import com.ledgerflow.core.domain.diagnostics.DiagnosticsWindow
import com.ledgerflow.core.domain.diagnostics.IngestDiagnostics
import com.ledgerflow.core.domain.diagnostics.IngestDiagnosticsRepository
import com.ledgerflow.core.domain.usecase.GetIngestDiagnosticsUseCase
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class DiagnosticsViewModelTest {

    private val dispatcher = StandardTestDispatcher()
    private val now = 1_800_000_000_000L

    /**
     * Records each read's window start. Each read takes the next entry of
     * [gates] (none: returns at once), and [reportFor] decides what it returns,
     * so two reads can be told apart by their result.
     */
    private class FakeDiagnostics : IngestDiagnosticsRepository {
        val sinces = mutableListOf<Long>()
        val nows = mutableListOf<Long>()
        val gates = ArrayDeque<CompletableDeferred<Unit>>()
        var reportFor: (Long) -> IngestDiagnostics? = { PreviewDiagnostics }

        override suspend fun snapshot(sinceMillis: Long, nowMillis: Long): IngestDiagnostics? {
            sinces += sinceMillis
            nows += nowMillis
            gates.removeFirstOrNull()?.await()
            return reportFor(sinceMillis)
        }
    }

    private val repository = FakeDiagnostics()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun viewModel() =
        DiagnosticsViewModel(GetIngestDiagnosticsUseCase(repository, Clock { now }))

    /** Nothing is counted until the screen is shown, and then the default is 30 days. */
    @Test
    fun opening_readsThirtyDays_andNothingBefore() = runTest(dispatcher) {
        val vm = viewModel()
        advanceUntilIdle()
        assertThat(repository.sinces).isEmpty()
        assertThat(vm.state.value.report).isEqualTo(DiagnosticsReport.Loading)

        vm.onEvent(DiagnosticsEvent.Resumed)
        advanceUntilIdle()

        assertThat(repository.sinces).containsExactly(DiagnosticsWindow.LAST_30_DAYS.sinceMillis(now))
        assertThat(repository.nows).containsExactly(now)
        assertThat(vm.state.value.window).isEqualTo(DiagnosticsWindow.LAST_30_DAYS)
        assertThat(vm.state.value.report).isEqualTo(DiagnosticsReport.Ready(PreviewDiagnostics))
    }

    @Test
    fun choosingAWindow_rereadsForThatWindow() = runTest(dispatcher) {
        val vm = viewModel()
        vm.onEvent(DiagnosticsEvent.Resumed)
        advanceUntilIdle()

        vm.onEvent(DiagnosticsEvent.WindowSelected(DiagnosticsWindow.ALL_TIME))
        advanceUntilIdle()

        assertThat(vm.state.value.window).isEqualTo(DiagnosticsWindow.ALL_TIME)
        assertThat(repository.sinces.last()).isEqualTo(0L)
    }

    /** Tapping the selected window again is not a reason to count everything again. */
    @Test
    fun choosingTheSelectedWindow_doesNotReread() = runTest(dispatcher) {
        val vm = viewModel()
        vm.onEvent(DiagnosticsEvent.Resumed)
        advanceUntilIdle()

        vm.onEvent(DiagnosticsEvent.WindowSelected(DiagnosticsWindow.LAST_30_DAYS))
        advanceUntilIdle()

        assertThat(repository.sinces).hasSize(1)
    }

    /**
     * A slow read for a window the user has already left must not land last:
     * the report on screen always belongs to the selected window.
     */
    @Test
    fun aNewerRead_cancelsTheOneInFlight() = runTest(dispatcher) {
        val thirtyDays = DiagnosticsWindow.LAST_30_DAYS.sinceMillis(now)
        val stale = PreviewDiagnostics.copy(captured = PreviewDiagnostics.captured.copy(parsed = 1))
        repository.reportFor = { since -> if (since == thirtyDays) stale else PreviewDiagnostics }
        val slowThirtyDayRead = CompletableDeferred<Unit>()
        repository.gates += slowThirtyDayRead
        val vm = viewModel()
        vm.onEvent(DiagnosticsEvent.Resumed)
        advanceUntilIdle()

        vm.onEvent(DiagnosticsEvent.WindowSelected(DiagnosticsWindow.LAST_90_DAYS))
        advanceUntilIdle()
        // The 30-day read finishes after the 90-day one did.
        slowThirtyDayRead.complete(Unit)
        advanceUntilIdle()

        assertThat(vm.state.value.window).isEqualTo(DiagnosticsWindow.LAST_90_DAYS)
        assertThat(vm.state.value.report).isEqualTo(DiagnosticsReport.Ready(PreviewDiagnostics))
    }

    /** A vault that cannot be opened says so, rather than showing zeroes nobody counted. */
    @Test
    fun aVaultThatCannotOpen_isUnavailable_notZero() = runTest(dispatcher) {
        repository.reportFor = { null }
        val vm = viewModel()

        vm.onEvent(DiagnosticsEvent.Resumed)
        advanceUntilIdle()

        assertThat(vm.state.value.report).isEqualTo(DiagnosticsReport.Unavailable)
    }
}
