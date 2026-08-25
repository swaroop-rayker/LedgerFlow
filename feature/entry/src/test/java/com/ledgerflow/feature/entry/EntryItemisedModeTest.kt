package com.ledgerflow.feature.entry

import androidx.lifecycle.SavedStateHandle
import com.google.common.truth.Truth.assertThat
import com.ledgerflow.core.common.id.Uuid7Generator
import com.ledgerflow.core.common.time.Clock
import com.ledgerflow.core.domain.usecase.ApproveTransactionUseCase
import com.ledgerflow.core.model.Category
import com.ledgerflow.core.model.CategoryTree
import com.ledgerflow.core.model.LedgerType
import com.ledgerflow.core.model.Money
import com.ledgerflow.core.testing.ledger.FakeDraftRepository
import com.ledgerflow.core.testing.ledger.FakeLedgerRepository
import com.ledgerflow.core.testing.taxonomy.FakeCategoryRepository
import com.ledgerflow.core.testing.taxonomy.FakeMerchantRepository
import com.ledgerflow.core.testing.taxonomy.FakePaymentMethodRepository
import java.security.SecureRandom
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
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
 * Itemised entries in the entry form (SPEC.md §5.4, ADR-0018).
 *
 * Split out of `EntryViewModelTest` when that class crossed detekt's size
 * ceiling. The cut is by subject rather than by line count: everything here is
 * about the one decision ADR-0018 introduced -- whether this entry files at
 * entry grain or line grain -- and none of it is about the amount field, the
 * draft debounce, or the save path that class exists to cover.
 *
 * `state` is a `stateIn(WhileSubscribed)`, so nothing flows until something
 * collects it; every test starts a collector and settles the scheduler before
 * asserting.
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class EntryItemisedModeTest {

    private val dispatcher = StandardTestDispatcher()

    private val ledger = FakeLedgerRepository()
    private val drafts = FakeDraftRepository()
    private val categories = FakeCategoryRepository()
    private val merchants = FakeMerchantRepository()
    private val paymentMethods = FakePaymentMethodRepository()

    private val groceries = Category(
        id = "cat-groceries",
        parentId = null,
        ledger = LedgerType.DEBIT,
        name = "Groceries",
        icon = "",
        colorArgb = 0,
        sortOrder = 0,
        isSystem = false,
    )
    private val vegetables = groceries.copy(
        id = "cat-veg",
        parentId = "cat-groceries",
        name = "Vegetables",
    )

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        categories.trees[LedgerType.DEBIT] = listOf(CategoryTree(groceries, listOf(vegetables)))
    }

    @After
    fun tearDown() = Dispatchers.resetMain()

    // ── Itemised entries (ADR-0018) ──────────────────────────────

    /**
     * The category is not discarded by the switch, it moves down.
     *
     * The user has already answered "what is this"; the answer is still true of
     * at least part of the bill, and making them pick it again for line one
     * would be the app forgetting something it was just told.
     */
    @Test
    fun itemising_movesTheEntryCategoryOntoTheFirstLine() = runTest(dispatcher) {
        val subject = collected()
        subject.type("1000")
        subject.choose(EntryPicker.Category(), groceries.id)
        subject.choose(EntryPicker.Subcategory(groceries.id), vegetables.id)
        advanceUntilIdle()

        subject.itemise()
        advanceUntilIdle()

        val line = subject.state.value.lineItems.single()
        assertThat(line.categoryId).isEqualTo(groceries.id)
        assertThat(line.subcategoryId).isEqualTo(vegetables.id)

        // ADR-0018: the entry itself now files nothing.
        assertThat(subject.state.value.categoryId).isNull()
        assertThat(subject.state.value.subcategoryId).isNull()
    }

    /** The scenario the feature exists for: one payment, two categories. */
    @Test
    fun save_whenItemised_sendsTheLinesAndNoEntryCategory() = runTest(dispatcher) {
        val subject = collected()
        subject.type("1000")
        subject.itemise()
        advanceUntilIdle()

        val first = subject.state.value.lineItems.single().key
        subject.onEvent(EntryEvent.LineItemNameChanged(first, "Weekly shop"))
        subject.onEvent(EntryEvent.LineItemUnitPriceChanged(first, "600"))
        subject.choose(EntryPicker.Category(lineKey = first), groceries.id)
        subject.onEvent(EntryEvent.LineItemAdded)
        advanceUntilIdle()

        val second = subject.state.value.lineItems.last().key
        subject.onEvent(EntryEvent.LineItemNameChanged(second, "Kettle"))
        subject.onEvent(EntryEvent.LineItemUnitPriceChanged(second, "400"))
        advanceUntilIdle()

        subject.onEvent(EntryEvent.SaveRequested)
        advanceUntilIdle()

        val request = ledger.approved.single()
        assertThat(request.assignment.categoryId).isNull()
        assertThat(request.lineItems.map { it.name })
            .containsExactly("Weekly shop", "Kettle").inOrder()
        assertThat(request.lineItems.map { it.total })
            .containsExactly(Money(600_00L), Money(400_00L)).inOrder()
    }

    /**
     * A second line inherits the first's filing.
     *
     * A twelve-line grocery bill is mostly one category with two exceptions, so
     * this turns twelve category picks into two.
     */
    @Test
    fun addingALine_inheritsThePreviousLinesCategory() = runTest(dispatcher) {
        val subject = collected()
        subject.type("100")
        subject.itemise()
        advanceUntilIdle()

        val first = subject.state.value.lineItems.single().key
        subject.choose(EntryPicker.Category(lineKey = first), groceries.id)
        subject.onEvent(EntryEvent.LineItemAdded)
        advanceUntilIdle()

        assertThat(subject.state.value.lineItems.last().categoryId).isEqualTo(groceries.id)
    }

    /** `unit price x quantity`, in integers, and never typed directly (Law 3). */
    @Test
    fun lineTotal_isUnitPriceTimesQuantity() = runTest(dispatcher) {
        val subject = collected()
        subject.type("1000")
        subject.itemise()
        advanceUntilIdle()

        val key = subject.state.value.lineItems.single().key
        subject.onEvent(EntryEvent.LineItemUnitPriceChanged(key, "120"))
        subject.onEvent(EntryEvent.LineItemQuantityChanged(key, "2"))
        advanceUntilIdle()

        assertThat(subject.state.value.lineItems.single().amountMinor).isEqualTo(240_00L)
        assertThat(subject.state.value.unallocatedMinor).isEqualTo(760_00L)
    }

    /** Half a kilo. The case the milli scale exists for. */
    @Test
    fun lineTotal_handlesAFractionalQuantity() = runTest(dispatcher) {
        val subject = collected()
        subject.type("100")
        subject.itemise()
        advanceUntilIdle()

        val key = subject.state.value.lineItems.single().key
        subject.onEvent(EntryEvent.LineItemUnitPriceChanged(key, "99.99"))
        subject.onEvent(EntryEvent.LineItemQuantityChanged(key, "0.5"))
        advanceUntilIdle()

        assertThat(subject.state.value.lineItems.single().amountMinor).isEqualTo(50_00L)
    }

    /** Leaving itemised mode destroys typing, so it asks first (BUG6's reasoning). */
    @Test
    fun leavingItemised_withLinesEntered_asksBeforeDiscarding() = runTest(dispatcher) {
        val subject = collected()
        subject.type("100")
        subject.itemise()
        advanceUntilIdle()

        val key = subject.state.value.lineItems.single().key
        subject.onEvent(EntryEvent.LineItemNameChanged(key, "Rice"))
        subject.onEvent(EntryEvent.ModeSelected(itemised = false))
        advanceUntilIdle()

        assertThat(subject.state.value.confirmingSingleItem).isTrue()
        // Nothing has gone yet.
        assertThat(subject.state.value.itemised).isTrue()
        assertThat(subject.state.value.lineItems).hasSize(1)

        subject.onEvent(EntryEvent.SingleItemConfirmed)
        advanceUntilIdle()

        assertThat(subject.state.value.itemised).isFalse()
        assertThat(subject.state.value.lineItems).isEmpty()
    }

    /**
     * An untouched editor goes without a question.
     *
     * A confirmation over nothing is how people learn to dismiss dialogs unread,
     * which is what makes the one that matters ineffective.
     */
    @Test
    fun leavingItemised_withNothingEntered_doesNotAsk() = runTest(dispatcher) {
        val subject = collected()
        subject.type("100")
        subject.itemise()
        advanceUntilIdle()

        subject.onEvent(EntryEvent.ModeSelected(itemised = false))
        advanceUntilIdle()

        assertThat(subject.state.value.confirmingSingleItem).isFalse()
        assertThat(subject.state.value.itemised).isFalse()
    }

    /** Single-item entries send no lines, whatever was typed before the switch. */
    @Test
    fun save_whenSingle_sendsNoLineItems() = runTest(dispatcher) {
        val subject = collected()
        subject.type("100")
        subject.itemise()
        advanceUntilIdle()

        val key = subject.state.value.lineItems.single().key
        subject.onEvent(EntryEvent.LineItemNameChanged(key, "Rice"))
        subject.onEvent(EntryEvent.ModeSelected(itemised = false))
        subject.onEvent(EntryEvent.SingleItemConfirmed)
        subject.choose(EntryPicker.Category(), groceries.id)
        advanceUntilIdle()

        subject.onEvent(EntryEvent.SaveRequested)
        advanceUntilIdle()

        val request = ledger.approved.single()
        assertThat(request.lineItems).isEmpty()
        assertThat(request.assignment.categoryId).isEqualTo(groceries.id)
    }

    /** Changing a line's category clears its subcategory -- §6.1.1, one level down. */
    @Test
    fun changingALinesCategory_clearsItsSubcategory() = runTest(dispatcher) {
        val subject = collected()
        subject.type("100")
        subject.itemise()
        advanceUntilIdle()

        val key = subject.state.value.lineItems.single().key
        subject.choose(EntryPicker.Category(lineKey = key), groceries.id)
        subject.choose(EntryPicker.Subcategory(groceries.id, lineKey = key), vegetables.id)
        advanceUntilIdle()
        assertThat(subject.state.value.lineItems.single().subcategoryId).isEqualTo(vegetables.id)

        subject.choose(EntryPicker.Category(lineKey = key), "cat-other")
        advanceUntilIdle()

        assertThat(subject.state.value.lineItems.single().subcategoryId).isNull()
    }

    /** Only one line is open at a time, and removing it leaves none open. */
    @Test
    fun expansion_isSingleAndSurvivesRemoval() = runTest(dispatcher) {
        val subject = collected()
        subject.type("100")
        subject.itemise()
        subject.onEvent(EntryEvent.LineItemAdded)
        advanceUntilIdle()

        val keys = subject.state.value.lineItems.map { it.key }
        // Adding opens the new row.
        assertThat(subject.state.value.editor.expandedKey).isEqualTo(keys[1])

        subject.onEvent(EntryEvent.LineItemExpanded(keys[0]))
        advanceUntilIdle()
        assertThat(subject.state.value.editor.expandedKey).isEqualTo(keys[0])

        subject.onEvent(EntryEvent.LineItemRemoved(keys[0]))
        advanceUntilIdle()
        assertThat(subject.state.value.editor.expandedKey).isNull()
    }

    // ── Harness ────────────────────────────────────────────

    private fun TestScope.collected(): EntryViewModel {
        val subject = viewModel()
        CoroutineScope(Job() + dispatcher).launch { subject.state.collect { } }
        advanceUntilIdle()
        return subject
    }

    private fun viewModel() = EntryViewModel(
        approveTransaction = ApproveTransactionUseCase(ledger),
        drafts = drafts,
        ledgerRepository = ledger,
        categories = categories,
        merchants = merchants,
        paymentMethods = paymentMethods,
        clock = Clock { FIXED_NOW },
        ids = Uuid7Generator(SecureRandom()),
        savedStateHandle = SavedStateHandle(),
    )

    private fun EntryViewModel.type(text: String) = onEvent(EntryEvent.AmountChanged(text))

    private fun EntryViewModel.choose(picker: EntryPicker, id: String?) {
        onEvent(EntryEvent.PickerOpened(picker))
        onEvent(EntryEvent.PickerItemSelected(id))
    }

    /** Into itemised mode, which seeds the first line (ADR-0018). */
    private fun EntryViewModel.itemise() =
        onEvent(EntryEvent.ModeSelected(itemised = true))

    private companion object {
        private const val FIXED_NOW = 1_787_000_000_000L
    }
}
