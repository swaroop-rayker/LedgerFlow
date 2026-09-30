package com.ledgerflow.feature.export

import com.google.common.truth.Truth.assertThat
import com.ledgerflow.core.domain.export.ExportFormat
import com.ledgerflow.core.domain.export.ExportRepository
import com.ledgerflow.core.domain.export.ExportResult
import com.ledgerflow.core.domain.usecase.ExportCsvUseCase
import com.ledgerflow.core.domain.usecase.ExportXlsxUseCase
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test

/**
 * The export screen's behaviour (SPEC.md §5.9, ADR-0017).
 *
 * The CSV itself is covered in `:core:data`, against a real vault. What is
 * tested here is the thing that protects the user: that **nothing is written
 * before the warning is answered**, and that backing out of the picker is not
 * reported as a failure.
 */
class ExportViewModelTest {

    private val dispatcher = StandardTestDispatcher()
    private lateinit var export: RecordingExportRepository

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        export = RecordingExportRepository()
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun viewModel() = ExportViewModel(ExportCsvUseCase(export), ExportXlsxUseCase(export))

    /**
     * The whole point of the screen: the tap raises the question, and the
     * question has to be answered before anything leaves the app.
     */
    @Test
    fun exportRequested_asksBeforeItOpensThePicker() = runTest(dispatcher) {
        val vm = viewModel()

        vm.onEvent(ExportEvent.ExportRequested)

        assertThat(vm.state.value.confirming).isTrue()
        assertThat(vm.state.value.pickerRequest).isFalse()
        assertThat(export.exported).isEmpty()
    }

    @Test
    fun dismissingTheWarning_opensNothingAndWritesNothing() = runTest(dispatcher) {
        val vm = viewModel()
        vm.onEvent(ExportEvent.ExportRequested)

        vm.onEvent(ExportEvent.WarningDismissed)

        assertThat(vm.state.value.confirming).isFalse()
        assertThat(vm.state.value.pickerRequest).isFalse()
        assertThat(export.exported).isEmpty()
    }

    @Test
    fun acceptingTheWarning_requestsThePicker() = runTest(dispatcher) {
        val vm = viewModel()
        vm.onEvent(ExportEvent.ExportRequested)

        vm.onEvent(ExportEvent.WarningAccepted)

        assertThat(vm.state.value.confirming).isFalse()
        assertThat(vm.state.value.pickerRequest).isTrue()
        // Still nothing written -- the picker has not answered yet.
        assertThat(export.exported).isEmpty()
    }

    /**
     * The request is consumed the moment the screen launches the picker.
     *
     * Without this a config change while the system picker is in front puts a
     * second picker behind the first, and the user dismisses one to find
     * another.
     */
    @Test
    fun pickerLaunched_clearsTheRequestSoItCannotFireTwice() = runTest(dispatcher) {
        val vm = viewModel()
        vm.onEvent(ExportEvent.ExportRequested)
        vm.onEvent(ExportEvent.WarningAccepted)

        vm.onEvent(ExportEvent.PickerLaunched)

        assertThat(vm.state.value.pickerRequest).isFalse()
    }

    @Test
    fun destinationChosen_writesAndReportsTheCounts() = runTest(dispatcher) {
        export.result = ExportResult.Success(fileCount = 11, rowCount = 1_482)
        val vm = viewModel()

        vm.onEvent(ExportEvent.DestinationChosen("content://docs/export.zip"))
        dispatcher.scheduler.advanceUntilIdle()

        // Excel is the default format (ADR-0004).
        assertThat(export.exported).containsExactly("xlsx:content://docs/export.zip")
        assertThat(vm.state.value.status).isEqualTo(ExportStatus.Done(11, 1_482, ExportFormat.XLSX))
    }

    @Test
    fun choosingCsv_writesCsvAndSaysFiles() = runTest(dispatcher) {
        export.result = ExportResult.Success(fileCount = 21, rowCount = 40)
        val vm = viewModel()

        vm.onEvent(ExportEvent.FormatSelected(ExportFormat.CSV))
        vm.onEvent(ExportEvent.DestinationChosen("content://docs/export.zip"))
        dispatcher.scheduler.advanceUntilIdle()

        assertThat(export.exported).containsExactly("csv:content://docs/export.zip")
        assertThat(vm.state.value.status).isEqualTo(ExportStatus.Done(21, 40, ExportFormat.CSV))
    }

