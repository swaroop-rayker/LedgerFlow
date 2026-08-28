package com.ledgerflow.feature.inbox

import com.google.common.truth.Truth.assertThat
import com.ledgerflow.core.domain.inbox.InboxFilter
import com.ledgerflow.core.domain.inbox.PendingTransaction
import com.ledgerflow.core.domain.ingest.ExtractedDirection
import com.ledgerflow.core.domain.ingest.ExtractedTransaction
import com.ledgerflow.core.domain.usecase.ApprovePendingUseCase
import com.ledgerflow.core.domain.usecase.ApproveTransactionUseCase
import com.ledgerflow.core.domain.usecase.DiscardPendingUseCase
import com.ledgerflow.core.domain.usecase.ObserveInboxCountsUseCase
import com.ledgerflow.core.domain.usecase.ObservePendingCountUseCase
import com.ledgerflow.core.domain.usecase.ObservePendingUseCase
import com.ledgerflow.core.domain.usecase.ErasePendingUseCase
import com.ledgerflow.core.domain.usecase.RestorePendingUseCase
import com.ledgerflow.core.model.EntrySource
import com.ledgerflow.core.model.Money
import com.ledgerflow.core.model.PendingStatus
import com.ledgerflow.core.testing.inbox.FakePendingRepository
import com.ledgerflow.core.testing.ledger.FakeLedgerRepository
import com.ledgerflow.core.testing.taxonomy.FakeMerchantRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test

