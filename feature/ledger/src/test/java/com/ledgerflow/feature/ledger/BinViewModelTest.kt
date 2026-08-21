package com.ledgerflow.feature.ledger

import com.google.common.truth.Truth.assertThat
import com.ledgerflow.core.domain.usecase.PurgeDeletedEntriesUseCase
import com.ledgerflow.core.domain.usecase.RestoreEntryUseCase
import com.ledgerflow.core.model.DeletedEntry
import com.ledgerflow.core.model.LedgerType
import com.ledgerflow.core.model.Money
import com.ledgerflow.core.testing.ledger.FakeLedgerRepository
import com.ledgerflow.core.testing.ledger.FakeStorageMaintenance
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
 * The bin (ADR-0015).
 *
 * This file is the ADR's verification clause. The bin is **the only surface in
 * the app that shows both books at once**, and the carve-out that allows it
 * rests on two properties that are easy to lose in a refactor and invisible
 * when they break:
 *
 * 1. every row keeps its own [LedgerType] through the merge, and
 * 2. every write is dispatched with the book its row came from.
 *
 * Lose the first and the screen colours an income row as an expense. Lose the
 * second and a restore is issued against the wrong book, affects nothing, and
 * silently does nothing at all (Law 2). Neither shows up as a crash.
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class BinViewModelTest {

    private val dispatcher = StandardTestDispatcher()
    private lateinit var ledger: FakeLedgerRepository

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        ledger = FakeLedgerRepository()
    }

    @After
    fun tearDown() = Dispatchers.resetMain()

    // ── The mixed list (ADR-0015) ───────────────────────────────────────────

    @Test
    fun bothBooksAppearInOneList_newestFirst() = runTest(dispatcher) {
        ledger.emitBinned(LedgerType.DEBIT, listOf(binned("d1", LedgerType.DEBIT, at = 300L)))
        ledger.emitBinned(LedgerType.CREDIT, listOf(binned("c1", LedgerType.CREDIT, at = 400L)))
        val viewModel = viewModel()

        assertThat(viewModel.state.value.entries.map { it.id })
            .containsExactly("c1", "d1")
            .inOrder()
    }

    /**
     * **The property ADR-0015 rests on.**
     *
     * The merge must not flatten the books into an undifferentiated list. A row
     * that lost its `ledger` would be signed and coloured wrong, and every
     * write about it would go to whichever book the code guessed.
     */
    @Test
    fun mergedRows_keepTheirOwnBook() = runTest(dispatcher) {
        ledger.emitBinned(LedgerType.DEBIT, listOf(binned("d1", LedgerType.DEBIT, at = 300L)))
        ledger.emitBinned(LedgerType.CREDIT, listOf(binned("c1", LedgerType.CREDIT, at = 400L)))
        val viewModel = viewModel()

        val byId = viewModel.state.value.entries.associateBy { it.id }
        assertThat(byId.getValue("d1").ledger).isEqualTo(LedgerType.DEBIT)
        assertThat(byId.getValue("c1").ledger).isEqualTo(LedgerType.CREDIT)
    }

    /** Selection is keyed on the pair, so two books cannot collide on an id. */
    @Test
    fun selectionKeys_areUniqueAcrossBooks() {
        val debit = binned("same-id", LedgerType.DEBIT)
        val credit = binned("same-id", LedgerType.CREDIT)

        assertThat(debit.selectionKey()).isNotEqualTo(credit.selectionKey())
    }

    // ── Selecting ───────────────────────────────────────────────────────────

    @Test
    fun toggling_selectsAndDeselects() = runTest(dispatcher) {
        ledger.emitBinned(LedgerType.DEBIT, listOf(binned("d1", LedgerType.DEBIT)))
        val viewModel = viewModel()
        val key = viewModel.state.value.entries.single().selectionKey()

        viewModel.onEvent(BinEvent.Toggled(key))
        dispatcher.scheduler.advanceUntilIdle()
        assertThat(viewModel.state.value.selectionCount).isEqualTo(1)

        viewModel.onEvent(BinEvent.Toggled(key))
        dispatcher.scheduler.advanceUntilIdle()
        assertThat(viewModel.state.value.hasSelection).isFalse()
    }

    @Test
    fun selectAll_thenAgain_clears() = runTest(dispatcher) {
        ledger.emitBinned(LedgerType.DEBIT, listOf(binned("d1", LedgerType.DEBIT)))
        ledger.emitBinned(LedgerType.CREDIT, listOf(binned("c1", LedgerType.CREDIT)))
        val viewModel = viewModel()

        viewModel.onEvent(BinEvent.SelectAllToggled)
        dispatcher.scheduler.advanceUntilIdle()
        assertThat(viewModel.state.value.selectionCount).isEqualTo(2)

        viewModel.onEvent(BinEvent.SelectAllToggled)
        dispatcher.scheduler.advanceUntilIdle()
        assertThat(viewModel.state.value.hasSelection).isFalse()
    }

    // ── Restoring ───────────────────────────────────────────────────────────

    /**
     * Restore acts immediately — no confirmation.
     *
     * Deliberate asymmetry with erase: putting something back is undone by
     * binning it again, so a question would be ceremony. Erasing cannot be
     * undone at all, so it always asks.
     */
    @Test
    fun restore_actsWithoutAConfirmation() = runTest(dispatcher) {
        ledger.emitBinned(LedgerType.DEBIT, listOf(binned("d1", LedgerType.DEBIT)))
        val viewModel = viewModel()
        selectAll(viewModel)

        viewModel.onEvent(BinEvent.RestoreRequested)
        dispatcher.scheduler.advanceUntilIdle()

        assertThat(ledger.restored).containsExactly(LedgerType.DEBIT to "d1")
        assertThat(viewModel.state.value.confirmation).isNull()
    }

    /**
     * **Each row is restored through its own book.**
     *
     * The failure this catches is silent: an id sent to the wrong ledger
     * matches no row, the statement affects nothing, and the entry simply
     * stays in the bin with no error anywhere (Law 2).
     */
    @Test
    fun restore_dispatchesEachRowToItsOwnBook() = runTest(dispatcher) {
        ledger.emitBinned(LedgerType.DEBIT, listOf(binned("d1", LedgerType.DEBIT)))
        ledger.emitBinned(LedgerType.CREDIT, listOf(binned("c1", LedgerType.CREDIT)))
        val viewModel = viewModel()
        selectAll(viewModel)

        viewModel.onEvent(BinEvent.RestoreRequested)
        dispatcher.scheduler.advanceUntilIdle()

        assertThat(ledger.restored).containsExactly(
            LedgerType.DEBIT to "d1",
            LedgerType.CREDIT to "c1",
        )
    }

    @Test
    fun restore_reportsWhatWentBack() = runTest(dispatcher) {
        ledger.emitBinned(LedgerType.DEBIT, listOf(binned("d1", LedgerType.DEBIT)))
        val viewModel = viewModel()
        selectAll(viewModel)

        viewModel.onEvent(BinEvent.RestoreRequested)
        dispatcher.scheduler.advanceUntilIdle()

        assertThat(viewModel.state.value.message).isEqualTo("Restored 1 entry.")
        assertThat(viewModel.state.value.hasSelection).isFalse()
    }

    // ── Erasing ─────────────────────────────────────────────────────────────

    /** The first tap asks; it destroys nothing. */
    @Test
    fun purgeSelected_asksFirst() = runTest(dispatcher) {
        ledger.emitBinned(LedgerType.DEBIT, listOf(binned("d1", LedgerType.DEBIT)))
        val viewModel = viewModel()
        selectAll(viewModel)

        viewModel.onEvent(BinEvent.PurgeSelectedRequested)
        dispatcher.scheduler.advanceUntilIdle()

        assertThat(viewModel.state.value.confirmation)
            .isEqualTo(BinConfirmation.PurgeSelected(1))
        assertThat(ledger.purgedEntries).isEmpty()
    }

    @Test
    fun purgeSelected_destroysOnlyTheChosenRows_throughTheirOwnBooks() = runTest(dispatcher) {
        ledger.emitBinned(LedgerType.DEBIT, listOf(binned("d1", LedgerType.DEBIT, at = 100L)))
        ledger.emitBinned(LedgerType.CREDIT, listOf(binned("c1", LedgerType.CREDIT, at = 200L)))
        val viewModel = viewModel()
        val credit = viewModel.state.value.entries.single { it.id == "c1" }

        viewModel.onEvent(BinEvent.Toggled(credit.selectionKey()))
        viewModel.onEvent(BinEvent.PurgeSelectedRequested)
        viewModel.onEvent(BinEvent.ConfirmationAccepted)
        dispatcher.scheduler.advanceUntilIdle()

        assertThat(ledger.purgedEntries).containsExactly(LedgerType.CREDIT to "c1")
        // The unticked debit row is untouched, which is the whole point of a
        // bin you pick from rather than one you only empty.
        assertThat(viewModel.state.value.entries.map { it.id }).containsExactly("d1")
    }

    @Test
    fun purgeSelected_dismissed_destroysNothing() = runTest(dispatcher) {
        ledger.emitBinned(LedgerType.DEBIT, listOf(binned("d1", LedgerType.DEBIT)))
        val viewModel = viewModel()
        selectAll(viewModel)

        viewModel.onEvent(BinEvent.PurgeSelectedRequested)
        viewModel.onEvent(BinEvent.ConfirmationDismissed)
        dispatcher.scheduler.advanceUntilIdle()

        assertThat(ledger.purgedEntries).isEmpty()
        assertThat(viewModel.state.value.confirmation).isNull()
    }

    /** "Erase all" needs no selection — it is the one action that never does. */
    @Test
    fun purgeAll_asksThenEmptiesBothBooks() = runTest(dispatcher) {
        ledger.emitBinned(LedgerType.DEBIT, listOf(binned("d1", LedgerType.DEBIT)))
        ledger.emitBinned(LedgerType.CREDIT, listOf(binned("c1", LedgerType.CREDIT)))
        val viewModel = viewModel()

        viewModel.onEvent(BinEvent.PurgeAllRequested)
        dispatcher.scheduler.advanceUntilIdle()
        assertThat(viewModel.state.value.confirmation).isEqualTo(BinConfirmation.PurgeAll(2))

        viewModel.onEvent(BinEvent.ConfirmationAccepted)
        dispatcher.scheduler.advanceUntilIdle()

        assertThat(ledger.purged).containsExactly(LedgerType.DEBIT, LedgerType.CREDIT)
    }

    /**
     * A tick pointing at a row that has left the bin drops out of the
     * selection.
     *
     * Otherwise the count in the header keeps counting something that is no
     * longer on screen, and the actions below act on a key matching nothing.
     */
    @Test
    fun selection_dropsRowsThatLeaveTheBin() = runTest(dispatcher) {
        ledger.emitBinned(LedgerType.DEBIT, listOf(binned("d1", LedgerType.DEBIT)))
        val viewModel = viewModel()
        selectAll(viewModel)
        assertThat(viewModel.state.value.selectionCount).isEqualTo(1)

        ledger.emitBinned(LedgerType.DEBIT, emptyList())
        dispatcher.scheduler.advanceUntilIdle()

        assertThat(viewModel.state.value.hasSelection).isFalse()
    }

    @Test
    fun emptyBin_isLoadedAndEmpty_notUnknown() = runTest(dispatcher) {
        val viewModel = viewModel()

        assertThat(viewModel.state.value.isLoaded).isTrue()
        assertThat(viewModel.state.value.entries).isEmpty()
    }

    // ── Helpers ─────────────────────────────────────────────────────────────

    private fun selectAll(viewModel: BinViewModel) {
        viewModel.onEvent(BinEvent.SelectAllToggled)
        dispatcher.scheduler.advanceUntilIdle()
    }

    private fun binned(
        id: String,
        ledger: LedgerType,
        at: Long = 1_000L,
    ) = DeletedEntry(
        id = id,
        ledger = ledger,
        amount = Money(69_00L),
        currency = "INR",
        occurredAt = at,
        deletedAt = at + 1,
        categoryName = "Transport",
        categoryColorArgb = 0,
        subcategoryName = "Auto",
        merchantName = "Uber",
        note = null,
    )

    /**
     * `state` is a `stateIn(WhileSubscribed)`, so nothing flows until something
     * collects. On the device the subscriber is `collectAsStateWithLifecycle`.
     */
    /** Compaction moved to its own port when the taxonomy purge became a second caller. */
    private val storage = FakeStorageMaintenance()

    private fun viewModel(): BinViewModel {
        val viewModel = BinViewModel(
            ledger = ledger,
            restoreEntries = RestoreEntryUseCase(ledger),
            purgeEntries = PurgeDeletedEntriesUseCase(ledger, storage),
        )
        CoroutineScope(dispatcher).launch { viewModel.state.collect {} }
        dispatcher.scheduler.advanceUntilIdle()
        return viewModel
    }
}
