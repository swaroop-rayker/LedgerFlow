package com.ledgerflow.feature.ledger

import com.google.common.truth.Truth.assertThat
import com.ledgerflow.core.common.time.Clock
import com.ledgerflow.core.domain.inbox.PendingTransaction
import com.ledgerflow.core.domain.ingest.ExtractedDirection
import com.ledgerflow.core.domain.ingest.ExtractedTransaction
import com.ledgerflow.core.domain.usecase.DeleteEntryUseCase
import com.ledgerflow.core.domain.usecase.ObservePendingUseCase
import com.ledgerflow.core.model.EntrySource
import com.ledgerflow.core.model.LedgerType
import com.ledgerflow.core.model.Money
import com.ledgerflow.core.model.PendingStatus
import com.ledgerflow.core.testing.inbox.FakePendingRepository
import com.ledgerflow.core.testing.ledger.FakeDraftRepository
import com.ledgerflow.core.testing.ledger.FakeLedgerRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test

/**
 * **The Ledger's "To review" band.** CHANGE#2.
 *
 * SPEC.md §6.1.2 kept `pending_transaction` and `draft_entry` apart on the
 * grounds that "one gates a commit and is what Law 1 is about, the other
 * recovers typing and gates nothing". BUG14 dissolved that: a candidate now
 * holds typing too, so it genuinely is unsaved work and the Ledger is a
 * reasonable place to admit it exists. §5.4 is amended to say so.
 *
 * What is **not** amended is Law 2, and the case that tests it is the candidate
 * whose direction the parser could not read. It has no book, so it shows on
 * both tabs — and the assertions below are careful about why that is allowed:
 * Law 2 forbids combining debits and credits into one *figure*. This is a list.
 * Nothing here is summed, and the row is filed into exactly one ledger the
 * moment someone approves it.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class LedgerCandidateBandTest {

    private companion object {
        const val FIXED_NOW = 1_787_000_000_000L
    }

    private val dispatcher = StandardTestDispatcher()
    private lateinit var ledger: FakeLedgerRepository
    private lateinit var drafts: FakeDraftRepository
    private lateinit var pending: FakePendingRepository

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        ledger = FakeLedgerRepository()
        drafts = FakeDraftRepository()
        pending = FakePendingRepository()
    }

    @After
    fun tearDown() = Dispatchers.resetMain()

    private fun candidate(
        id: String,
        direction: ExtractedDirection,
        status: PendingStatus = PendingStatus.PENDING,
        suppressedById: String? = null,
    ) = PendingTransaction(
        id = id,
        source = EntrySource.SMS,
        extracted = ExtractedTransaction(
            amount = Money(6_900L).takeIf { direction != ExtractedDirection.UNKNOWN },
            direction = direction,
            merchantRaw = "SWIGGY".takeIf { direction != ExtractedDirection.UNKNOWN },
            confidence = if (direction == ExtractedDirection.UNKNOWN) 0.0 else 0.9,
        ),
        confidence = if (direction == ExtractedDirection.UNKNOWN) 0.0 else 0.9,
        status = status,
        needsManualFill = direction == ExtractedDirection.UNKNOWN,
        suppressedById = suppressedById,
        createdAt = FIXED_NOW,
        reviewedAt = null,
        approvedEntryId = null,
    )

    private fun viewModel(): LedgerViewModel {
        val viewModel = LedgerViewModel(
            ledger = ledger,
            drafts = drafts,
            deleteEntry = DeleteEntryUseCase(ledger),
            observePending = ObservePendingUseCase(pending),
            clock = Clock { FIXED_NOW },
        )
        CoroutineScope(dispatcher).launch { viewModel.state.collect {} }
        dispatcher.scheduler.advanceUntilIdle()
        return viewModel
    }

    // ── Filed into the book the message named ───────────────────────────────

    @Test
    fun aDebitCandidate_showsOnExpensesAndNotOnIncome() = runTest(dispatcher) {
        pending.put(candidate("debit", ExtractedDirection.DEBIT))
        val viewModel = viewModel()

        viewModel.onEvent(LedgerEvent.LedgerSelected(LedgerType.DEBIT))
        dispatcher.scheduler.advanceUntilIdle()
        assertThat(viewModel.state.value.candidates.map { it.id }).containsExactly("debit")

        // The second half is the one that matters: "it appears in its own book"
        // passes even for a filter that has stopped filtering.
        viewModel.onEvent(LedgerEvent.LedgerSelected(LedgerType.CREDIT))
        dispatcher.scheduler.advanceUntilIdle()
        assertThat(viewModel.state.value.candidates).isEmpty()
    }

    @Test
    fun aCreditCandidate_showsOnIncomeAndNotOnExpenses() = runTest(dispatcher) {
        pending.put(candidate("credit", ExtractedDirection.CREDIT))
        val viewModel = viewModel()

        viewModel.onEvent(LedgerEvent.LedgerSelected(LedgerType.CREDIT))
        dispatcher.scheduler.advanceUntilIdle()
        assertThat(viewModel.state.value.candidates.map { it.id }).containsExactly("credit")

        viewModel.onEvent(LedgerEvent.LedgerSelected(LedgerType.DEBIT))
        dispatcher.scheduler.advanceUntilIdle()
        assertThat(viewModel.state.value.candidates).isEmpty()
    }

    // ── The one with no book ────────────────────────────────────────────────

    /**
     * **§5.1's never-drop row shows on both tabs, and that is the point.**
     *
     * A message no rule understood has no direction to file it by. Hiding it
     * until the user guesses which tab to look under would be the silent drop
     * this whole pipeline exists to avoid — and it is the row that most needs
     * attention, because nothing was extracted and nothing else will remind
     * them it happened.
     */
    @Test
    fun aCandidateWithNoReadableDirection_showsOnBothBooks() = runTest(dispatcher) {
        pending.put(candidate("unread", ExtractedDirection.UNKNOWN))
        val viewModel = viewModel()

        viewModel.onEvent(LedgerEvent.LedgerSelected(LedgerType.DEBIT))
        dispatcher.scheduler.advanceUntilIdle()
        assertThat(viewModel.state.value.candidates.map { it.id }).containsExactly("unread")

        viewModel.onEvent(LedgerEvent.LedgerSelected(LedgerType.CREDIT))
        dispatcher.scheduler.advanceUntilIdle()
        assertThat(viewModel.state.value.candidates.map { it.id }).containsExactly("unread")
    }

    /**
     * Law 2 holds: the two books never carry each other's candidates.
     *
     * Showing one directionless row twice is not mixing the books — it has no
     * book yet. Showing a *debit* on the Income tab would be, and this is what
     * says the filter does not do that even with all three kinds present.
     */
    @Test
    fun eachBookSeesItsOwnCandidatesPlusTheUnreadableOnesOnly() = runTest(dispatcher) {
        pending.put(candidate("debit", ExtractedDirection.DEBIT))
        pending.put(candidate("credit", ExtractedDirection.CREDIT))
        pending.put(candidate("unread", ExtractedDirection.UNKNOWN))
        val viewModel = viewModel()

        viewModel.onEvent(LedgerEvent.LedgerSelected(LedgerType.DEBIT))
        dispatcher.scheduler.advanceUntilIdle()
        assertThat(viewModel.state.value.candidates.map { it.id })
            .containsExactly("debit", "unread")

        viewModel.onEvent(LedgerEvent.LedgerSelected(LedgerType.CREDIT))
        dispatcher.scheduler.advanceUntilIdle()
        assertThat(viewModel.state.value.candidates.map { it.id })
            .containsExactly("credit", "unread")
    }

    // ── What the band must not list ─────────────────────────────────────────

    /**
     * Only the queue proper.
     *
     * A discarded candidate is one the user has already said no to, and an
     * approved one is in the ledger below — listing either as "to review" would
     * ask them to do work they have already done. The filter is
     * `InboxFilter.PENDING`, which also excludes §3.1's suppressed duplicates:
     * those are visible in the Inbox and are not work.
     */
    @Test
    fun discardedAndApprovedAndSuppressedCandidates_areNotListed() = runTest(dispatcher) {
        pending.put(candidate("live", ExtractedDirection.DEBIT))
        pending.put(
            candidate("discarded", ExtractedDirection.DEBIT, status = PendingStatus.DISCARDED),
        )
        pending.put(
            candidate("approved", ExtractedDirection.DEBIT, status = PendingStatus.APPROVED),
        )
        pending.put(
            candidate("suppressed", ExtractedDirection.DEBIT, suppressedById = "live"),
        )

        val viewModel = viewModel()
        viewModel.onEvent(LedgerEvent.LedgerSelected(LedgerType.DEBIT))
        dispatcher.scheduler.advanceUntilIdle()

        assertThat(viewModel.state.value.candidates.map { it.id }).containsExactly("live")
    }

    /** An empty queue leaves the band absent rather than empty. */
    @Test
    fun withNothingToReview_theBandIsEmpty() = runTest(dispatcher) {
        val viewModel = viewModel()
        viewModel.onEvent(LedgerEvent.LedgerSelected(LedgerType.DEBIT))
        dispatcher.scheduler.advanceUntilIdle()

        assertThat(viewModel.state.value.candidates).isEmpty()
    }

    /**
     * Candidates and drafts stay separate collections.
     *
     * They open different screens and their discards mean different things — a
     * draft's is final, a candidate's is reversible for 30 days — so merging
     * them into one list would be one band with two behaviours hiding in it.
     */
    @Test
    fun candidatesDoNotLeakIntoTheDraftsBand() = runTest(dispatcher) {
        pending.put(candidate("live", ExtractedDirection.DEBIT))
        val viewModel = viewModel()

        viewModel.onEvent(LedgerEvent.LedgerSelected(LedgerType.DEBIT))
        dispatcher.scheduler.advanceUntilIdle()

        assertThat(viewModel.state.value.candidates).hasSize(1)
        assertThat(viewModel.state.value.pending).isEmpty()
    }
}