/**
 * **Nothing is erased without the warning.** CHANGE#1.
 *
 * `PendingPurgeTest` covers what the SQL refuses; this covers the other half —
 * that the destructive call is unreachable from any list control. The two
 * "requested" events open a dialog and nothing else, and `EraseConfirmed` is
 * the only event in the sealed type that destroys a row. That separation is
 * what makes CLAUDE.md §7's rule structural rather than a thing to remember:
 * a purge is one tap from a small control in a scrolling list, and it cannot be
 * undone.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class InboxEraseConfirmationTest {

    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() = Dispatchers.resetMain()

    private val pending = FakePendingRepository()
    private val merchants = FakeMerchantRepository()
    private val ledger = FakeLedgerRepository()

    private fun discarded(id: String) = PendingTransaction(
        id = id,
        source = EntrySource.SMS,
        extracted = ExtractedTransaction(
            amount = Money(6_900L),
            direction = ExtractedDirection.DEBIT,
            merchantRaw = "SWIGGY",
            confidence = 0.9,
        ),
        confidence = 0.9,
        status = PendingStatus.DISCARDED,
        needsManualFill = false,
        suppressedById = null,
        createdAt = 1_787_810_214_627L,
        reviewedAt = 1_787_810_214_628L,
        approvedEntryId = null,
    )

    /**
     * A ViewModel with its state actively collected.
     *
     * `InboxViewModel.state` is a `WhileSubscribed` flow over database queries,
     * so with nothing collecting it never starts and reads as the initial empty
     * state -- rows absent, counts zero. The screen always collects it, so a
     * test that does not is testing a configuration the app never runs in.
     */
    private fun TestScope.viewModel(): InboxViewModel {
        val vm = newViewModel()
        backgroundScope.launch { vm.state.collect { } }
        return vm
    }

    private fun newViewModel() = InboxViewModel(
        observePending = ObservePendingUseCase(pending),
        observePendingCount = ObservePendingCountUseCase(pending),
        observeCounts = ObserveInboxCountsUseCase(pending),
        approvePending = ApprovePendingUseCase(
            pending,
            merchants,
            ApproveTransactionUseCase(ledger),
        ),
        discardPending = DiscardPendingUseCase(pending),
        restorePending = RestorePendingUseCase(pending),
        erasePending = ErasePendingUseCase(pending),
    )

    private fun ids(): Set<String> = pending.snapshot().keys

    // ── The gate ────────────────────────────────────────────────────────────

    @Test
    fun requestingEraseAll_opensTheWarningAndDestroysNothing() = runTest(dispatcher) {
        pending.put(discarded("d1"))
        pending.put(discarded("d2"))

        val vm = viewModel()
        vm.onEvent(InboxEvent.FilterSelected(InboxFilter.DISCARDED))
        advanceUntilIdle()

        vm.onEvent(InboxEvent.EraseAllRequested)
        advanceUntilIdle()

        assertThat(vm.state.value.confirmation)
            .isEqualTo(InboxConfirmation.EraseAll(count = 2, filter = InboxFilter.DISCARDED))
        // The whole point: the rows are still there.
        assertThat(ids()).containsExactly("d1", "d2")
    }

    @Test
    fun requestingEraseSelected_opensTheWarningAndDestroysNothing() = runTest(dispatcher) {
        pending.put(discarded("d1"))
        pending.put(discarded("d2"))

        val vm = viewModel()
        vm.onEvent(InboxEvent.FilterSelected(InboxFilter.DISCARDED))
        advanceUntilIdle()
        vm.onEvent(InboxEvent.SelectionToggled("d1"))
        advanceUntilIdle()

        vm.onEvent(InboxEvent.EraseSelectedRequested)
        advanceUntilIdle()

        assertThat(vm.state.value.confirmation).isEqualTo(InboxConfirmation.EraseSelected(count = 1))
        assertThat(ids()).containsExactly("d1", "d2")
    }

    @Test
    fun dismissingTheWarning_destroysNothing() = runTest(dispatcher) {
        pending.put(discarded("d1"))

        val vm = viewModel()
        vm.onEvent(InboxEvent.FilterSelected(InboxFilter.DISCARDED))
        advanceUntilIdle()
        vm.onEvent(InboxEvent.EraseAllRequested)
        vm.onEvent(InboxEvent.EraseDismissed)
        advanceUntilIdle()

        assertThat(vm.state.value.confirmation).isNull()
        assertThat(ids()).containsExactly("d1")
    }

    /** Confirming is the only thing that destroys, and it does. */
    @Test
    fun confirming_erasesTheSelection() = runTest(dispatcher) {
        pending.put(discarded("d1"))
        pending.put(discarded("d2"))

        val vm = viewModel()
        vm.onEvent(InboxEvent.FilterSelected(InboxFilter.DISCARDED))
        advanceUntilIdle()
        vm.onEvent(InboxEvent.SelectionToggled("d1"))
        advanceUntilIdle()
        vm.onEvent(InboxEvent.EraseSelectedRequested)
        vm.onEvent(InboxEvent.EraseConfirmed)
        advanceUntilIdle()

        assertThat(ids()).containsExactly("d2")
        assertThat(vm.state.value.selected).isEmpty()
        assertThat(vm.state.value.message).contains("1 item erased")
    }

    /**
     * A confirm with no standing confirmation destroys nothing.
     *
     * The event is public and the screen is stateless, so the ViewModel cannot
     * assume the dialog was ever on screen. Reading the confirmation rather than
     * the live selection is also what makes the erase destroy *what the dialog
     * named* — between it opening and the user confirming, rows can arrive or
     * leave.
     */
    @Test
    fun confirmingWithNoStandingWarning_destroysNothing() = runTest(dispatcher) {
        pending.put(discarded("d1"))

        val vm = viewModel()
        vm.onEvent(InboxEvent.FilterSelected(InboxFilter.DISCARDED))
        advanceUntilIdle()
        vm.onEvent(InboxEvent.SelectionToggled("d1"))
        advanceUntilIdle()

        vm.onEvent(InboxEvent.EraseConfirmed)
        advanceUntilIdle()

        assertThat(ids()).containsExactly("d1")
    }

    // ── Selection hygiene ───────────────────────────────────────────────────

    /**
     * Switching filter clears the ticks.
     *
     * A selection that survived a tab switch would let "Erase 3" name three rows
     * the user can no longer see — which is the count the warning is supposed to
     * make trustworthy.
     */
    @Test
    fun changingFilter_clearsTheSelection() = runTest(dispatcher) {
        pending.put(discarded("d1"))

        val vm = viewModel()
        vm.onEvent(InboxEvent.FilterSelected(InboxFilter.DISCARDED))
        advanceUntilIdle()
        vm.onEvent(InboxEvent.SelectionToggled("d1"))
        advanceUntilIdle()
        assertThat(vm.state.value.hasSelection).isTrue()

        vm.onEvent(InboxEvent.FilterSelected(InboxFilter.PENDING))
        advanceUntilIdle()

        assertThat(vm.state.value.hasSelection).isFalse()
    }

    /** The Pending filter offers no erase at all. */
    @Test
    fun theQueueItselfIsNotErasable() = runTest(dispatcher) {
        val vm = viewModel()
        advanceUntilIdle()

        assertThat(vm.state.value.filter).isEqualTo(InboxFilter.PENDING)
        assertThat(vm.state.value.canErase).isFalse()
    }
}
