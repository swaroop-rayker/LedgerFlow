package com.ledgerflow.feature.inbox

import androidx.lifecycle.SavedStateHandle
import com.google.common.truth.Truth.assertThat
import com.ledgerflow.core.common.id.Uuid7Generator
import com.ledgerflow.core.domain.analytics.NoOpBudgetAlertTrigger
import com.ledgerflow.core.domain.inbox.PendingTransaction
import com.ledgerflow.core.domain.inbox.ReviewEditLine
import com.ledgerflow.core.domain.inbox.ReviewEdits
import com.ledgerflow.core.domain.ingest.ExtractedDirection
import com.ledgerflow.core.domain.ingest.ExtractedLineItem
import com.ledgerflow.core.domain.ingest.ExtractedTransaction
import com.ledgerflow.core.domain.usecase.ApprovePendingUseCase
import com.ledgerflow.core.domain.usecase.ApproveTransactionUseCase
import com.ledgerflow.core.domain.usecase.DiscardPendingUseCase
import com.ledgerflow.core.domain.usecase.ObserveCategoryTreeUseCase
import com.ledgerflow.core.model.EntrySource
import com.ledgerflow.core.model.LedgerType
import com.ledgerflow.core.model.LineItemKind
import com.ledgerflow.core.model.Merchant
import com.ledgerflow.core.model.Money
import com.ledgerflow.core.model.PendingStatus
import com.ledgerflow.core.model.Quantity
import com.ledgerflow.core.testing.inbox.FakePendingRepository
import com.ledgerflow.core.testing.ledger.FakeLedgerRepository
import com.ledgerflow.core.testing.taxonomy.FakeCategoryRepository
import com.ledgerflow.core.testing.taxonomy.FakeMerchantRepository
import com.ledgerflow.core.testing.taxonomy.FakePaymentMethodRepository
import java.security.SecureRandom
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test

