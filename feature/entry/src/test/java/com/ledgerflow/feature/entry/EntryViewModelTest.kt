package com.ledgerflow.feature.entry

import com.google.common.truth.Truth.assertThat
import com.ledgerflow.core.common.id.Uuid7Generator
import com.ledgerflow.core.common.time.Clock
import com.ledgerflow.core.domain.ledger.LedgerError
import com.ledgerflow.core.domain.ledger.LedgerResult
import com.ledgerflow.core.domain.usecase.ApproveTransactionUseCase
import com.ledgerflow.core.model.Category
import com.ledgerflow.core.model.CategoryTree
import com.ledgerflow.core.model.EntrySource
import com.ledgerflow.core.model.LedgerType
import com.ledgerflow.core.model.Money
import com.ledgerflow.core.testing.ledger.FakeDraftRepository
import com.ledgerflow.core.testing.ledger.FakeLedgerRepository
import com.ledgerflow.core.testing.ledger.entryCombo
import com.ledgerflow.core.testing.taxonomy.FakeCategoryRepository
import com.ledgerflow.core.testing.taxonomy.FakeMerchantRepository
import com.ledgerflow.core.testing.taxonomy.FakePaymentMethodRepository
import java.security.SecureRandom
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test

/**
 * The manual entry form (SPEC.md §5.4) and its draft persistence (BUG6).
 *
 * `state` is a `stateIn(WhileSubscribed)` over a `combine`, so it emits nothing
 * until something collects it. Every test here starts a collector and settles
 * the scheduler before asserting; reading `state.value` without one returns the
 * seed and the assertions pass vacuously.
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class EntryViewModelTest {

    private val dispatcher = StandardTestDispatcher()

    private val ledger = FakeLedgerRepository()
    private val drafts = FakeDraftRepository()
    private val categories = FakeCategoryRepository()
    private val merchants = FakeMerchantRepository()
    private val paymentMethods = FakePaymentMethodRepository()

    private val groceries = category("cat-groceries", "Groceries")
    private val vegetables = category("cat-veg", "Vegetables", parentId = "cat-groceries")
    private val salary = category("cat-salary", "Salary", ledger = LedgerType.CREDIT)

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        categories.trees[LedgerType.DEBIT] = listOf(CategoryTree(groceries, listOf(vegetables)))
        categories.trees[LedgerType.CREDIT] = listOf(CategoryTree(salary, emptyList()))
    }

    @After
    fun tearDown() = Dispatchers.resetMain()

    // ── The typed amount becomes a Long, exactly (Law 3) ────────────────────

    /** The whole point of ADR-0012: type 125, get ₹125, not ₹1.25. */
    @Test
    fun typedWholeNumber_isMajorUnits() = runTest(dispatcher) {
        val subject = collected()

        subject.type("125")
        advanceUntilIdle()

        assertThat(subject.state.value.amountMinor).isEqualTo(125_00L)
    }

    @Test
    fun typedDecimal_isExact() = runTest(dispatcher) {
        val subject = collected()

        subject.type("8415.79")
        advanceUntilIdle()

        // Via a Double this is 841578.9999999999, which floors one paise short.
        assertThat(subject.state.value.amountMinor).isEqualTo(841_579L)
    }

    /**
     * The text is never rewritten by the ViewModel. Echoing back a reformatted
     * value would move the caret out from under the user's thumb on every
     * keystroke, which is how a money field becomes unusable.
     */
    @Test
    fun theFieldKeepsExactlyWhatWasTyped() = runTest(dispatcher) {
        val subject = collected()

        subject.type("12.")
        advanceUntilIdle()

        assertThat(subject.state.value.amountText).isEqualTo("12.")
        assertThat(subject.state.value.amountMinor).isEqualTo(12_00L)
    }

    @Test
    fun clearingTheField_returnsToZero() = runTest(dispatcher) {
        val subject = collected()
        subject.type("125")
        advanceUntilIdle()

        subject.type("")
        advanceUntilIdle()

        assertThat(subject.state.value.amountMinor).isEqualTo(0L)
        assertThat(subject.state.value.canSave).isFalse()
    }

    // ── Draft persistence (BUG6) ────────────────────────────────────────────

    /**
     * The debounce is a coalescing window, not a delay before the state is
     * real: five keystrokes inside 300 ms are one write, and the write happens.
     */
    @Test
    fun draft_isWrittenOnceAfterABurstOfKeystrokes() = runTest(dispatcher) {
        val subject = collected()

        subject.type("125")
        advanceTimeBy(DEBOUNCE_MS + 1)
        advanceUntilIdle()

        assertThat(drafts.saves).hasSize(1)
        assertThat(drafts.saves.single()).contains("\"amountMinor\":12500")
    }

    @Test
    fun draft_isNotWrittenForAFormNobodyTouched() = runTest(dispatcher) {
        collected()
        advanceTimeBy(DEBOUNCE_MS * 4)
        advanceUntilIdle()

        // Otherwise the next launch offers to resume an entry nobody started.
        assertThat(drafts.saves).isEmpty()
    }

    @Test
    fun draft_capturesEveryFieldOfTheForm() = runTest(dispatcher) {
        val subject = collected()

        subject.type("0.99")
        subject.onEvent(EntryEvent.PickerOpened(EntryPicker.Category))
        subject.onEvent(EntryEvent.PickerItemSelected(groceries.id))
        subject.onEvent(EntryEvent.NoteChanged("lunch"))
        advanceTimeBy(DEBOUNCE_MS + 1)
        advanceUntilIdle()

        val payload = drafts.saves.last()
        assertThat(payload).contains("\"amountMinor\":99")
        assertThat(payload).contains("\"categoryId\":\"cat-groceries\"")
        assertThat(payload).contains("\"note\":\"lunch\"")
    }

    /** The draft is the form, so the ledger is stored as the slot, not in the payload. */
    @Test
    fun draft_payloadNeverCarriesTheLedger() = runTest(dispatcher) {
        val subject = collected()

        subject.type("0.01")
        advanceTimeBy(DEBOUNCE_MS + 1)
        advanceUntilIdle()

        assertThat(drafts.saves.single()).doesNotContain("ledger")
    }

    @Test
    fun openingTheForm_resumesAnExistingDraftFieldForField() = runTest(dispatcher) {
        drafts.seed(
            "draft-a",
            LedgerType.DEBIT,
            """{"amountMinor":4250,"categoryId":"cat-groceries","note":"half typed"}""",
        )
        val subject = collected()
        advanceUntilIdle()

        // ADR-0013: the form opens empty and the stack offers the draft.
        assertThat(subject.state.value.amountMinor).isEqualTo(0L)
        assertThat(subject.state.value.unsaved.map { it.id }).containsExactly("draft-a")

        subject.onEvent(EntryEvent.DraftOpened("draft-a"))
        advanceUntilIdle()

        val state = subject.state.value
        assertThat(state.amountMinor).isEqualTo(4250L)
        assertThat(state.categoryId).isEqualTo("cat-groceries")
        assertThat(state.note).isEqualTo("half typed")
        assertThat(state.resumedFromDraft).isTrue()
    }

    /**
     * §6.1.2: a payload this build cannot read is never deserialized, and the
     * row is left alone. The form starts empty rather than showing fields it
     * guessed at.
     */
    @Test
    fun openingTheForm_ignoresADraftFromANewerBuild() = runTest(dispatcher) {
        drafts.seed(
            "draft-future",
            LedgerType.DEBIT,
            """{"amountMinor":4250}""",
            payloadVersion = EntryDraftCodec.VERSION + 1,
        )

        val subject = collected()
        advanceUntilIdle()

        // Not offered, not deserialized -- and the row is still on disk.
        assertThat(subject.state.value.unsaved).isEmpty()
        assertThat(drafts.find("draft-future")).isNotNull()
        assertThat(subject.state.value.amountMinor).isEqualTo(0L)
    }

    /**
     * The race the instrumented BUG6 test found.
     *
     * Reading the slot is asynchronous, so there is a window between the form
     * opening and the draft arriving. A restore that landed on top of the first
     * keystrokes would be BUG6 committed by its own countermeasure — the user
     * watching what they just typed be replaced by yesterday's form.
     */
    @Test
    fun aDraftArrivingLate_neverOverwritesWhatTheUserAlreadyTyped() = runTest(dispatcher) {
        drafts.seed("draft-a", LedgerType.DEBIT, """{"amountMinor":4250,"note":"yesterday"}""")

        val subject = viewModel()
        val collector = CoroutineScope(dispatcher).launch { subject.state.collect {} }
        // Deliberately before advanceUntilIdle: the restore has not landed yet.
        subject.type("7")
        advanceUntilIdle()

        assertThat(subject.state.value.amountMinor).isEqualTo(7_00L)
        assertThat(subject.state.value.note).isEmpty()
        collector.cancel()
    }

    @Test
    fun startFresh_discardsTheDraftAndEmptiesTheForm() = runTest(dispatcher) {
        drafts.seed("draft-a", LedgerType.DEBIT, """{"amountMinor":4250}""")
        val subject = collected()
        advanceUntilIdle()
        subject.onEvent(EntryEvent.DraftOpened("draft-a"))
        advanceUntilIdle()

        subject.onEvent(EntryEvent.DiscardRequested)
        advanceUntilIdle()
        assertThat(subject.state.value.confirmingDiscard).isTrue()

        subject.onEvent(EntryEvent.DiscardConfirmed)
        advanceUntilIdle()

        assertThat(drafts.discarded).contains("draft-a")
        assertThat(subject.state.value.amountMinor).isEqualTo(0L)
    }

    // ── Two books, two forms (Law 2, D-06) ──────────────────────────────────

    @Test
    fun switchingLedger_flushesTheOutgoingFormImmediately() = runTest(dispatcher) {
        val subject = collected()

        subject.type("0.75")
        // Deliberately *inside* the debounce window: a tap that looks like
        // navigation must not throw away 300 ms of typing.
        subject.onEvent(EntryEvent.LedgerSelected(LedgerType.CREDIT))
        advanceUntilIdle()

        assertThat(drafts.saves.last()).contains("\"amountMinor\":75")
    }

    @Test
    fun switchingLedger_showsTheOtherBooksStack() = runTest(dispatcher) {
        drafts.seed("draft-credit", LedgerType.CREDIT, """{"amountMinor":900000}""")
        val subject = collected()
        advanceUntilIdle()

        subject.onEvent(EntryEvent.LedgerSelected(LedgerType.CREDIT))
        advanceUntilIdle()

        // A fresh form in the other book, with that book's stack beside it.
        assertThat(subject.state.value.ledger).isEqualTo(LedgerType.CREDIT)
        assertThat(subject.state.value.amountMinor).isEqualTo(0L)
        assertThat(subject.state.value.unsaved.map { it.id }).containsExactly("draft-credit")
    }

    @Test
    fun switchingLedger_showsTheOtherBooksCategories() = runTest(dispatcher) {
        val subject = collected()
        advanceUntilIdle()
        assertThat(subject.state.value.tree.single().parent.name).isEqualTo("Groceries")

        subject.onEvent(EntryEvent.LedgerSelected(LedgerType.CREDIT))
        advanceUntilIdle()

        assertThat(subject.state.value.tree.single().parent.name).isEqualTo("Salary")
    }

    // ── Assignment ──────────────────────────────────────────────────────────

    /**
     * §6.1.1's invariant, enforced at the tap rather than discovered at Save.
     * A stale subcategory under a new category is exactly the row the approval
     * refuses.
     */
    @Test
    fun changingCategory_clearsTheSubcategory() = runTest(dispatcher) {
        val subject = collected()
        subject.choose(EntryPicker.Category, groceries.id)
        subject.choose(EntryPicker.Subcategory(groceries.id), vegetables.id)
        advanceUntilIdle()
        assertThat(subject.state.value.subcategoryId).isEqualTo(vegetables.id)

        subject.choose(EntryPicker.Category, "cat-other")
        advanceUntilIdle()

        assertThat(subject.state.value.subcategoryId).isNull()
    }

    @Test
    fun pickerSelection_ofNullClearsTheAssignment() = runTest(dispatcher) {
        val subject = collected()
        subject.choose(EntryPicker.Category, groceries.id)
        advanceUntilIdle()

        subject.choose(EntryPicker.Category, null)
        advanceUntilIdle()

        assertThat(subject.state.value.categoryId).isNull()
    }

    /** §5.4's four taps: digits, chip, save. The chip fills the rest. */
    @Test
    fun comboChip_fillsTheWholeAssignmentInOneTap() = runTest(dispatcher) {
        ledger.emitCombos(
            LedgerType.DEBIT,
            listOf(
                entryCombo(
                    categoryId = groceries.id,
                    subcategoryId = vegetables.id,
                    paymentMethodId = "pm-1",
                    uses = 4,
                ),
            ),
        )
        val subject = collected()
        advanceUntilIdle()

        subject.onEvent(EntryEvent.ComboSelected(0))
        advanceUntilIdle()

        val state = subject.state.value
        assertThat(state.categoryId).isEqualTo(groceries.id)
        assertThat(state.subcategoryId).isEqualTo(vegetables.id)
        assertThat(state.paymentMethodId).isEqualTo("pm-1")
    }

    @Test
    fun comboChip_forAVanishedCategoryIsNotOffered() = runTest(dispatcher) {
        // Filling the form with an id the approval will refuse is worse than
        // the chip simply not being there.
        ledger.emitCombos(LedgerType.DEBIT, listOf(entryCombo(categoryId = "cat-deleted")))
        val subject = collected()
        advanceUntilIdle()

        assertThat(subject.state.value.combos).isEmpty()
    }

    // ── Line items ──────────────────────────────────────────────────────────

    @Test
    fun lineItems_reportTheUnallocatedRemainder() = runTest(dispatcher) {
        val subject = collected()
        subject.type("100")
        subject.onEvent(EntryEvent.LineItemAdded)
        advanceUntilIdle()

        val key = subject.state.value.lineItems.single().key
        subject.onEvent(EntryEvent.LineItemAmountChanged(key, "60"))
        advanceUntilIdle()

        assertThat(subject.state.value.unallocatedMinor).isEqualTo(40_00L)
    }

    @Test
    fun lineItems_removeTakesTheRightRow() = runTest(dispatcher) {
        val subject = collected()
        repeat(3) { subject.onEvent(EntryEvent.LineItemAdded) }
        advanceUntilIdle()

        val keys = subject.state.value.lineItems.map { it.key }
        subject.onEvent(EntryEvent.LineItemNameChanged(keys[2], "third"))
        subject.onEvent(EntryEvent.LineItemRemoved(keys[1]))
        advanceUntilIdle()

        val remaining = subject.state.value.lineItems
        assertThat(remaining.map { it.key }).containsExactly(keys[0], keys[2]).inOrder()
        assertThat(remaining.last().name).isEqualTo("third")
    }

    // ── Saving ──────────────────────────────────────────────────────────────

    @Test
    fun save_sendsManualProvenanceAndTheTypedAmount() = runTest(dispatcher) {
        val subject = collected()
        subject.type("125")
        subject.choose(EntryPicker.Category, groceries.id)
        advanceUntilIdle()

        subject.onEvent(EntryEvent.SaveRequested)
        advanceUntilIdle()

        val request = ledger.approved.single()
        assertThat(request.amount).isEqualTo(Money(125_00L))
        assertThat(request.ledger).isEqualTo(LedgerType.DEBIT)
        assertThat(request.assignment.categoryId).isEqualTo(groceries.id)
        // §5.4: manual entry does not route through pending_transaction.
        assertThat(request.origin.source).isEqualTo(EntrySource.MANUAL)
        assertThat(request.origin.refId).isNull()
    }

    @Test
    fun save_withoutAnAmountIsRefusedBeforeItReachesTheLedger() = runTest(dispatcher) {
        val subject = collected()
        advanceUntilIdle()

        subject.onEvent(EntryEvent.SaveRequested)
        advanceUntilIdle()

        assertThat(ledger.approved).isEmpty()
        assertThat(subject.state.value.canSave).isFalse()
    }

    @Test
    fun save_dropsEmptyLineItems() = runTest(dispatcher) {
        val subject = collected()
        subject.type("50")
        subject.onEvent(EntryEvent.LineItemAdded)
        advanceUntilIdle()

        subject.onEvent(EntryEvent.SaveRequested)
        advanceUntilIdle()

        // A row the user added and never filled in is not a line item.
        assertThat(ledger.approved.single().lineItems).isEmpty()
    }

    @Test
    fun save_clearsTheDraftOnlyAfterTheEntryIsCommitted() = runTest(dispatcher) {
        val subject = collected()
        subject.type("50")
        advanceUntilIdle()

        subject.onEvent(EntryEvent.SaveRequested)
        advanceUntilIdle()

        assertThat(drafts.discarded).hasSize(1)
        assertThat(subject.state.value.savedEntryId).isNotNull()
    }

    /**
     * Found on the device, not here: after saving, the next entry opened
     * pre-filled with the one just committed.
     *
     * `filter` runs before `debounce`, so the cleaned form never reaches the
     * window and cannot cancel a tick already pending in it — the discard
     * landed, and then a tick from the keystroke *before* the save wrote the
     * whole form straight back as a fresh draft.
     */
    @Test
    fun save_leavesNoDraftBehindEvenWithAWriteAlreadyPending() = runTest(dispatcher) {
        val subject = collected()
        subject.type("50")

        // Save inside the debounce window, so a write is genuinely in flight.
        subject.onEvent(EntryEvent.SaveRequested)
        advanceUntilIdle()

        assertThat(drafts.observe(LedgerType.DEBIT).first()).isEmpty()
    }

    @Test
    fun save_emptiesTheFormForTheNextEntry() = runTest(dispatcher) {
        val subject = collected()
        subject.type("50")
        subject.choose(EntryPicker.Category, groceries.id)
        advanceUntilIdle()

        subject.onEvent(EntryEvent.SaveRequested)
        advanceUntilIdle()

        assertThat(subject.state.value.amountMinor).isEqualTo(0L)
        assertThat(subject.state.value.categoryId).isNull()
        assertThat(subject.state.value.resumedFromDraft).isFalse()
    }

    @Test
    fun save_refused_keepsTheDraftAndExplainsWhy() = runTest(dispatcher) {
        ledger.approveResult = LedgerResult.Failure(
            LedgerError.CategoryNotInLedger(salary.id, LedgerType.DEBIT),
        )
        val subject = collected()
        subject.type("50")
        advanceUntilIdle()

        subject.onEvent(EntryEvent.SaveRequested)
        advanceUntilIdle()

        // Losing the form because the ledger said no would be BUG6 by another route.
        assertThat(drafts.discarded).isEmpty()
        assertThat(subject.state.value.savedEntryId).isNull()
        assertThat(subject.state.value.message)
            .isEqualTo("That category belongs to the other ledger.")
    }

    @Test
    fun baseCurrency_comesFromTheVaultRatherThanADefault() = runTest(dispatcher) {
        ledger.installBaseCurrency = "JPY"
        val subject = collected()
        advanceUntilIdle()

        assertThat(subject.state.value.currencyCode).isEqualTo("JPY")
    }

    // ── Harness ─────────────────────────────────────────────────────────────

    private fun viewModel() = EntryViewModel(
        approveTransaction = ApproveTransactionUseCase(ledger),
        drafts = drafts,
        ledgerRepository = ledger,
        categories = categories,
        merchants = merchants,
        paymentMethods = paymentMethods,
        clock = Clock { NOW },
        ids = Uuid7Generator(SecureRandom()),
    )

    /**
     * A ViewModel with a live collector.
     *
     * `stateIn(WhileSubscribed)` runs nothing until subscribed, so a test that
     * skipped this would assert against the seed state forever.
     */
    private fun TestScope.collected(): EntryViewModel {
        val subject = viewModel()
        collectors += CoroutineScope(dispatcher).launch { subject.state.collect {} }
        testScheduler.advanceUntilIdle()
        return subject
    }

    private val collectors = mutableListOf<Job>()

    @After
    fun stopCollectors() {
        collectors.forEach { it.cancel() }
        collectors.clear()
    }

    /** Types into the amount field, as the system keyboard would. */
    private fun EntryViewModel.type(text: String) =
        onEvent(EntryEvent.AmountChanged(text))

    private fun EntryViewModel.choose(picker: EntryPicker, id: String?) {
        onEvent(EntryEvent.PickerOpened(picker))
        onEvent(EntryEvent.PickerItemSelected(id))
    }

    private fun category(
        id: String,
        name: String,
        parentId: String? = null,
        ledger: LedgerType = LedgerType.DEBIT,
    ) = Category(
        id = id,
        parentId = parentId,
        ledger = ledger,
        name = name,
        icon = "",
        colorArgb = 0,
        sortOrder = 0,
        isSystem = false,
    )

    private companion object {
        private const val NOW = 1_755_540_000_000L
        private const val DEBOUNCE_MS = 300L
    }
}
