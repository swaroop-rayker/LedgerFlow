package com.ledgerflow.feature.settings.receipts

import androidx.lifecycle.SavedStateHandle
import com.google.common.truth.Truth.assertThat
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

/** One receipt's page (V2-5). */
@OptIn(ExperimentalCoroutinesApi::class)
class ReceiptViewModelTest {

    private val dispatcher = StandardTestDispatcher()
    private val receipts = FakeReceipts()

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() = Dispatchers.resetMain()

    private fun viewModel(id: String) =
        ReceiptViewModel(receipts, dispatcher, SavedStateHandle(mapOf(ReceiptViewModel.ATTACHMENT_ID_ARG to id)))

    /** A file that is missing or does not open is said, not drawn as a blank (ADR-0023). */
    @Test
    fun anImageThatDoesNotOpen_isShownAsUnavailable() = runTest(dispatcher) {
        val vm = viewModel("r1")
        advanceUntilIdle()

        assertThat(vm.state.value.receipt?.id).isEqualTo("r1")
        assertThat(vm.state.value.image).isEqualTo(ReceiptPicture.Unavailable)
    }

    @Test
    fun deleting_asksFirst_thenDeletesThisOne_andClosesThePage() = runTest(dispatcher) {
        val vm = viewModel("r2")
        advanceUntilIdle()
        vm.onEvent(ReceiptEvent.DeleteRequested)
        assertThat(vm.state.value.confirmingDelete).isTrue()
        assertThat(receipts.deletedIds).isEmpty()

        vm.onEvent(ReceiptEvent.DeleteConfirmed)
        advanceUntilIdle()

        assertThat(receipts.deletedIds).containsExactly(setOf("r2"))
        assertThat(vm.state.value.gone).isTrue()
    }

    @Test
    fun aReceiptThatNoLongerExists_closesThePage() = runTest(dispatcher) {
        val vm = viewModel("gone")
        advanceUntilIdle()

        assertThat(vm.state.value.gone).isTrue()
    }
}
