package com.ledgerflow.feature.inbox

import androidx.lifecycle.SavedStateHandle
import com.google.common.truth.Truth.assertThat
import com.ledgerflow.core.common.id.Uuid7Generator
import com.ledgerflow.core.domain.inbox.PendingTransaction
import com.ledgerflow.core.domain.ingest.ExtractedDirection
import com.ledgerflow.core.domain.ingest.ExtractedTransaction
import com.ledgerflow.core.domain.usecase.ApprovePendingUseCase
import com.ledgerflow.core.domain.usecase.ApproveTransactionUseCase
import com.ledgerflow.core.domain.usecase.DiscardPendingUseCase
import com.ledgerflow.core.domain.usecase.GetPendingUseCase
import com.ledgerflow.core.domain.usecase.ObserveCategoryTreeUseCase
import com.ledgerflow.core.model.EntrySource
import com.ledgerflow.core.model.Money
import com.ledgerflow.core.model.PendingStatus
import com.ledgerflow.core.testing.inbox.FakePendingRepository
import com.ledgerflow.core.testing.ledger.FakeLedgerRepository
import com.ledgerflow.core.testing.taxonomy.FakeCategoryRepository
import com.ledgerflow.core.testing.taxonomy.FakeMerchantRepository
import com.ledgerflow.core.testing.taxonomy.FakePaymentMethodRepository
import java.security.SecureRandom
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test

