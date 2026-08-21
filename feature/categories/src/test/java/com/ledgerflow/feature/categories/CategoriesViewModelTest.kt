package com.ledgerflow.feature.categories

import com.google.common.truth.Truth.assertThat
import com.ledgerflow.core.domain.taxonomy.TaxonomyError
import com.ledgerflow.core.domain.taxonomy.TaxonomyResult
import com.ledgerflow.core.domain.usecase.PurgeHiddenCategoryUseCase
import com.ledgerflow.core.domain.usecase.PurgeHiddenMerchantUseCase
import com.ledgerflow.core.domain.usecase.PurgeHiddenPaymentMethodUseCase
import com.ledgerflow.core.model.Category
import com.ledgerflow.core.model.CategoryTree
import com.ledgerflow.core.model.HiddenTaxonomy
import com.ledgerflow.core.model.LedgerType
import com.ledgerflow.core.model.Merchant
import com.ledgerflow.core.model.PaymentMethodType
import com.ledgerflow.core.testing.taxonomy.FakeCategoryRepository
import com.ledgerflow.core.testing.taxonomy.FakeMerchantRepository
import com.ledgerflow.core.testing.taxonomy.FakePaymentMethodRepository
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
 * Category, merchant and payment-method management (SPEC.md §5.5).
 *
 * The database rules -- uniqueness, the two-level tree, transactional
 * re-assignment -- are tested instrumented against real SQLCipher in
 * `TaxonomyRepositoryInstrumentedTest`. What is tested here is the *screen's*
 * behaviour when the repository refuses: whether the user keeps what they typed,
 * whether a refusal turns into a question, and whether the right call was made.
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class CategoriesViewModelTest {

    private val dispatcher = StandardTestDispatcher()
    private lateinit var categories: FakeCategoryRepository
    private lateinit var merchants: FakeMerchantRepository
    private lateinit var paymentMethods: FakePaymentMethodRepository

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        categories = FakeCategoryRepository()
        merchants = FakeMerchantRepository()
        paymentMethods = FakePaymentMethodRepository()
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun category(id: String, name: String, parent: String? = null, system: Boolean = false) =
        Category(id, parent, LedgerType.DEBIT, name, "", 0, 0, system)

    /**
     * `state` is a `stateIn(WhileSubscribed)`, so nothing flows until something
     * collects. Every test starts a collector and settles the scheduler.
     */
    private fun viewModel(): Pair<CategoriesViewModel, CoroutineScope> {
        val vm = CategoriesViewModel(
            categories = categories,
            merchants = merchants,
            paymentMethods = paymentMethods,
            // Real use cases over fake repositories. They are thin by design and
            // wrapping them in fakes would test the wrapper; what matters is
            // that the screen goes *through* them, which is what
            // `TaxonomySingleWriterTest` enforces and what the fakes record.
            purgeCategory = PurgeHiddenCategoryUseCase(categories),
            purgeMerchant = PurgeHiddenMerchantUseCase(merchants),
            purgePaymentMethod = PurgeHiddenPaymentMethodUseCase(paymentMethods),
        )
        val scope = CoroutineScope(dispatcher)
        scope.launch { vm.state.collect {} }
        dispatcher.scheduler.advanceUntilIdle()
        return vm to scope
    }

    private fun CategoriesViewModel.settle() = dispatcher.scheduler.advanceUntilIdle()

    /**
     * Say yes to the "are you sure?" that now precedes every delete.
     *
     * Spelled out in each test rather than folded into a `delete()` helper: the
     * point of these tests is that the two steps are separate, and a helper that
     * performed both would pass just as happily if the guard were removed.
     */
    private fun CategoriesViewModel.confirmDelete() {
        onEvent(CategoriesEvent.DialogConfirmed)
        settle()
    }

    // ── Sections and the ledger partition ───────────────────────────────────

    @Test
    fun startsOnCategoriesShowingTheDebitTree() = runTest(dispatcher) {
        categories.trees[LedgerType.DEBIT] = listOf(CategoryTree(category("1", "Food"), emptyList()))
        val (vm, scope) = viewModel()

        assertThat(vm.state.value.section).isEqualTo(TaxonomySection.Categories)
        assertThat(vm.state.value.ledger).isEqualTo(LedgerType.DEBIT)
        assertThat(vm.state.value.tree.map { it.parent.name }).containsExactly("Food")
        scope.cancel()
    }

    /**
     * The ledger control selects a partition, not a filter (Law 2). Switching it
     * must re-subscribe to the other tree rather than filter the current one.
     */
    @Test
    fun switchingLedgerSwitchesToTheOtherTree() = runTest(dispatcher) {
        categories.trees[LedgerType.DEBIT] = listOf(CategoryTree(category("1", "Food"), emptyList()))
        categories.trees[LedgerType.CREDIT] = listOf(
            CategoryTree(
                Category("2", null, LedgerType.CREDIT, "Salary", "", 0, 0, false),
                emptyList(),
            ),
        )
        val (vm, scope) = viewModel()

        vm.onEvent(CategoriesEvent.LedgerSelected(LedgerType.CREDIT))
        vm.settle()

        assertThat(vm.state.value.tree.map { it.parent.name }).containsExactly("Salary")
        scope.cancel()
    }

    // ── Creating ────────────────────────────────────────────────────────────

    @Test
    fun addingACategoryOpensAPromptAndCreatesInTheSelectedLedger() = runTest(dispatcher) {
        val (vm, scope) = viewModel()
        vm.onEvent(CategoriesEvent.LedgerSelected(LedgerType.CREDIT))

        vm.onEvent(CategoriesEvent.AddCategory(parentId = null, parentName = null))
        vm.onEvent(CategoriesEvent.DialogTextChanged("Freelance"))
        vm.onEvent(CategoriesEvent.DialogConfirmed)
        vm.settle()

        val created = categories.created.single()
        assertThat(created.name).isEqualTo("Freelance")
        assertThat(created.ledger).isEqualTo(LedgerType.CREDIT)
        assertThat(created.parentId).isNull()
        assertThat(vm.state.value.dialog).isNull()
        scope.cancel()
    }

    @Test
    fun addingASubcategoryCarriesTheParent() = runTest(dispatcher) {
        val (vm, scope) = viewModel()

        vm.onEvent(CategoriesEvent.AddCategory(parentId = "cat-food", parentName = "Food"))
        vm.onEvent(CategoriesEvent.DialogTextChanged("Groceries"))
        vm.onEvent(CategoriesEvent.DialogConfirmed)
        vm.settle()

        assertThat(categories.created.single().parentId).isEqualTo("cat-food")
        scope.cancel()
    }

    /**
     * A refusal must not close the dialog. Closing it throws away what the user
     * typed and leaves them nothing to correct -- "that name is taken" is only
     * useful next to the name.
     */
    @Test
    fun aDuplicateNameKeepsTheDialogOpenWithTheTypedValue() = runTest(dispatcher) {
        categories.createResult = TaxonomyResult.Failure(TaxonomyError.DuplicateName("Food"))
        val (vm, scope) = viewModel()

        vm.onEvent(CategoriesEvent.AddCategory(null, null))
        vm.onEvent(CategoriesEvent.DialogTextChanged("Food"))
        vm.onEvent(CategoriesEvent.DialogConfirmed)
        vm.settle()

        val dialog = vm.state.value.dialog
        assertThat(dialog).isInstanceOf(TaxonomyDialog.TextPrompt::class.java)
        assertThat((dialog as TaxonomyDialog.TextPrompt).value).isEqualTo("Food")
        assertThat(vm.state.value.message).contains("already exists")
        scope.cancel()
    }

    // ── Deleting: the confirmation guard ────────────────────────────────────

    /**
     * Delete used to be a one-tap write, and every one of these rows sits under
     * a finger in a scrolling list.
     *
     * The assertion that matters is the second one in each case: not that a
     * dialog appeared, but that the repository was *not* called. A guard that
     * shows a dialog and deletes anyway would satisfy the first alone.
     */
    @Test
    fun deletingACategoryAsksFirstAndWritesNothingYet() = runTest(dispatcher) {
        val (vm, scope) = viewModel()

        vm.onEvent(CategoriesEvent.DeleteCategory("cat-1", "Snacks", isChild = false))
        vm.settle()

        val dialog = vm.state.value.dialog
        assertThat(dialog).isInstanceOf(TaxonomyDialog.ConfirmDelete::class.java)
        with(dialog as TaxonomyDialog.ConfirmDelete) {
            assertThat(target).isEqualTo(DeleteTarget.Category)
            // The name is carried so the dialog can name what it is about to
            // remove; "delete this?" is not a question anyone can answer.
            assertThat(name).isEqualTo("Snacks")
        }
        assertThat(categories.deleted).isEmpty()
        scope.cancel()
    }

    @Test
    fun deletingASubcategoryIsNamedAsOne() = runTest(dispatcher) {
        val (vm, scope) = viewModel()

        vm.onEvent(CategoriesEvent.DeleteCategory("cat-2", "Coffee", isChild = true))
        vm.settle()

        val dialog = vm.state.value.dialog as TaxonomyDialog.ConfirmDelete
        assertThat(dialog.target).isEqualTo(DeleteTarget.Subcategory)
        scope.cancel()
    }

    @Test
    fun deletingAMerchantAsksFirstAndWritesNothingYet() = runTest(dispatcher) {
        val (vm, scope) = viewModel()

        vm.onEvent(CategoriesEvent.DeleteMerchant("m1", "Amazon"))
        vm.settle()

        val dialog = vm.state.value.dialog as TaxonomyDialog.ConfirmDelete
        assertThat(dialog.target).isEqualTo(DeleteTarget.Merchant)
        assertThat(merchants.deleted).isEmpty()
        scope.cancel()
    }

    @Test
    fun deletingAPaymentMethodAsksFirstAndWritesNothingYet() = runTest(dispatcher) {
        val (vm, scope) = viewModel()

        vm.onEvent(CategoriesEvent.DeletePaymentMethod("pm1", "HDFC Card"))
        vm.settle()

        val dialog = vm.state.value.dialog as TaxonomyDialog.ConfirmDelete
        assertThat(dialog.target).isEqualTo(DeleteTarget.PaymentMethod)
        assertThat(paymentMethods.deleted).isEmpty()
        scope.cancel()
    }

    @Test
    fun dismissingTheConfirmationDeletesNothing() = runTest(dispatcher) {
        val (vm, scope) = viewModel()

        vm.onEvent(CategoriesEvent.DeleteCategory("cat-1", "Snacks", isChild = false))
        vm.onEvent(CategoriesEvent.DialogDismissed)
        vm.settle()

        assertThat(categories.deleted).isEmpty()
        assertThat(vm.state.value.dialog).isNull()
        scope.cancel()
    }

    @Test
    fun confirmingAMerchantDeleteIsWhatActuallyDeletesIt() = runTest(dispatcher) {
        val (vm, scope) = viewModel()

        vm.onEvent(CategoriesEvent.DeleteMerchant("m1", "Amazon"))
        vm.confirmDelete()

        assertThat(merchants.deleted).containsExactly("m1")
        assertThat(vm.state.value.dialog).isNull()
        scope.cancel()
    }

    @Test
    fun confirmingAPaymentMethodDeleteIsWhatActuallyDeletesIt() = runTest(dispatcher) {
        val (vm, scope) = viewModel()

        vm.onEvent(CategoriesEvent.DeletePaymentMethod("pm1", "HDFC Card"))
        vm.confirmDelete()

        assertThat(paymentMethods.deleted).containsExactly("pm1")
        assertThat(vm.state.value.dialog).isNull()
        scope.cancel()
    }

    // ── Deleting, and the re-assign flow (§5.5) ─────────────────────────────

    @Test
    fun deletingAnUnusedCategoryJustDeletesIt() = runTest(dispatcher) {
        val (vm, scope) = viewModel()

        vm.onEvent(CategoriesEvent.DeleteCategory("cat-1", "Snacks", isChild = false))
        vm.confirmDelete()

        assertThat(categories.deleted).containsExactly("cat-1" to null)
        assertThat(vm.state.value.dialog).isNull()
        scope.cancel()
    }

    /**
     * The count in the dialog comes from the repository's refusal, not from a
     * separate query. Asking twice would race an approval landing in between and
     * show a number that was already wrong.
     */
    @Test
    fun deletingACategoryInUseTurnsTheRefusalIntoAQuestion() = runTest(dispatcher) {
        categories.trees[LedgerType.DEBIT] = listOf(
            CategoryTree(category("cat-1", "Snacks"), emptyList()),
            CategoryTree(category("cat-2", "Groceries"), emptyList()),
        )
        categories.deleteResult = TaxonomyResult.Failure(TaxonomyError.ReassignRequired(7))
        val (vm, scope) = viewModel()

        vm.onEvent(CategoriesEvent.DeleteCategory("cat-1", "Snacks", isChild = false))
        vm.confirmDelete()
        vm.settle()

        val dialog = vm.state.value.dialog
        assertThat(dialog).isInstanceOf(TaxonomyDialog.ReassignCategory::class.java)
        with(dialog as TaxonomyDialog.ReassignCategory) {
            assertThat(affected).isEqualTo(7)
            // The category being deleted is not somewhere its own entries can go.
            assertThat(candidates.map { it.id }).containsExactly("cat-2")
        }
        scope.cancel()
    }

    @Test
    fun choosingATargetRetriesTheDeleteWithIt() = runTest(dispatcher) {
        categories.trees[LedgerType.DEBIT] = listOf(
            CategoryTree(category("cat-1", "Snacks"), emptyList()),
            CategoryTree(category("cat-2", "Groceries"), emptyList()),
        )
        categories.deleteResult = TaxonomyResult.Failure(TaxonomyError.ReassignRequired(2))
        val (vm, scope) = viewModel()
        vm.onEvent(CategoriesEvent.DeleteCategory("cat-1", "Snacks", isChild = false))
        vm.confirmDelete()
        vm.settle()

        categories.deleteResult = TaxonomyResult.Success(Unit)
        vm.onEvent(CategoriesEvent.DialogTargetSelected("cat-2"))
        vm.onEvent(CategoriesEvent.DialogConfirmed)
        vm.settle()

        assertThat(categories.deleted).contains("cat-1" to "cat-2")
        assertThat(vm.state.value.dialog).isNull()
        scope.cancel()
    }

    @Test
    fun aRefusalIsExplainedRatherThanSwallowed() = runTest(dispatcher) {
        categories.deleteResult = TaxonomyResult.Failure(TaxonomyError.NotFound)
        val (vm, scope) = viewModel()

        vm.onEvent(CategoriesEvent.DeleteCategory("cat-gone", "Gone", isChild = false))
        vm.confirmDelete()
        vm.settle()

        assertThat(vm.state.value.message).isNotEmpty()
        scope.cancel()
    }

    // ── Merchants ───────────────────────────────────────────────────────────

    @Test
    fun mergeOffersEveryOtherMerchantButNotItself() = runTest(dispatcher) {
        merchants.merchants.value = listOf(
            Merchant("m1", "Amazn", "amazn", null, null),
            Merchant("m2", "Amazon", "amazon", null, null),
        )
        val (vm, scope) = viewModel()
        vm.onEvent(CategoriesEvent.SectionSelected(TaxonomySection.Merchants))
        vm.settle()

        vm.onEvent(CategoriesEvent.StartMergeMerchant("m1", "Amazn"))
        vm.settle()

        val dialog = vm.state.value.dialog as TaxonomyDialog.MergeMerchant
        assertThat(dialog.candidates.map { it.id }).containsExactly("m2")
        scope.cancel()
    }

    @Test
    fun confirmingAMergeWithoutATargetDoesNothing() = runTest(dispatcher) {
        merchants.merchants.value = listOf(Merchant("m1", "Amazn", "amazn", null, null))
        val (vm, scope) = viewModel()
        vm.onEvent(CategoriesEvent.StartMergeMerchant("m1", "Amazn"))
        vm.settle()

        vm.onEvent(CategoriesEvent.DialogConfirmed)
        vm.settle()

        assertThat(merchants.merged).isEmpty()
        scope.cancel()
    }

    @Test
    fun confirmingAMergeWithATargetPerformsIt() = runTest(dispatcher) {
        merchants.merchants.value = listOf(
            Merchant("m1", "Amazn", "amazn", null, null),
            Merchant("m2", "Amazon", "amazon", null, null),
        )
        val (vm, scope) = viewModel()
        vm.onEvent(CategoriesEvent.StartMergeMerchant("m1", "Amazn"))
        vm.settle()

        vm.onEvent(CategoriesEvent.DialogTargetSelected("m2"))
        vm.onEvent(CategoriesEvent.DialogConfirmed)
        vm.settle()

        assertThat(merchants.merged).containsExactly("m1" to "m2")
        scope.cancel()
    }

    // ── Payment methods ─────────────────────────────────────────────────────

    /**
     * §5.5 stores a last-4, never a card number. The field keeps the LAST
     * four rather than the first: someone pasting a full PAN into a field
     * labelled "last 4 digits" means the last four, and keeping the first four
     * would store a number that identifies nothing.
     */
    @Test
    fun theLast4FieldKeepsFourDigitsAndDropsNonDigits() = runTest(dispatcher) {
        val (vm, scope) = viewModel()
        vm.onEvent(CategoriesEvent.AddPaymentMethod)
        vm.settle()

        vm.onEvent(CategoriesEvent.DialogLast4Changed("4111-1111-1111-1234"))
        vm.settle()

        val dialog = vm.state.value.dialog as TaxonomyDialog.NewPaymentMethod
        assertThat(dialog.last4).isEqualTo("1234")
        scope.cancel()
    }

    @Test
    fun creatingAPaymentMethodPassesLabelTypeAndLast4() = runTest(dispatcher) {
        val (vm, scope) = viewModel()
        vm.onEvent(CategoriesEvent.AddPaymentMethod)
        vm.settle()

        vm.onEvent(CategoriesEvent.DialogTextChanged("HDFC Card"))
        vm.onEvent(CategoriesEvent.DialogTypeSelected(PaymentMethodType.CREDIT_CARD))
        vm.onEvent(CategoriesEvent.DialogLast4Changed("4821"))
        vm.onEvent(CategoriesEvent.DialogConfirmed)
        vm.settle()

        val created = paymentMethods.created.single()
        assertThat(created.label).isEqualTo("HDFC Card")
        assertThat(created.type).isEqualTo(PaymentMethodType.CREDIT_CARD)
        assertThat(created.last4).isEqualTo("4821")
        scope.cancel()
    }

    @Test
    fun anEmptyLast4IsStoredAsNullRatherThanAnEmptyString() = runTest(dispatcher) {
        val (vm, scope) = viewModel()
        vm.onEvent(CategoriesEvent.AddPaymentMethod)
        vm.settle()

        vm.onEvent(CategoriesEvent.DialogTextChanged("Cash"))
        vm.onEvent(CategoriesEvent.DialogConfirmed)
        vm.settle()

        assertThat(paymentMethods.created.single().last4).isNull()
        scope.cancel()
    }

    @Test
    fun dismissingADialogDiscardsIt() = runTest(dispatcher) {
        val (vm, scope) = viewModel()
        vm.onEvent(CategoriesEvent.AddCategory(null, null))
        vm.settle()

        vm.onEvent(CategoriesEvent.DialogDismissed)
        vm.settle()

        assertThat(vm.state.value.dialog).isNull()
        assertThat(categories.created).isEmpty()
        scope.cancel()
    }

    // -- The hidden section (ADR-0016) ---------------------------------------

    @Test
    fun hiddenRows_areReadFromTheSectionOnShow() = runTest(dispatcher) {
        categories.hidden[LedgerType.DEBIT] = listOf(hidden("c1", "Food"))
        merchants.hidden.value = listOf(hidden("m1", "Amazon"))
        val (vm, scope) = viewModel()

        assertThat(vm.state.value.hidden.map { it.name }).containsExactly("Food")

        vm.onEvent(CategoriesEvent.SectionSelected(TaxonomySection.Merchants))
        vm.settle()

        assertThat(vm.state.value.hidden.map { it.name }).containsExactly("Amazon")
        scope.cancel()
    }

    /**
     * The Categories tab's hidden rows follow the ledger control.
     *
     * The two trees are disjoint (Law 2), and so are the two sets of hidden
     * rows. Switching books must re-read, not filter.
     */
    @Test
    fun hiddenCategories_followTheLedgerPartition() = runTest(dispatcher) {
        categories.hidden[LedgerType.DEBIT] = listOf(hidden("c1", "Food"))
        categories.hidden[LedgerType.CREDIT] = listOf(hidden("c2", "Bonus"))
        val (vm, scope) = viewModel()

        vm.onEvent(CategoriesEvent.LedgerSelected(LedgerType.CREDIT))
        vm.settle()

        assertThat(vm.state.value.hidden.map { it.name }).containsExactly("Bonus")
        scope.cancel()
    }

    /**
     * Switching section re-collapses.
     *
     * One flag over three sections: carrying it across would open the Merchants
     * hidden list because the user had opened the Categories one.
     */
    @Test
    fun switchingSection_collapsesTheHiddenList() = runTest(dispatcher) {
        merchants.hidden.value = listOf(hidden("m1", "Amazon"))
        val (vm, scope) = viewModel()

        vm.onEvent(CategoriesEvent.HiddenToggled)
        vm.settle()
        assertThat(vm.state.value.hiddenExpanded).isTrue()

        vm.onEvent(CategoriesEvent.SectionSelected(TaxonomySection.Merchants))
        vm.settle()

        assertThat(vm.state.value.hiddenExpanded).isFalse()
        scope.cancel()
    }

    /** Restore asks nothing. The bin settled that, and this keeps it settled. */
    @Test
    fun restore_writesImmediatelyWithNoDialog() = runTest(dispatcher) {
        merchants.hidden.value = listOf(hidden("m1", "Amazon"))
        val (vm, scope) = viewModel()
        vm.onEvent(CategoriesEvent.SectionSelected(TaxonomySection.Merchants))
        vm.settle()

        vm.onEvent(CategoriesEvent.RestoreHidden("m1", "Amazon"))
        vm.settle()

        assertThat(merchants.restored).containsExactly("m1")
        assertThat(vm.state.value.dialog).isNull()
        scope.cancel()
    }

    @Test
    fun restore_surfacesARefusalAsAMessage() = runTest(dispatcher) {
        merchants.hidden.value = listOf(hidden("m1", "Amazon"))
        merchants.restoreResult = TaxonomyResult.Failure(TaxonomyError.DuplicateName("Amazon"))
        val (vm, scope) = viewModel()
        vm.onEvent(CategoriesEvent.SectionSelected(TaxonomySection.Merchants))
        vm.settle()

        vm.onEvent(CategoriesEvent.RestoreHidden("m1", "Amazon"))
        vm.settle()

        assertThat(vm.state.value.message).contains("already exists")
        scope.cancel()
    }

    /** Erase always asks, and writes nothing until it is answered. */
    @Test
    fun erase_asksBeforeDestroyingAnything() = runTest(dispatcher) {
        merchants.hidden.value = listOf(hidden("m1", "Amazon"))
        val (vm, scope) = viewModel()
        vm.onEvent(CategoriesEvent.SectionSelected(TaxonomySection.Merchants))
        vm.settle()

        vm.onEvent(CategoriesEvent.EraseHidden("m1", "Amazon"))
        vm.settle()

        val dialog = vm.state.value.dialog
        assertThat(dialog).isInstanceOf(TaxonomyDialog.ConfirmErase::class.java)
        assertThat((dialog as TaxonomyDialog.ConfirmErase).target).isEqualTo(DeleteTarget.Merchant)
        assertThat(merchants.purged).isEmpty()

        vm.onEvent(CategoriesEvent.DialogConfirmed)
        vm.settle()

        assertThat(merchants.purged).containsExactly("m1" to null)
        scope.cancel()
    }

    /**
     * A `ReassignRequired` refusal becomes the question it implies, and the
     * answer carries the target through.
     *
     * The count comes from the repository rather than being recomputed here --
     * a number the UI counted separately could disagree with the one the
     * database saw, and on this path the disagreement is how a reference gets
     * destroyed.
     */
    @Test
    fun erase_turnsAReassignRefusalIntoAQuestion() = runTest(dispatcher) {
        merchants.merchants.value = listOf(Merchant("m2", "DMart", "dmart", null, null))
        merchants.hidden.value = listOf(hidden("m1", "Amazon"))
        merchants.purgeResult = TaxonomyResult.Failure(TaxonomyError.ReassignRequired(3))
        val (vm, scope) = viewModel()
        vm.onEvent(CategoriesEvent.SectionSelected(TaxonomySection.Merchants))
        vm.settle()

        vm.onEvent(CategoriesEvent.EraseHidden("m1", "Amazon"))
        vm.onEvent(CategoriesEvent.DialogConfirmed)
        vm.settle()

        val dialog = vm.state.value.dialog
        assertThat(dialog).isInstanceOf(TaxonomyDialog.ReassignBeforeErase::class.java)
        val reassign = dialog as TaxonomyDialog.ReassignBeforeErase
        assertThat(reassign.affected).isEqualTo(3)
        assertThat(reassign.candidates.map { it.name }).containsExactly("DMart")

        merchants.purgeResult = TaxonomyResult.Success(Unit)
        vm.onEvent(CategoriesEvent.DialogTargetSelected("m2"))
        vm.onEvent(CategoriesEvent.DialogConfirmed)
        vm.settle()

        assertThat(merchants.purged.last()).isEqualTo("m1" to "m2")
        assertThat(vm.state.value.dialog).isNull()
        scope.cancel()
    }

    /** A payment-method erase takes no target -- hiding already scrubbed it. */
    @Test
    fun erase_ofAPaymentMethod_passesNoReassignTarget() = runTest(dispatcher) {
        paymentMethods.hidden.value = listOf(hidden("p1", "HDFC"))
        val (vm, scope) = viewModel()
        vm.onEvent(CategoriesEvent.SectionSelected(TaxonomySection.PaymentMethods))
        vm.settle()

        vm.onEvent(CategoriesEvent.EraseHidden("p1", "HDFC"))
        vm.onEvent(CategoriesEvent.DialogConfirmed)
        vm.settle()

        assertThat(paymentMethods.purged).containsExactly("p1")
        scope.cancel()
    }

    private fun hidden(id: String, name: String) = HiddenTaxonomy(id, name, hiddenAt = 1_000L)
}

private fun CoroutineScope.cancel() = coroutineContext[kotlinx.coroutines.Job]?.cancel()


