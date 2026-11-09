package com.ledgerflow.feature.settings.receipts

import com.google.common.truth.Truth.assertThat
import com.ledgerflow.core.domain.receipts.ReceiptPeriod
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test

/**
 * The Receipts page's decisions (V2-5): nothing is deleted without the Warning
 * being answered, the dialog names what it will delete, and the selection
 * belongs to the period it was made in.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ReceiptsViewModelTest {

    private val dispatcher = StandardTestDispatcher()
    private val receipts = FakeReceipts()

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() = Dispatchers.resetMain()

    private fun kotlinx.coroutines.test.TestScope.viewModel(): ReceiptsViewModel =
        ReceiptsViewModel(receipts, receipts, receipts.removeUseCase()).also { vm ->
            backgroundScope.launch { vm.state.collect {} }
            backgroundScope.launch { vm.receipts.collect {} }
            vm.refresh()
            advanceUntilIdle()
        }

    @Test
    fun deletingTheSelection_asksFirst_namingTheCountAndSize() = runTest(dispatcher) {
        val vm = viewModel()
        vm.onEvent(ReceiptsEvent.SelectionStarted)
        vm.onEvent(ReceiptsEvent.Toggled("r1", 412_000))
        vm.onEvent(ReceiptsEvent.Toggled("r2", 268_000))
        vm.onEvent(ReceiptsEvent.DeleteSelectedRequested)
        advanceUntilIdle()

        assertThat(vm.state.value.confirmation)
            .isEqualTo(ReceiptsConfirmation.DeleteSelected(count = 2, bytes = 680_000))
        assertThat(receipts.deletedIds).isEmpty()
    }

    @Test
    fun answeringTheWarning_deletesExactlyTheSelection_andSaysWhatItDid() = runTest(dispatcher) {
        val vm = viewModel()
        vm.onEvent(ReceiptsEvent.SelectionStarted)
        vm.onEvent(ReceiptsEvent.Toggled("r1", 412_000))
        vm.onEvent(ReceiptsEvent.DeleteSelectedRequested)
        vm.onEvent(ReceiptsEvent.ConfirmationAccepted)
        advanceUntilIdle()

        assertThat(receipts.deletedIds).containsExactly(setOf("r1"))
        assertThat(vm.state.value.selecting).isFalse()
        assertThat(vm.state.value.message).isEqualTo("Deleted 1 receipt (412 kB).")
        // Re-measured from the store after the delete, not assumed.
        assertThat(vm.state.value.onPhone?.count).isEqualTo(previewReceipts.size - 1)
    }

    @Test
    fun dismissingTheWarning_deletesNothing() = runTest(dispatcher) {
        val vm = viewModel()
        vm.onEvent(ReceiptsEvent.DeleteAllRequested)
        vm.onEvent(ReceiptsEvent.ConfirmationDismissed)
        advanceUntilIdle()

        assertThat(vm.state.value.confirmation).isNull()
        assertThat(receipts.deletedAll).isEqualTo(0)
    }

    @Test
    fun deleteAll_namesEveryReceiptOnThePhone() = runTest(dispatcher) {
        val vm = viewModel()
        vm.onEvent(ReceiptsEvent.DeleteAllRequested)
        advanceUntilIdle()

        val total = previewReceipts.sumOf { it.bytes }
        assertThat(vm.state.value.confirmation)
            .isEqualTo(ReceiptsConfirmation.DeleteAll(count = previewReceipts.size, bytes = total))

        vm.onEvent(ReceiptsEvent.ConfirmationAccepted)
        advanceUntilIdle()
        assertThat(receipts.deletedAll).isEqualTo(1)
    }

    /** ADR-0035: asked first, then only the ones with a checked Drive copy leave, and it says which stayed. */
    @Test
    fun removingTheSelection_asksFirst_thenRemovesOnlyTheChecked() = runTest(dispatcher) {
        receipts.backedUp("r1")
        val vm = viewModel()
        vm.onEvent(ReceiptsEvent.SelectionStarted)
        vm.onEvent(ReceiptsEvent.Toggled("r1", 412_000))
        vm.onEvent(ReceiptsEvent.Toggled("r2", 268_000))
        vm.onEvent(ReceiptsEvent.RemoveSelectedRequested)
        advanceUntilIdle()
        assertThat(vm.state.value.confirmation)
            .isEqualTo(ReceiptsConfirmation.RemoveSelected(count = 2, bytes = 680_000))

        vm.onEvent(ReceiptsEvent.ConfirmationAccepted)
        advanceUntilIdle()

        assertThat(receipts.rows.value.single { it.id == "r1" }.removedFromPhoneAt).isNotNull()
        assertThat(receipts.rows.value.single { it.id == "r2" }.removedFromPhoneAt).isNull()
        assertThat(vm.state.value.message)
            .isEqualTo(
                "Removed 1 receipt (412 kB) from this phone. " +
                    "1 stayed: not in Google Drive yet. Receipts are copied after the nightly backup.",
            )
        assertThat(vm.state.value.removed?.count).isEqualTo(1)
    }

    /** Without Google Drive backup there is no copy to check: the action is not offered. */
    @Test
    fun withDriveOff_removeIsNotOffered() = runTest(dispatcher) {
        receipts.driveOn = false
        receipts.copies = receipts.copies.copy(drive = false)
        val vm = viewModel()
        vm.onEvent(ReceiptsEvent.SelectionStarted)
        vm.onEvent(ReceiptsEvent.Toggled("r1", 412_000))
        vm.onEvent(ReceiptsEvent.RemoveSelectedRequested)
        advanceUntilIdle()

        assertThat(vm.state.value.canRemoveFromPhone).isFalse()
        assertThat(vm.state.value.confirmation).isNull()
    }

    /** Owner, 2026-11-08: with nothing on the phone it says so, and nothing is deleted. */
    @Test
    fun deleteAll_withNothingOnThePhone_deletesNothing() = runTest(dispatcher) {
        receipts.rows.value = emptyList()
        val vm = viewModel()
        vm.onEvent(ReceiptsEvent.DeleteAllRequested)
        advanceUntilIdle()
        assertThat(vm.state.value.confirmation).isEqualTo(ReceiptsConfirmation.DeleteAll(count = 0, bytes = 0))

        vm.onEvent(ReceiptsEvent.ConfirmationAccepted)
        advanceUntilIdle()

        assertThat(receipts.deletedAll).isEqualTo(0)
        assertThat(vm.state.value.confirmation).isNull()
        assertThat(vm.state.value.message).isNull()
    }

    @Test
    fun selectAll_takesTheWholePeriod_notOnlyTheLoadedRows() = runTest(dispatcher) {
        val vm = viewModel()
        vm.onEvent(ReceiptsEvent.SelectAll)
        advanceUntilIdle()

        assertThat(vm.state.value.selected).containsExactlyElementsIn(previewReceipts.map { it.id })
        assertThat(vm.state.value.selectedBytes).isEqualTo(previewReceipts.sumOf { it.bytes })
    }

    @Test
    fun changingThePeriod_endsTheSelection() = runTest(dispatcher) {
        val vm = viewModel()
        vm.onEvent(ReceiptsEvent.SelectionStarted)
        vm.onEvent(ReceiptsEvent.Toggled("r1", 412_000))
        vm.onEvent(ReceiptsEvent.PeriodSelected(ReceiptPeriod.All))
        advanceUntilIdle()

        assertThat(vm.state.value.selecting).isFalse()
        assertThat(vm.state.value.selected).isEmpty()
        assertThat(vm.state.value.period).isEqualTo(ReceiptPeriod.All)
    }

    /** Ticking rows must not rebuild the list under the user's finger. */
    @Test
    fun tickingARow_doesNotAskForTheListAgain() = runTest(dispatcher) {
        val vm = viewModel()
        val before = receipts.listRequests
        vm.onEvent(ReceiptsEvent.SelectionStarted)
        vm.onEvent(ReceiptsEvent.Toggled("r1", 412_000))
        vm.onEvent(ReceiptsEvent.Toggled("r2", 268_000))
        advanceUntilIdle()

        assertThat(receipts.listRequests).isEqualTo(before)
    }
}
