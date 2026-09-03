package com.ledgerflow.feature.inbox

import com.google.common.truth.Truth.assertThat
import com.ledgerflow.core.domain.inbox.InboxFilter
import com.ledgerflow.core.domain.inbox.PendingTransaction
import com.ledgerflow.core.domain.ingest.ExtractedDirection
import com.ledgerflow.core.domain.ingest.ExtractedTransaction
import com.ledgerflow.core.domain.analytics.NoOpBudgetAlertTrigger
import com.ledgerflow.core.domain.usecase.ApprovePendingUseCase
import com.ledgerflow.core.domain.usecase.ApproveTransactionUseCase
import com.ledgerflow.core.domain.usecase.DiscardPendingUseCase
import com.ledgerflow.core.domain.usecase.ErasePendingUseCase
import com.ledgerflow.core.domain.usecase.ObserveInboxCountsUseCase
import com.ledgerflow.core.domain.usecase.ObservePendingCountUseCase
import com.ledgerflow.core.domain.usecase.ObservePendingUseCase
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
 * **The Inbox offers the filters that hold something.** (owner)
 *
 * It showed four chips permanently. `Suppressed` is empty unless one payment
 * arrived twice, and `Failed` is empty **by construction** — no path in the app
 * writes that status. Two thirds of the row were furniture advertising screens
 * with nothing on them.
 *
 * The rule is deliberately a *count*, not a list of chips to draw. `FAILED`
 * stays reachable for a cause that is genuinely terminal (SPEC.md §6.1), so a
 * chip deleted outright would hide those rows on the day something finally
 * writes one — §5.1's silent drop, arriving through the filter bar. The last
 * test here is the one that pins that.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class InboxFilterChipsTest {

    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() = Dispatchers.resetMain()

    private val pending = FakePendingRepository()
    private val merchants = FakeMerchantRepository()
    private val ledger = FakeLedgerRepository()

    private fun row(
        id: String,
        status: PendingStatus = PendingStatus.PENDING,
        suppressedById: String? = null,
    ) = PendingTransaction(
        id = id,
        source = EntrySource.SMS,
        extracted = ExtractedTransaction(
            amount = Money(6_900L),
            direction = ExtractedDirection.DEBIT,
            merchantRaw = "SWIGGY",
            confidence = 0.9,
        ),
        confidence = 0.9,
        status = status,
        needsManualFill = false,
        suppressedById = suppressedById,
        createdAt = 1_787_810_214_627L,
        reviewedAt = null,
        approvedEntryId = null,
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
        )
        backgroundScope.launch { vm.state.collect { } }
        return vm
    }

    // ── What is hidden ──────────────────────────────────────────────────────

    /**
     * A fresh install offers one chip.
     *
     * Nothing is captured, nothing is discarded, nothing is suppressed — so
     * there is exactly one place to be, and three chips saying "nothing here"
     * is the clutter this removes.
     */
    @Test
    fun withAnEmptyInbox_onlyTheQueueIsOffered() = runTest(dispatcher) {
        val vm = viewModel()
        advanceUntilIdle()

        assertThat(vm.state.value.visibleFilters).containsExactly(InboxFilter.PENDING)
    }

    /** The queue's chip stays even when the queue itself is empty. */
    @Test
    fun theQueuesChipIsOfferedEvenWithNothingWaiting() = runTest(dispatcher) {
        pending.put(row("d", PendingStatus.DISCARDED))

        val vm = viewModel()
        advanceUntilIdle()

        assertThat(vm.state.value.counts[InboxFilter.PENDING]).isEqualTo(0)
        assertThat(vm.state.value.visibleFilters)
            .containsExactly(InboxFilter.PENDING, InboxFilter.DISCARDED)
            .inOrder()
    }

    // ── What appears ────────────────────────────────────────────────────────

    @Test
    fun aFilterAppearsAsSoonAsItHoldsSomething() = runTest(dispatcher) {
        val vm = viewModel()
        advanceUntilIdle()
        assertThat(vm.state.value.visibleFilters).containsExactly(InboxFilter.PENDING)

        pending.put(row("winner"))
        pending.put(row("loser", suppressedById = "winner"))
        advanceUntilIdle()

        assertThat(vm.state.value.visibleFilters)
            .containsExactly(InboxFilter.PENDING, InboxFilter.SUPPRESSED)
            .inOrder()
    }

    /**
     * **`FAILED` is hidden by being empty, not by being `FAILED`.**
     *
     * Nothing writes that status today, which is exactly what makes deleting
     * the chip tempting and wrong: the value stays reachable for a genuinely
     * terminal cause, and a chip removed by hand would leave those rows
     * invisible on the day one appears. Seeding one proves the chip returns on
     * its own.
     */
    @Test
    fun theFailedChipReturnsTheMomentSomethingWritesThatStatus() = runTest(dispatcher) {
        val vm = viewModel()
        advanceUntilIdle()
        assertThat(vm.state.value.visibleFilters).doesNotContain(InboxFilter.FAILED)

        pending.put(row("broken", PendingStatus.FAILED))
        advanceUntilIdle()

        assertThat(vm.state.value.visibleFilters).contains(InboxFilter.FAILED)
    }

    // ── What must not move under the user ───────────────────────────────────

    /**
     * The chip you are standing on does not vanish when you empty it.
     *
     * Erasing every discarded row while on Discarded would otherwise pull the
     * chip out mid-tap and leave the screen showing a filter with no control
     * selected. It stays until the user leaves it.
     */
    @Test
    fun theSelectedChipSurvivesItsOwnFilterEmptying() = runTest(dispatcher) {
        pending.put(row("d", PendingStatus.DISCARDED))

        val vm = viewModel()
        advanceUntilIdle()
        vm.onEvent(InboxEvent.FilterSelected(InboxFilter.DISCARDED))
        advanceUntilIdle()

        vm.onEvent(InboxEvent.SelectionToggled("d"))
        vm.onEvent(InboxEvent.EraseSelectedRequested)
        vm.onEvent(InboxEvent.EraseConfirmed)
        advanceUntilIdle()

        assertThat(vm.state.value.counts[InboxFilter.DISCARDED]).isEqualTo(0)
        assertThat(vm.state.value.visibleFilters).contains(InboxFilter.DISCARDED)
    }

    @Test
    fun countsMatchWhatEachFilterLists() = runTest(dispatcher) {
        pending.put(row("live-1"))
        pending.put(row("live-2"))
        pending.put(row("gone", PendingStatus.DISCARDED))

        val vm = viewModel()
        advanceUntilIdle()

        assertThat(vm.state.value.counts[InboxFilter.PENDING]).isEqualTo(2)
        assertThat(vm.state.value.counts[InboxFilter.DISCARDED]).isEqualTo(1)
        assertThat(vm.state.value.rows).hasSize(2)
    }
}
