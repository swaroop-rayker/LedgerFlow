package com.ledgerflow.feature.export

import com.google.common.truth.Truth.assertThat
import com.ledgerflow.core.domain.export.ExportRepository
import com.ledgerflow.core.domain.export.ExportResult
import com.ledgerflow.core.domain.usecase.ExportCsvUseCase
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

    private fun viewModel() = ExportViewModel(ExportCsvUseCase(export))

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

        assertThat(export.exported).containsExactly("content://docs/export.zip")
        assertThat(vm.state.value.status).isEqualTo(ExportStatus.Done(11, 1_482))
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

        override suspend fun exportCsv(destinationUri: String): ExportResult {
            exported += destinationUri
            return result
        }

        override fun suggestedFileName(): String = "LedgerFlow-export-2026-08-21.zip"
    }
}