    /**
     * The file the user just named in the picker has that format's extension, so
     * the export must be the format the picker was opened for -- not whatever
     * the control says by the time the export starts.
     */
    @Test
    fun theFormatIsTheOneThePickerWasOpenedFor() = runTest(dispatcher) {
        val vm = viewModel()
        vm.onEvent(ExportEvent.FormatSelected(ExportFormat.CSV))

        vm.onEvent(ExportEvent.DestinationChosen("content://docs/export.zip"))
        vm.onEvent(ExportEvent.FormatSelected(ExportFormat.XLSX))
        dispatcher.scheduler.advanceUntilIdle()

        assertThat(export.exported).containsExactly("csv:content://docs/export.zip")
    }

    @Test
    fun theFormatCannotChangeWhileAnExportRuns() = runTest(dispatcher) {
        // Held open until released, so the export is genuinely mid-run.
        val release = CompletableDeferred<Unit>()
        export.gate = release
        val vm = viewModel()
        vm.onEvent(ExportEvent.DestinationChosen("content://docs/export.xlsx"))
        dispatcher.scheduler.runCurrent()
        assertThat(vm.state.value.status).isEqualTo(ExportStatus.Working)

        vm.onEvent(ExportEvent.FormatSelected(ExportFormat.CSV))
        assertThat(vm.state.value.format).isEqualTo(ExportFormat.XLSX)

        release.complete(Unit)
        dispatcher.scheduler.advanceUntilIdle()
        assertThat(vm.state.value.status).isInstanceOf(ExportStatus.Done::class.java)
    }

    /**
     * Cancelling the picker is not a failure.
     *
     * Reporting "export failed" for a deliberate cancellation is how a screen
     * teaches people to ignore its messages.
     */
    @Test
    fun cancellingThePicker_isSilent() = runTest(dispatcher) {
        val vm = viewModel()

        vm.onEvent(ExportEvent.DestinationChosen(null))
        dispatcher.scheduler.advanceUntilIdle()

        assertThat(export.exported).isEmpty()
        assertThat(vm.state.value.status).isEqualTo(ExportStatus.Idle)
    }

    @Test
    fun aLockedVault_saysSoRatherThanFailingGenerically() = runTest(dispatcher) {
        export.result = ExportResult.VaultLocked
        val vm = viewModel()

        vm.onEvent(ExportEvent.DestinationChosen("content://docs/export.zip"))
        dispatcher.scheduler.advanceUntilIdle()

        val status = vm.state.value.status
        assertThat(status).isInstanceOf(ExportStatus.Failed::class.java)
        assertThat((status as ExportStatus.Failed).message).contains("locked")
    }

    /**
     * A storage failure never shows the user the exception text.
     *
     * A `SecurityException` from a revoked SAF grant and a full disk are the
     * same sentence to the person holding the phone, and printing the technical
     * detail would be the app admitting it does not know what happened.
     */
    @Test
    fun aStorageFailure_becomesAnActionableSentence() = runTest(dispatcher) {
        export.result = ExportResult.Failure("java.lang.SecurityException: no persisted grant")
        val vm = viewModel()

        vm.onEvent(ExportEvent.DestinationChosen("content://docs/export.zip"))
        dispatcher.scheduler.advanceUntilIdle()

        val status = vm.state.value.status as ExportStatus.Failed
        assertThat(status.message).doesNotContain("SecurityException")
        assertThat(status.message).contains("Try somewhere else")
    }

    @Test
    fun statusDismissed_returnsTheScreenToIdle() = runTest(dispatcher) {
        export.result = ExportResult.Success(fileCount = 11, rowCount = 3)
        val vm = viewModel()
        vm.onEvent(ExportEvent.DestinationChosen("content://docs/export.zip"))
        dispatcher.scheduler.advanceUntilIdle()

        vm.onEvent(ExportEvent.StatusDismissed)

        assertThat(vm.state.value.status).isEqualTo(ExportStatus.Idle)
    }

    /** Records what it was asked to do and returns what it was told to. */
    private class RecordingExportRepository : ExportRepository {

        var result: ExportResult = ExportResult.Success(fileCount = 0, rowCount = 0)

        val exported: MutableList<String> = mutableListOf()

        /** When set, every export suspends until it completes. */
        var gate: CompletableDeferred<Unit>? = null

        override suspend fun exportCsv(destinationUri: String): ExportResult {
            exported += "csv:$destinationUri"
            gate?.await()
            return result
        }

        override fun suggestedFileName(): String = "LedgerFlow-export-2026-08-21.zip"

        override suspend fun exportXlsx(destinationUri: String): ExportResult {
            exported += "xlsx:$destinationUri"
            gate?.await()
            return result
        }

        override fun suggestedXlsxFileName(): String = "LedgerFlow-export-2026-08-21.xlsx"
    }
}