/**
 * **BUG14 — a half-reviewed candidate lost its typing on back.** v8.
 *
 * BUG6's rule, applied to the screen that did not have it. The entry form
 * persists to `draft_entry` on every keystroke; the review screen held its
 * typing in the ViewModel, so leaving and coming back gave you the parser's
 * extraction again and the note, category and amount you had entered were gone.
 *
 * **Back is not a background.** It pops the destination, which clears the
 * ViewModel and takes its `SavedStateHandle` with it — that survives a
 * configuration change, which is a different event entirely. Only disk survives
 * the gesture the user actually makes, which is why this needed schema v8
 * rather than a `rememberSaveable`.
 *
 * The ViewModel is recreated between the two halves of each test on purpose:
 * that *is* the back press. Asserting on one long-lived instance would prove
 * only that a `StateFlow` holds its value.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class Bug14_ReviewSurvivesBackPressTest {

    private companion object {
        /** Comfortably past the 300 ms coalescing window. */
        const val PAST_DEBOUNCE = 400L
    }

    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() = Dispatchers.resetMain()

    private val pending = FakePendingRepository()
    private val merchants = FakeMerchantRepository()
    private val paymentMethods = FakePaymentMethodRepository()
    private val categories = FakeCategoryRepository()
    private val ledger = FakeLedgerRepository()

    private fun candidate() = PendingTransaction(
        id = "p1",
        source = EntrySource.SMS,
        extracted = ExtractedTransaction(
            amount = Money(6_900L),
            direction = ExtractedDirection.DEBIT,
            merchantRaw = "SWIGGY",
            confidence = 0.9,
        ),
        confidence = 0.9,
        status = PendingStatus.PENDING,
        needsManualFill = false,
        suppressedById = null,
        createdAt = 1_787_810_214_627L,
        reviewedAt = null,
        approvedEntryId = null,
    )

    /** A fresh ViewModel over the same repository — i.e. reopening the screen. */
    private fun viewModel() = ReviewViewModel(
        savedStateHandle = SavedStateHandle(mapOf(ReviewViewModel.PENDING_ID_ARG to "p1")),
        getPending = GetPendingUseCase(pending),
        approvePending = ApprovePendingUseCase(
            pending,
            merchants,
            ApproveTransactionUseCase(ledger),
        ),
        discardPending = DiscardPendingUseCase(pending),
        pendingRepository = pending,
        observeCategoryTree = ObserveCategoryTreeUseCase(categories),
        merchants = merchants,
        paymentMethods = paymentMethods,
        ledgerRepository = ledger,
        ids = Uuid7Generator(SecureRandom()),
    )

    // ── The bug ─────────────────────────────────────────────────────────────

    @Test
    fun typingANote_thenLeavingAndReturning_keepsTheNote() = runTest(dispatcher) {
        pending.put(candidate())

        val first = viewModel()
        advanceUntilIdle()
        first.onEvent(ReviewEvent.NoteChanged("split with Anita"))
        advanceTimeBy(PAST_DEBOUNCE)
        advanceUntilIdle()

        // Back: the destination is popped and the ViewModel is gone.
        val reopened = viewModel()
        advanceUntilIdle()

        assertThat(reopened.state.value.noteText).isEqualTo("split with Anita")
    }

    @Test
    fun editingTheAmount_thenLeavingAndReturning_keepsTheEditedAmount() = runTest(dispatcher) {
        pending.put(candidate())

        val first = viewModel()
        advanceUntilIdle()
        // The parser read 69.00; the user corrects it.
        first.onEvent(ReviewEvent.AmountChanged("75.50"))
        advanceTimeBy(PAST_DEBOUNCE)
        advanceUntilIdle()

        val reopened = viewModel()
        advanceUntilIdle()

        assertThat(reopened.state.value.amountText).isEqualTo("75.50")
    }

    /**
     * Raw text, not a reparsed value.
     *
     * A restored form that had rewritten "12." as "12.00" would have edited the
     * user's input while they were away, and moved their caret. The payload
     * stores what was typed for exactly this reason.
     */
    @Test
    fun aHalfTypedAmount_comesBackExactlyAsTyped() = runTest(dispatcher) {
        pending.put(candidate())

        val first = viewModel()
        advanceUntilIdle()
        first.onEvent(ReviewEvent.AmountChanged("12."))
        advanceTimeBy(PAST_DEBOUNCE)
        advanceUntilIdle()

        val reopened = viewModel()
        advanceUntilIdle()

        assertThat(reopened.state.value.amountText).isEqualTo("12.")
    }

    // ── What must NOT be persisted ──────────────────────────────────────────

    /**
     * Opening a candidate and leaving without touching it writes nothing.
     *
     * Without a baseline, the first debounce tick after load would persist the
     * extraction back onto the row and **every** candidate in the Inbox would
     * look edited. That is not a cosmetic problem: a draft is what the Ledger's
     * "To review" band and any future "edited" marker key off.
     */
    @Test
    fun openingWithoutTyping_writesNoDraft() = runTest(dispatcher) {
        pending.put(candidate())

        viewModel()
        advanceTimeBy(PAST_DEBOUNCE)
        advanceUntilIdle()

        assertThat(pending.get("p1")?.reviewDraftJson).isNull()
    }

    /**
     * **An edit undone leaves nothing behind — and this is the case that caught
     * a real bug.**
     *
     * The first version of the persistence merely *skipped* the write when the
     * state returned to the baseline. That left the last edit sitting on disk,
     * so a user who typed 99.00, thought better of it, restored 69.00 and left
     * would reopen to **99.00** — their correction discarded and an intermediate
     * value handed back as though they had chosen it. Worse than losing the
     * typing, because it looks deliberate.
     *
     * The assertion is deliberately on a **reopen** rather than on the column.
     * Checking `reviewDraftJson` is null would pass for a mechanism that wrote
     * the right thing and read it back wrongly; only reopening tests what the
     * user actually experiences.
     */
    @Test
    fun typingAndThenRestoringTheOriginal_reopensToTheOriginal() = runTest(dispatcher) {
        pending.put(candidate())

        val vm = viewModel()
        advanceUntilIdle()
        val original = vm.state.value.amountText

        vm.onEvent(ReviewEvent.AmountChanged("99.00"))
        advanceTimeBy(PAST_DEBOUNCE)
        advanceUntilIdle()
        // The intermediate edit really was persisted -- so the clean-up below
        // is undoing something rather than never having happened.
        assertThat(pending.get("p1")?.reviewDraftJson).isNotNull()

        vm.onEvent(ReviewEvent.AmountChanged(original))
        advanceTimeBy(PAST_DEBOUNCE)
        advanceUntilIdle()

        assertThat(pending.get("p1")?.reviewDraftJson).isNull()

        val reopened = viewModel()
        advanceUntilIdle()
        assertThat(reopened.state.value.amountText).isEqualTo(original)
    }

    // ── Resolution clears it ────────────────────────────────────────────────

    /**
     * Discarding throws the typing away with the candidate.
     *
     * Cleared by the same `UPDATE` that sets `DISCARDED`, so it cannot be
     * forgotten by a caller — including the notification action, which runs in a
     * `BroadcastReceiver` and never touches this ViewModel at all.
     */
    @Test
    fun discarding_clearsTheSavedDraft() = runTest(dispatcher) {
        pending.put(candidate())

        val vm = viewModel()
        advanceUntilIdle()
        vm.onEvent(ReviewEvent.NoteChanged("never mind"))
        advanceTimeBy(PAST_DEBOUNCE)
        advanceUntilIdle()
        assertThat(pending.get("p1")?.reviewDraftJson).isNotNull()

        vm.onEvent(ReviewEvent.Discard)
        advanceUntilIdle()

        assertThat(pending.get("p1")?.status).isEqualTo(PendingStatus.DISCARDED)
        assertThat(pending.get("p1")?.reviewDraftJson).isNull()
    }

    /**
     * **The race the SQL predicate exists for.**
     *
     * A debounce tick can still be in flight when the user taps Approve. If it
     * lands afterwards it would write typing back onto a resolved row — the
     * entry form's exact bug, where saving discarded the draft and the next tick
     * wrote the whole thing straight back. The fake binds `status = 'PENDING'`
     * like the real statement, so a late write finds no row to touch.
     */
    @Test
    fun aDebounceTickAfterDiscard_cannotResurrectTheDraft() = runTest(dispatcher) {
        pending.put(candidate())

        val vm = viewModel()
        advanceUntilIdle()

        // Type, then resolve before the window closes.
        vm.onEvent(ReviewEvent.NoteChanged("typed just before discarding"))
        vm.onEvent(ReviewEvent.Discard)
        advanceUntilIdle()
        // ...and let any pending tick fire into a row that is no longer PENDING.
        advanceTimeBy(PAST_DEBOUNCE)
        advanceUntilIdle()

        assertThat(pending.get("p1")?.status).isEqualTo(PendingStatus.DISCARDED)
        assertThat(pending.get("p1")?.reviewDraftJson).isNull()
    }

    // ── The payload itself ──────────────────────────────────────────────────

    /**
     * An unreadable payload opens the extraction, not an error.
     *
     * A draft written by a build that knew more fields is read by
     * `ignoreUnknownKeys`; one that is not JSON at all degrades to null and the
     * screen behaves exactly as it did at v7. Both are better than a screen that
     * cannot open a candidate the user can see in their Inbox.
     */
    @Test
    fun anUnreadableDraft_fallsBackToTheExtraction() = runTest(dispatcher) {
        pending.put(candidate().copy(reviewDraftJson = "{ this is not the payload }"))

        val vm = viewModel()
        advanceUntilIdle()

        assertThat(vm.state.value.missing).isFalse()
        assertThat(vm.state.value.amountText).isEqualTo("69.00")
        assertThat(vm.state.value.noteText).isEmpty()
    }
}
