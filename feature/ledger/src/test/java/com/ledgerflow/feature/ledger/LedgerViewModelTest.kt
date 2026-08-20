package com.ledgerflow.feature.ledger

import com.google.common.truth.Truth.assertThat
import com.ledgerflow.core.common.time.Clock
import com.ledgerflow.core.common.time.LocalDates
import com.ledgerflow.core.domain.ledger.DraftSummaryFields
import com.ledgerflow.core.domain.ledger.LedgerError
import com.ledgerflow.core.domain.ledger.LedgerRepository
import com.ledgerflow.core.domain.ledger.LedgerResult
import com.ledgerflow.core.domain.usecase.DeleteEntryUseCase
import com.ledgerflow.core.model.LedgerListItem
import com.ledgerflow.core.model.LedgerType
import com.ledgerflow.core.model.Money
import com.ledgerflow.core.testing.ledger.FakeDraftRepository
import com.ledgerflow.core.testing.ledger.FakeLedgerRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test

/**
 * The Ledger tab's ViewModel (SPEC.md §5.5, §9.3).
 *
 * The *query* behaviour — that a book's page holds its own entries and not the
 * other book's, that `since` bounds without deleting — is asserted against a
 * real SQLCipher database in `Bug10_SavedEntryAppearsInLedgerTest`, because
 * that is a statement about SQL and a fake would only echo the Kotlin here.
 *
 * What is tested in this file is the screen's own behaviour: that the segmented
 * control selects a *partition* rather than filtering one, that the day headers
 * get a `today` the ViewModel derived rather than one composition read off the
 * wall clock, and that nothing is deleted without a second tap.
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class LedgerViewModelTest {

    private val dispatcher = StandardTestDispatcher()
    private lateinit var ledger: FakeLedgerRepository
    private lateinit var drafts: FakeDraftRepository

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        ledger = FakeLedgerRepository()
        drafts = FakeDraftRepository()
    }

    @After
    fun tearDown() = Dispatchers.resetMain()

    // ── The partition selector ──────────────────────────────────────────────

    @Test
    fun state_startsOnTheDebitBook() = runTest(dispatcher) {
        val viewModel = viewModel()

        assertThat(viewModel.state.value.ledger).isEqualTo(LedgerType.DEBIT)
    }

    @Test
    fun ledgerSelected_switchesTheBookOnScreen() = runTest(dispatcher) {
        val viewModel = viewModel()

        viewModel.onEvent(LedgerEvent.LedgerSelected(LedgerType.CREDIT))
        dispatcher.scheduler.advanceUntilIdle()

        assertThat(viewModel.state.value.ledger).isEqualTo(LedgerType.CREDIT)
    }

    /**
     * `today` comes off the injected clock.
     *
     * Not a style point: `LocalDate.now()` read inside a composable is
     * untestable, and it is the one value every recency header on the screen
     * has to agree on. Asserting it here is what stops it drifting back into
     * the UI.
     */
    @Test
    fun state_carriesTodayFromTheInjectedClock() = runTest(dispatcher) {
        val viewModel = viewModel()

        assertThat(viewModel.state.value.today).isEqualTo(LocalDates.of(FIXED_NOW))
    }

    /** The list's 30-day bound reaches the repository, and is 30 days back. */
    @Test
    fun entries_areRequestedForTheWindowNotTheWholeBook() = runTest(dispatcher) {
        val viewModel = viewModel()
        CoroutineScope(dispatcher).launch { viewModel.entries.collect {} }
        dispatcher.scheduler.advanceUntilIdle()

        assertThat(ledger.windows).isNotEmpty()
        assertThat(ledger.windows.first())
            .isEqualTo(LocalDates.of(FIXED_NOW) - LedgerRepository.LIST_WINDOW_DAYS)
    }

    // ── Deleting (CHANGE#2) ─────────────────────────────────────────────────

    /**
     * The first tap asks; it does not delete.
     *
     * This is the assertion that matters most in this file. The control is a
     * small icon in a scrolling list, so a delete that fired on the first tap
     * would be one mis-scroll away from destroying a real entry at all times.
     */
    @Test
    fun deleteRequested_asksAndWritesNothing() = runTest(dispatcher) {
        val viewModel = viewModel()

        viewModel.onEvent(LedgerEvent.DeleteRequested("entry-1", "₹69.00 · Transport"))
        dispatcher.scheduler.advanceUntilIdle()

        assertThat(viewModel.state.value.confirmation)
            .isEqualTo(LedgerConfirmation.DeleteEntry("entry-1", "₹69.00 · Transport"))
        assertThat(ledger.deleted).isEmpty()
    }

    @Test
    fun deleteConfirmed_removesTheEntryFromTheBookOnScreen() = runTest(dispatcher) {
        val viewModel = viewModel()

        viewModel.onEvent(LedgerEvent.DeleteRequested("entry-1", "₹69.00 · Transport"))
        viewModel.onEvent(LedgerEvent.ConfirmationAccepted)
        dispatcher.scheduler.advanceUntilIdle()

        assertThat(ledger.deleted).containsExactly(LedgerType.DEBIT to "entry-1")
        assertThat(viewModel.state.value.confirmation).isNull()
    }

    /**
     * The book comes from what is on screen, not from the row.
     *
     * An id carries no book inside it, so if the ViewModel passed the wrong one
     * the repository would refuse — and the user would be told "already gone"
     * about an entry sitting in front of them (Law 2).
     */
    @Test
    fun deleteConfirmed_usesTheSelectedBook() = runTest(dispatcher) {
        val viewModel = viewModel()

        viewModel.onEvent(LedgerEvent.LedgerSelected(LedgerType.CREDIT))
        viewModel.onEvent(LedgerEvent.DeleteRequested("entry-9", "₹85,000.00 · Salary"))
        viewModel.onEvent(LedgerEvent.ConfirmationAccepted)
        dispatcher.scheduler.advanceUntilIdle()

        assertThat(ledger.deleted).containsExactly(LedgerType.CREDIT to "entry-9")
    }

    @Test
    fun deleteDismissed_writesNothing() = runTest(dispatcher) {
        val viewModel = viewModel()

        viewModel.onEvent(LedgerEvent.DeleteRequested("entry-1", "₹69.00 · Transport"))
        viewModel.onEvent(LedgerEvent.ConfirmationDismissed)
        dispatcher.scheduler.advanceUntilIdle()

        assertThat(ledger.deleted).isEmpty()
        assertThat(viewModel.state.value.confirmation).isNull()
    }

    /** A refusal becomes a sentence, and the question closes rather than repeating. */
    @Test
    fun deleteRefused_showsAMessageTheUserCanRead() = runTest(dispatcher) {
        ledger.deleteResult = LedgerResult.Failure(LedgerError.EntryNotFound("entry-1"))
        val viewModel = viewModel()

        viewModel.onEvent(LedgerEvent.DeleteRequested("entry-1", "₹69.00 · Transport"))
        viewModel.onEvent(LedgerEvent.ConfirmationAccepted)
        dispatcher.scheduler.advanceUntilIdle()

        assertThat(viewModel.state.value.message).isEqualTo("That entry is already gone.")
        assertThat(viewModel.state.value.confirmation).isNull()
    }

    @Test
    fun messageDismissed_clearsIt() = runTest(dispatcher) {
        ledger.deleteResult = LedgerResult.Failure(LedgerError.EntryNotFound("entry-1"))
        val viewModel = viewModel()

        viewModel.onEvent(LedgerEvent.DeleteRequested("entry-1", "₹69.00 · Transport"))
        viewModel.onEvent(LedgerEvent.ConfirmationAccepted)
        dispatcher.scheduler.advanceUntilIdle()
        viewModel.onEvent(LedgerEvent.MessageDismissed)
        dispatcher.scheduler.advanceUntilIdle()

        assertThat(viewModel.state.value.message).isNull()
    }

    /**
     * `hasAnyEntries` is per book, and it is what picks the empty-state copy.
     *
     * Getting it wrong tells a user with a full ledger that they have never
     * saved anything, which reads as data loss.
     */
    @Test
    fun hasAnyEntries_followsTheBookOnScreen() = runTest(dispatcher) {
        ledger.emitEntries(LedgerType.DEBIT, listOf(listItem("entry-1")))
        val viewModel = viewModel()

        assertThat(viewModel.state.value.hasAnyEntries).isTrue()

        viewModel.onEvent(LedgerEvent.LedgerSelected(LedgerType.CREDIT))
        dispatcher.scheduler.advanceUntilIdle()

        assertThat(viewModel.state.value.hasAnyEntries).isFalse()
    }

    // ── The unsaved section (CHANGE#1) ──────────────────────────────────────

    @Test
    fun pending_showsTheDraftsOfTheBookOnScreen() = runTest(dispatcher) {
        drafts.seed("draft-1", LedgerType.DEBIT, "{}", summary = DraftSummaryFields(24_050L))
        drafts.seed("draft-2", LedgerType.CREDIT, "{}", summary = DraftSummaryFields(69_00L))
        val viewModel = viewModel()

        assertThat(viewModel.state.value.pending.map { it.id }).containsExactly("draft-1")

        viewModel.onEvent(LedgerEvent.LedgerSelected(LedgerType.CREDIT))
        dispatcher.scheduler.advanceUntilIdle()

        assertThat(viewModel.state.value.pending.map { it.id }).containsExactly("draft-2")
    }

    /**
     * The amount survives the trip through the summary columns.
     *
     * This is what schema v4 exists for: the Ledger cannot read a draft's
     * payload, so if the denormalised copy were not written or not read, every
     * pending row would show zero and the section would be useless.
     */
    @Test
    fun pending_carriesTheAmountFromTheSummary() = runTest(dispatcher) {
        drafts.seed("draft-1", LedgerType.DEBIT, "{}", summary = DraftSummaryFields(24_050L))
        val viewModel = viewModel()

        assertThat(viewModel.state.value.pending.single().amount).isEqualTo(Money(24_050L))
    }

    /** Discarding unsaved work asks first, exactly as deleting a saved entry does. */
    @Test
    fun discardRequested_asksAndDiscardsNothing() = runTest(dispatcher) {
        drafts.seed("draft-1", LedgerType.DEBIT, "{}")
        val viewModel = viewModel()

        viewModel.onEvent(LedgerEvent.DiscardRequested("draft-1", "₹240.50"))
        dispatcher.scheduler.advanceUntilIdle()

        assertThat(viewModel.state.value.confirmation)
            .isEqualTo(LedgerConfirmation.DiscardDraft("draft-1", "₹240.50"))
        assertThat(drafts.discarded).isEmpty()
    }

    @Test
    fun discardConfirmed_removesTheDraft() = runTest(dispatcher) {
        drafts.seed("draft-1", LedgerType.DEBIT, "{}")
        val viewModel = viewModel()

        viewModel.onEvent(LedgerEvent.DiscardRequested("draft-1", "₹240.50"))
        viewModel.onEvent(LedgerEvent.ConfirmationAccepted)
        dispatcher.scheduler.advanceUntilIdle()

        assertThat(drafts.discarded).containsExactly("draft-1")
        assertThat(viewModel.state.value.pending).isEmpty()
        assertThat(viewModel.state.value.confirmation).isNull()
    }

    @Test
    fun discardDismissed_keepsTheDraft() = runTest(dispatcher) {
        drafts.seed("draft-1", LedgerType.DEBIT, "{}")
        val viewModel = viewModel()

        viewModel.onEvent(LedgerEvent.DiscardRequested("draft-1", "₹240.50"))
        viewModel.onEvent(LedgerEvent.ConfirmationDismissed)
        dispatcher.scheduler.advanceUntilIdle()

        assertThat(drafts.discarded).isEmpty()
        assertThat(viewModel.state.value.pending).hasSize(1)
    }

    /**
     * The two questions cannot both be open, and accepting answers the right
     * one.
     *
     * They share a single state field for exactly this reason -- with two
     * nullable fields, a delete confirmation left open behind a discard
     * confirmation would fire both on one tap.
     */
    @Test
    fun asking_asecondQuestion_replacesTheFirst() = runTest(dispatcher) {
        drafts.seed("draft-1", LedgerType.DEBIT, "{}")
        val viewModel = viewModel()

        viewModel.onEvent(LedgerEvent.DeleteRequested("entry-1", "₹69.00"))
        viewModel.onEvent(LedgerEvent.DiscardRequested("draft-1", "₹240.50"))
        viewModel.onEvent(LedgerEvent.ConfirmationAccepted)
        dispatcher.scheduler.advanceUntilIdle()

        assertThat(drafts.discarded).containsExactly("draft-1")
        assertThat(ledger.deleted).isEmpty()
    }

    // ── Helpers ─────────────────────────────────────────────────────────────

    private fun listItem(id: String) = LedgerListItem(
        id = id,
        ledger = LedgerType.DEBIT,
        amount = Money(69_00L),
        currency = "INR",
        occurredAt = FIXED_NOW,
        localDate = LocalDates.of(FIXED_NOW),
        categoryName = "Transport",
        categoryColorArgb = 0,
        merchantName = null,
        note = null,
    )

    /**
     * `state` is a `stateIn(WhileSubscribed)`, so nothing flows until something
     * collects -- reading `.value` with no subscriber returns the initial value
     * forever, and a test that did so would pass whatever the event handler did.
     * On the device the subscriber is `collectAsStateWithLifecycle`.
     */
    private fun viewModel(): LedgerViewModel {
        val viewModel = LedgerViewModel(
            ledger = ledger,
            drafts = drafts,
            deleteEntry = DeleteEntryUseCase(ledger),
            clock = Clock { FIXED_NOW },
        )
        CoroutineScope(dispatcher).launch { viewModel.state.collect {} }
        dispatcher.scheduler.advanceUntilIdle()
        return viewModel
    }

    private companion object {
        /** A fixed instant, so `today` never races midnight on CI. */
        private const val FIXED_NOW = 1_755_540_000_000L
    }
}
