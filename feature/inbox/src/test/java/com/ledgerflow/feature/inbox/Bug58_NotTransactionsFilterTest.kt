package com.ledgerflow.feature.inbox

import com.google.common.truth.Truth.assertThat
import com.ledgerflow.core.domain.analytics.NoOpBudgetAlertTrigger
import com.ledgerflow.core.domain.inbox.InboxFilter
import com.ledgerflow.core.domain.inbox.PendingTransaction
import com.ledgerflow.core.domain.ingest.ExtractedTransaction
import com.ledgerflow.core.domain.usecase.ApprovePendingUseCase
import com.ledgerflow.core.domain.usecase.ApproveTransactionUseCase
import com.ledgerflow.core.domain.usecase.DiscardPendingUseCase
import com.ledgerflow.core.domain.usecase.ErasePendingUseCase
import com.ledgerflow.core.domain.usecase.MarkPendingAsTransactionUseCase
import com.ledgerflow.core.domain.usecase.ObserveInboxCountsUseCase
import com.ledgerflow.core.domain.usecase.ObservePendingCountUseCase
import com.ledgerflow.core.domain.usecase.ObservePendingUseCase
import com.ledgerflow.core.domain.usecase.RestorePendingUseCase
import com.ledgerflow.core.model.EntrySource
import com.ledgerflow.core.model.NonTransactionKind
import com.ledgerflow.core.model.PendingStatus
import com.ledgerflow.core.testing.inbox.FakePendingRepository
import com.ledgerflow.core.testing.ledger.FakeLedgerRepository
import com.ledgerflow.core.testing.taxonomy.FakeMerchantRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test

/**
 * **BUG58, the Inbox half** (V2-PLAN BUG-B, decision D6).
 *
 * A message the classifier recognised as not a transaction is retained and
 * visible under "Not transactions", kept out of the queue and its count, never
 * erasable in bulk, and back in Pending with one tap. The pipeline half —
 * written, labelled, never announced — is
 * `Bug58_GhostMessagesLandInNotTransactionsTest` in `:feature:ingest`.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class Bug58_NotTransactionsFilterTest {

    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() = Dispatchers.resetMain()

    private val pending = FakePendingRepository()
    private val merchants = FakeMerchantRepository()
    private val ledger = FakeLedgerRepository()

    private fun ghost(id: String, kind: NonTransactionKind?) = PendingTransaction(
        id = id,
        source = EntrySource.SMS,
        extracted = ExtractedTransaction(),
        confidence = 0.0,
        status = PendingStatus.PENDING,
        needsManualFill = true,
        suppressedById = null,
        createdAt = 1_787_810_214_627L,
        reviewedAt = null,
        approvedEntryId = null,
        nonTransactionKind = kind,
    )

    private fun TestScope.viewModel(): InboxViewModel {
        val vm = InboxViewModel(
            observePending = ObservePendingUseCase(pending),
            observePendingCount = ObservePendingCountUseCase(pending),
            observeCounts = ObserveInboxCountsUseCase(pending),
            approvePending = ApprovePendingUseCase(
                pending,
                merchants,
                ApproveTransactionUseCase(ledger, NoOpBudgetAlertTrigger),
            ),
            discardPending = DiscardPendingUseCase(pending),
            restorePending = RestorePendingUseCase(pending),
            erasePending = ErasePendingUseCase(pending),
            markAsTransaction = MarkPendingAsTransactionUseCase(pending),
        )
        backgroundScope.launch { vm.state.collect { } }
        return vm
    }

    @Test
    fun aClassifiedRow_isOutOfTheQueueAndUnderNotTransactions() = runTest(dispatcher) {
        pending.put(ghost("otp", NonTransactionKind.OTP))
        pending.put(ghost("ghost", null))
        val vm = viewModel()
        advanceUntilIdle()

        // The queue and its badge hold only the ghost nobody could label.
        assertThat(vm.state.value.rows.map { it.id }).containsExactly("ghost")
        assertThat(vm.state.value.pendingCount).isEqualTo(1)
        // The chip is offered, because it holds something.
        assertThat(vm.state.value.visibleFilters).contains(InboxFilter.NOT_TRANSACTIONS)
        assertThat(vm.state.value.counts[InboxFilter.NOT_TRANSACTIONS]).isEqualTo(1)

        vm.onEvent(InboxEvent.FilterSelected(InboxFilter.NOT_TRANSACTIONS))
        advanceUntilIdle()
        assertThat(vm.state.value.rows.map { it.id }).containsExactly("otp")
    }

    @Test
    fun moveToPending_putsItBackInTheQueue() = runTest(dispatcher) {
        pending.put(ghost("otp", NonTransactionKind.OTP))
        val vm = viewModel()
        vm.onEvent(InboxEvent.FilterSelected(InboxFilter.NOT_TRANSACTIONS))
        advanceUntilIdle()

        vm.onEvent(InboxEvent.MarkedAsTransaction("otp"))
        advanceUntilIdle()

        assertThat(vm.state.value.rows).isEmpty()
        assertThat(vm.state.value.pendingCount).isEqualTo(1)
        assertThat(pending.get("otp")?.nonTransactionKind).isNull()
    }

    /** D6: never deleted. Not offered by the screen, and refused underneath it. */
    @Test
    fun notTransactions_cannotBeErasedInBulk() = runTest(dispatcher) {
        pending.put(ghost("otp", NonTransactionKind.OTP))
        val vm = viewModel()
        vm.onEvent(InboxEvent.FilterSelected(InboxFilter.NOT_TRANSACTIONS))
        advanceUntilIdle()

        assertThat(vm.state.value.canErase).isFalse()
        assertThat(pending.eraseAll(InboxFilter.NOT_TRANSACTIONS)).isEqualTo(0)
        assertThat(pending.snapshot().keys).containsExactly("otp")
    }

    /** Discarding one and restoring it brings it back where it was, label intact. */
    @Test
    fun discardThenRestore_keepsTheLabel() = runTest(dispatcher) {
        pending.put(ghost("otp", NonTransactionKind.OTP))

        assertThat(pending.discard("otp")).isTrue()
        assertThat(pending.restore("otp")).isTrue()

        assertThat(pending.get("otp")?.nonTransactionKind).isEqualTo(NonTransactionKind.OTP)
    }
}
