package com.ledgerflow.feature.ledger

import com.google.common.truth.Truth.assertThat
import com.ledgerflow.core.common.time.Clock
import com.ledgerflow.core.domain.inbox.PendingTransaction
import com.ledgerflow.core.domain.ingest.ExtractedDirection
import com.ledgerflow.core.domain.ingest.ExtractedTransaction
import com.ledgerflow.core.domain.ledger.DraftSummaryFields
import com.ledgerflow.core.domain.usecase.DeleteEntryUseCase
import com.ledgerflow.core.domain.usecase.ObservePendingUseCase
import com.ledgerflow.core.model.EntrySource
import com.ledgerflow.core.model.LedgerType
import com.ledgerflow.core.model.Money
import com.ledgerflow.core.model.PendingStatus
import com.ledgerflow.core.testing.inbox.FakePendingRepository
import com.ledgerflow.core.testing.ledger.FakeDraftRepository
import com.ledgerflow.core.testing.ledger.FakeLedgerRepository
import java.time.Instant
import java.time.ZoneId
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
 * **One "Unsaved" section holding two kinds of row.** CHANGE#1.
 *
 * Drafts and captured candidates were two bands; the owner asked for one. What
 * did **not** merge is the row type, and that is what these tests are about: a
 * [UnsavedRow.Draft] opens the entry form and its discard is final, a
 * [UnsavedRow.Candidate] opens the review screen and its discard is reversible
 * for 30 days (§5.1). Flattening them into one shape would make two identical
 * rows behave differently, which is the outcome the marker on each row exists
 * to prevent — so the merge is in the *list*, never in the model.
 *
 * The other half is the order. Sorted by when each thing **happened** rather
 * than by when it was written, so the section reads chronologically like the
 * rest of the Ledger: a candidate captured this morning for a payment made
 * three days ago belongs where three days ago is.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class UnsavedSectionMergeTest {

    private companion object {
        const val FIXED_NOW = 1_787_000_000_000L
        const val DAY = 86_400_000L
        const val HOUR = 3_600_000L
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

    private fun candidate(id: String, occurredAt: Long?, createdAt: Long = FIXED_NOW) =
        PendingTransaction(
            id = id,
            source = EntrySource.SMS,
            extracted = ExtractedTransaction(
                amount = Money(6_900L),
                direction = ExtractedDirection.DEBIT,
                merchantRaw = "SWIGGY",
                occurredAt = occurredAt,
                confidence = 0.9,
            ),
            confidence = 0.9,
            status = PendingStatus.PENDING,
            needsManualFill = false,
            suppressedById = null,
            createdAt = createdAt,
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

    private fun LedgerViewModel.unsaved() = state.value.unsaved

    /**
     * Local midnight on [FIXED_NOW]'s day — what `DateText` produces for a
     * message that states a date and no clock.
     *
     * Computed the way the parser does rather than by rounding the epoch, so
     * this is midnight in the *device's* zone and the test does not quietly
     * pass or fail on the machine's offset.
     */
    private fun midnight(): Long =
        Instant.ofEpochMilli(FIXED_NOW)
            .atZone(ZoneId.systemDefault())
            .toLocalDate()
            .atStartOfDay(ZoneId.systemDefault())
            .toInstant()
            .toEpochMilli()

    // ── One section ─────────────────────────────────────────────────────────

    @Test
    fun draftsAndCandidates_shareOneList() = runTest(dispatcher) {
        drafts.seed(
            "draft-1",
            LedgerType.DEBIT,
            "{}",
            summary = DraftSummaryFields(24_050L, occurredAt = FIXED_NOW),
        )
        pending.put(candidate("cand-1", occurredAt = FIXED_NOW))

        val viewModel = viewModel()
        viewModel.onEvent(LedgerEvent.LedgerSelected(LedgerType.DEBIT))
        dispatcher.scheduler.advanceUntilIdle()

        assertThat(viewModel.unsaved().map { it.key })
            .containsExactly("draft-draft-1", "candidate-cand-1")
    }

    /**
     * The two kinds stay distinguishable after the merge.
     *
     * This is the assertion the whole sealed type exists for: they are in one
     * list, and the list still knows which is which. A flattened row model
     * would pass "one section" and lose exactly this.
     */
    @Test
    fun eachRowStillKnowsWhichKindItIs() = runTest(dispatcher) {
        drafts.seed(
            "draft-1",
            LedgerType.DEBIT,
            "{}",
            summary = DraftSummaryFields(24_050L, occurredAt = FIXED_NOW),
        )
        pending.put(candidate("cand-1", occurredAt = FIXED_NOW))

        val viewModel = viewModel()
        viewModel.onEvent(LedgerEvent.LedgerSelected(LedgerType.DEBIT))
        dispatcher.scheduler.advanceUntilIdle()

        val rows = viewModel.unsaved()
        assertThat(rows.filterIsInstance<UnsavedRow.Draft>()).hasSize(1)
        assertThat(rows.filterIsInstance<UnsavedRow.Candidate>()).hasSize(1)
    }

    /** Keys are unique across kinds, so a shared `LazyColumn` cannot collide. */
    @Test
    fun keysAreDistinctEvenWhenTheIdsAreIdentical() = runTest(dispatcher) {
        drafts.seed(
            "same",
            LedgerType.DEBIT,
            "{}",
            summary = DraftSummaryFields(1_000L, occurredAt = FIXED_NOW),
        )
        pending.put(candidate("same", occurredAt = FIXED_NOW))

        val viewModel = viewModel()
        viewModel.onEvent(LedgerEvent.LedgerSelected(LedgerType.DEBIT))
        dispatcher.scheduler.advanceUntilIdle()

        val keys = viewModel.unsaved().map { it.key }
        assertThat(keys).hasSize(2)
        assertThat(keys.toSet()).hasSize(2)
    }

    // ── One order ───────────────────────────────────────────────────────────

    /**
     * Newest first, across both kinds.
     *
     * Interleaved rather than grouped: the owner asked for one section, and a
     * section that silently put all of one kind above all of the other would be
     * the old two bands wearing one heading.
     */
    @Test
    fun rowsAreOrderedByWhenTheyHappened_notByKind() = runTest(dispatcher) {
        drafts.seed(
            "old-draft",
            LedgerType.DEBIT,
            "{}",
            summary = DraftSummaryFields(1_000L, occurredAt = FIXED_NOW - 3 * DAY),
        )
        drafts.seed(
            "new-draft",
            LedgerType.DEBIT,
            "{}",
            summary = DraftSummaryFields(2_000L, occurredAt = FIXED_NOW),
        )
        pending.put(candidate("mid-candidate", occurredAt = FIXED_NOW - DAY))

        val viewModel = viewModel()
        viewModel.onEvent(LedgerEvent.LedgerSelected(LedgerType.DEBIT))
        dispatcher.scheduler.advanceUntilIdle()

        assertThat(viewModel.unsaved().map { it.key })
            .containsExactly("draft-new-draft", "candidate-mid-candidate", "draft-old-draft")
            .inOrder()
    }

    /**
     * A candidate sorts by the payment's day, not by when it was captured.
     *
     * §16 Q14's re-triage re-admits messages that were rejected earlier, so a
     * candidate created today can describe a payment from days ago. Sorting by
     * `createdAt` would drop it at the top of the list above things that
     * genuinely happened later.
     */
    @Test
    fun aLateCapturedCandidate_sortsByThePaymentsDate() = runTest(dispatcher) {
        drafts.seed(
            "yesterday-draft",
            LedgerType.DEBIT,
            "{}",
            summary = DraftSummaryFields(1_000L, occurredAt = FIXED_NOW - DAY),
        )
        // Captured just now, but the message says it happened three days ago.
        pending.put(candidate("old-payment", occurredAt = FIXED_NOW - 3 * DAY))

        val viewModel = viewModel()
        viewModel.onEvent(LedgerEvent.LedgerSelected(LedgerType.DEBIT))
        dispatcher.scheduler.advanceUntilIdle()

        assertThat(viewModel.unsaved().map { it.key })
            .containsExactly("draft-yesterday-draft", "candidate-old-payment")
            .inOrder()
    }

    /**
     * A candidate whose message stated no date falls back to capture time.
     *
     * The same rule `ApprovePendingUseCase` applies: a message that named no
     * day still happened, and when we received it is a fact about something
     * real rather than a guess.
     */
    @Test
    fun aCandidateWithNoStatedDate_sortsByWhenItWasCaptured() = runTest(dispatcher) {
        drafts.seed(
            "old-draft",
            LedgerType.DEBIT,
            "{}",
            summary = DraftSummaryFields(1_000L, occurredAt = FIXED_NOW - 3 * DAY),
        )
        pending.put(candidate("dateless", occurredAt = null, createdAt = FIXED_NOW))

        val viewModel = viewModel()
        viewModel.onEvent(LedgerEvent.LedgerSelected(LedgerType.DEBIT))
        dispatcher.scheduler.advanceUntilIdle()

        assertThat(viewModel.unsaved().map { it.key })
            .containsExactly("candidate-dateless", "draft-old-draft")
            .inOrder()
    }

    /**
     * **The sort key is the value the row displays.** Found on the device.
     *
     * Every bank SMS states a date and no clock, so `occurredAt` is midnight
     * and the row *displays* the capture time instead
     * (`TimeStamp.ofCapture`). Sorting on the raw `occurredAt` therefore put
     * both real candidates at 12:00 am, and a draft from 2:49 pm sorted above
     * a candidate whose own row read 4:24 pm — a list that simply looked
     * unsorted.
     *
     * Neither half was wrong alone, which is why no existing test saw it: the
     * display was right, the sort was right about the value it was given, and
     * only the two together were wrong. This asserts they agree.
     */
    @Test
    fun aMidnightCandidate_sortsByItsCaptureTime_notByMidnight() = runTest(dispatcher) {
        val midnight = midnight()
        // A draft in the early afternoon...
        drafts.seed(
            "afternoon-draft",
            LedgerType.DEBIT,
            "{}",
            summary = DraftSummaryFields(1_000L, occurredAt = midnight + 14 * HOUR),
        )
        // ...and an SMS candidate for the same day, captured later. Its stored
        // occurredAt is midnight, so a raw sort would drop it to the bottom.
        pending.put(
            candidate("evening-sms", occurredAt = midnight, createdAt = midnight + 16 * HOUR),
        )

        val viewModel = viewModel()
        viewModel.onEvent(LedgerEvent.LedgerSelected(LedgerType.DEBIT))
        dispatcher.scheduler.advanceUntilIdle()

        assertThat(viewModel.unsaved().map { it.key })
            .containsExactly("candidate-evening-sms", "draft-afternoon-draft")
            .inOrder()
    }

    /** And the key really is the blended value, not a coincidence of ordering. */
    @Test
    fun aMidnightCandidatesSortKey_isItsCaptureTime() = runTest(dispatcher) {
        val midnight = midnight()
        val captured = midnight + 16 * HOUR
        pending.put(candidate("sms", occurredAt = midnight, createdAt = captured))

        val viewModel = viewModel()
        viewModel.onEvent(LedgerEvent.LedgerSelected(LedgerType.DEBIT))
        dispatcher.scheduler.advanceUntilIdle()

        assertThat(viewModel.unsaved().single().happenedAt).isEqualTo(captured)
    }

    @Test
    fun withNothingUnsaved_theSectionIsEmpty() = runTest(dispatcher) {
        val viewModel = viewModel()
        viewModel.onEvent(LedgerEvent.LedgerSelected(LedgerType.DEBIT))
        dispatcher.scheduler.advanceUntilIdle()

        assertThat(viewModel.unsaved()).isEmpty()
    }
}