/**
 * **Step 18: an OCR candidate arrives with its lines** (`docs/OCR-PIPELINE.md`).
 *
 * `ReviewUiState.lines` was fed only from `review_draft_json` — the user's own
 * typing — and nothing read `extracted_json`'s `lines` (ADR-0022). For an SMS
 * that was right; for a receipt it meant the whole of §5.3's extraction landed
 * in a column no screen looked at, and the review form opened empty.
 *
 * The failure is worse than cosmetic, which is why the test exists rather than
 * a visual check: with no lines on the screen there is no way to tell an
 * extractor that found nothing from a screen that dropped everything.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ReviewSeedsExtractedLinesTest {

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

    private fun candidate(
        source: EntrySource = EntrySource.OCR,
        lines: List<ExtractedLineItem> = emptyList(),
        edits: ReviewEdits? = null,
    ) = PendingTransaction(
        id = "p1",
        source = source,
        extracted = ExtractedTransaction(
            amount = Money(47_300L),
            direction = ExtractedDirection.DEBIT,
            merchantRaw = "SRI LAKSHMI STORES",
            confidence = 0.7,
            lines = lines,
        ),
        confidence = 0.7,
        status = PendingStatus.PENDING,
        needsManualFill = false,
        suppressedById = null,
        createdAt = 1_787_810_214_627L,
        reviewedAt = null,
        approvedEntryId = null,
        edits = edits,
    )

    private fun viewModel() = ReviewViewModel(
        savedStateHandle = SavedStateHandle(mapOf(ReviewViewModel.PENDING_ID_ARG to "p1")),
        approvePending = ApprovePendingUseCase(
            pending,
            merchants,
            ApproveTransactionUseCase(ledger, NoOpBudgetAlertTrigger),
        ),
        discardPending = DiscardPendingUseCase(pending),
        pendingRepository = pending,
        observeCategoryTree = ObserveCategoryTreeUseCase(categories),
        merchants = merchants,
        paymentMethods = paymentMethods,
        ledgerRepository = ledger,
        ids = Uuid7Generator(SecureRandom()),
    )

    @Test
    fun anExtractedBill_opensItemised_withEveryItemLine() = runTest(dispatcher) {
        pending.put(
            candidate(
                lines = listOf(
                    ExtractedLineItem(name = "TOMATO 1KG", total = Money(4_000L)),
                    ExtractedLineItem(name = "ATTA 5KG", total = Money(28_500L)),
                ),
            ),
        )

        val subject = viewModel()
        dispatcher.scheduler.advanceUntilIdle()

        val state = subject.state.value
        // Both, or the lines are held and never shown: `newLineItems` returns
        // nothing while `itemised` is false.
        assertThat(state.itemised).isTrue()
        assertThat(state.lines.map { it.name }).containsExactly("TOMATO 1KG", "ATTA 5KG").inOrder()
        assertThat(state.lines.map { it.amountMinor }).containsExactly(4_000L, 28_500L).inOrder()
    }

    /**
     * A `TAX` line committed as an `ITEM` is tax counted as a purchase.
     *
     * [ReviewLine] carries no kind and `newLineItems` builds every line as
     * `ITEM`, so the only honest place for a tax line today is the
     * `UNALLOCATED` remainder §5.4 already writes. It stays in
     * `extracted_json` either way.
     */
    @Test
    fun taxAndDiscountLines_doNotReachTheForm() = runTest(dispatcher) {
        pending.put(
            candidate(
                lines = listOf(
                    ExtractedLineItem(name = "TOMATO 1KG", total = Money(4_000L)),
                    ExtractedLineItem(
                        name = "CGST 2.5%",
                        kind = LineItemKind.TAX,
                        total = Money(300L),
                    ),
                    ExtractedLineItem(
                        name = "MEMBER OFF",
                        kind = LineItemKind.DISCOUNT,
                        total = Money(-500L),
                    ),
                ),
            ),
        )

        val subject = viewModel()
        dispatcher.scheduler.advanceUntilIdle()

        assertThat(subject.state.value.lines.map { it.name }).containsExactly("TOMATO 1KG")
    }

    /** The quantity survives when the editor's own arithmetic reproduces the total. */
    @Test
    fun aConsistentQuantity_isKept() = runTest(dispatcher) {
        pending.put(
            candidate(
                lines = listOf(
                    ExtractedLineItem(
                        name = "CURD 400G",
                        quantityMilli = 2 * Quantity.SCALE,
                        unitPrice = Money(3_500L),
                        total = Money(7_000L),
                    ),
                ),
            ),
        )

        val subject = viewModel()
        dispatcher.scheduler.advanceUntilIdle()

        val line = subject.state.value.lines.single()
        assertThat(line.quantityMilli).isEqualTo(2 * Quantity.SCALE)
        assertThat(line.unitPriceMinor).isEqualTo(3_500L)
        assertThat(line.amountMinor).isEqualTo(7_000L)
    }

    /**
     * When the receipt's own three numbers disagree, the money wins.
     *
     * The editor shows the total read-only as `unit price × quantity`, so a
     * form holding 2 × 4500 would display 9000 for a line the bill prints as
     * 9050 — a figure the user never saw, on the one value §5.3's
     * reconciliation weighs.
     */
    @Test
    fun anInconsistentQuantity_isDropped_andTheTotalIsKept() = runTest(dispatcher) {
        pending.put(
            candidate(
                lines = listOf(
                    ExtractedLineItem(
                        name = "OIL 1L",
                        quantityMilli = 2 * Quantity.SCALE,
                        unitPrice = Money(4_500L),
                        total = Money(9_050L),
                    ),
                ),
            ),
        )

        val subject = viewModel()
        dispatcher.scheduler.advanceUntilIdle()

        val line = subject.state.value.lines.single()
        assertThat(line.quantityMilli).isEqualTo(Quantity.SCALE)
        assertThat(line.amountMinor).isEqualTo(9_050L)
    }

    /** A bank message has no lines, and seeding must not make it look itemised. */
    @Test
    fun anSmsCandidate_staysSingleItem() = runTest(dispatcher) {
        pending.put(candidate(source = EntrySource.SMS))

        val subject = viewModel()
        dispatcher.scheduler.advanceUntilIdle()

        assertThat(subject.state.value.itemised).isFalse()
        assertThat(subject.state.value.lines).isEmpty()
    }

    /**
     * The extraction is the **baseline** (BUG16), lines included.
     *
     * If the seeded lines were not part of the baseline, the first debounce
     * tick after opening would read as an edit and write a draft for a screen
     * nobody typed on — and every OCR candidate in the Inbox would show as
     * edited the moment it was looked at.
     */
    @Test
    fun openingAnExtractedBill_writesNoDraft() = runTest(dispatcher) {
        pending.put(
            candidate(lines = listOf(ExtractedLineItem(name = "TOMATO 1KG", total = Money(4_000L)))),
        )

        val subject = viewModel()
        dispatcher.scheduler.advanceUntilIdle()

        assertThat(subject.state.value.lines).hasSize(1)
        assertThat(pending.get("p1")?.edits).isNull()
    }

    /** A correction the user typed outranks the reading it corrected. */
    @Test
    fun aSavedDraft_winsOverTheExtraction() = runTest(dispatcher) {
        pending.put(
            candidate(
                lines = listOf(ExtractedLineItem(name = "TOMAT0 1KG", total = Money(4_000L))),
                edits = ReviewEdits(
                    ledger = LedgerType.DEBIT,
                    amountText = "473.00",
                    amountMinor = 47_300L,
                    itemised = true,
                    lines = listOf(
                        ReviewEditLine(
                            key = "k1",
                            name = "TOMATO 1KG",
                            unitPriceText = "40.00",
                            unitPriceMinor = 4_000L,
                            quantityMilli = Quantity.SCALE,
                        ),
                    ),
                ),
            ),
        )

        val subject = viewModel()
        dispatcher.scheduler.advanceUntilIdle()

        assertThat(subject.state.value.lines.map { it.name }).containsExactly("TOMATO 1KG")
    }

    // ── Item 7b: a taught payee opens on its merchant ─────────────────────

    /**
     * The receipt read the legal seller; the user once filed it as Zepto; the
     * next one opens on Zepto — and that is part of what was read, so opening
     * the review writes no draft (BUG16's baseline).
     */
    @Test
    fun aTaughtPayee_opensOnItsMerchant_withoutRecordingADraft() = runTest(dispatcher) {
        merchants.merchants.value = listOf(Merchant("zepto", "Zepto", "zepto", null, null))
        merchants.aliases["sri lakshmi stores"] = "zepto"
        pending.put(candidate())

        val subject = viewModel()
        dispatcher.scheduler.advanceUntilIdle()

        assertThat(subject.state.value.merchantId).isEqualTo("zepto")
        assertThat(pending.get("p1")?.edits).isNull()
    }
}
