package com.ledgerflow.feature.ledger

import androidx.paging.testing.asSnapshot
import com.google.common.truth.Truth.assertThat
import com.ledgerflow.core.common.time.Clock
import com.ledgerflow.core.domain.analytics.MerchantCategoryBreakdown
import com.ledgerflow.core.domain.analytics.NoOpBudgetAlertTrigger
import com.ledgerflow.core.domain.ledger.DraftSummaryFields
import com.ledgerflow.core.domain.usecase.DeleteEntryUseCase
import com.ledgerflow.core.domain.usecase.ObservePendingUseCase
import com.ledgerflow.core.domain.usecase.RestoreEntryUseCase
import com.ledgerflow.core.model.LedgerListItem
import com.ledgerflow.core.model.LedgerType
import com.ledgerflow.core.model.MerchantCategory
import com.ledgerflow.core.model.Money
import com.ledgerflow.core.testing.inbox.FakePendingRepository
import com.ledgerflow.core.testing.ledger.FakeDraftRepository
import com.ledgerflow.core.testing.ledger.FakeLedgerRepository
import com.ledgerflow.core.testing.ledger.FakeMerchantEntriesRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test

/**
 * The Ledger's merchant-category filter (V2-6, V2-PLAN §5.3): it swaps the
 * list's source for the filtered read, hides the unsaved rows, outlives a book
 * switch, and clearing it brings the plain list back.
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class LedgerMerchantCategoryFilterTest {

    private val dispatcher = StandardTestDispatcher()
    private val ledger = FakeLedgerRepository()
    private val drafts = FakeDraftRepository()
    private val pending = FakePendingRepository()
    private val merchantEntries = FakeMerchantEntriesRepository()
    private val kinds = listOf(
        MerchantCategory("g", "Groceries", 0, 0, isSystem = true),
        MerchantCategory("f", "Fuel", 0, 1, isSystem = true),
    )

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() = Dispatchers.resetMain()

    private fun row(id: String, book: LedgerType = LedgerType.DEBIT) = LedgerListItem(
        id = id, ledger = book, amount = Money(100), currency = "INR", occurredAt = FIXED_NOW,
        localDate = TODAY, categoryName = null, categoryColorArgb = null, merchantName = id, note = null,
    )

    private fun viewModel(): LedgerViewModel {
        val viewModel = LedgerViewModel(
            ledger = ledger,
            drafts = drafts,
            deleteEntry = DeleteEntryUseCase(ledger),
            restoreEntry = RestoreEntryUseCase(ledger, NoOpBudgetAlertTrigger),
            observePending = ObservePendingUseCase(pending),
            clock = Clock { FIXED_NOW },
            merchantEntries = merchantEntries,
            merchantCategories = { flowOf(kinds) },
        )
        CoroutineScope(dispatcher).launch { viewModel.state.collect {} }
        dispatcher.scheduler.advanceUntilIdle()
        return viewModel
    }

    @Test
    fun noFilter_isThePlainList() = runTest(dispatcher) {
        ledger.entries[LedgerType.DEBIT] = listOf(row("plain"))
        val vm = viewModel()

        assertThat(vm.entries.asSnapshot().map { it.id }).containsExactly("plain")
        assertThat(merchantEntries.categoryRequests).isEmpty()
        assertThat(vm.state.value.merchantCategories).isEqualTo(kinds)
    }

    @Test
    fun aFilter_listsTheFilteredRead_forTheChosenCategories() = runTest(dispatcher) {
        ledger.entries[LedgerType.DEBIT] = listOf(row("plain"))
        merchantEntries.byMerchantCategory.value = mapOf(
            LedgerType.DEBIT to mapOf("g" to listOf(row("grocer")), "f" to listOf(row("pump"))),
        )
        val vm = viewModel()

        vm.onEvent(LedgerEvent.FilterToggled("g"))
        dispatcher.scheduler.advanceUntilIdle()

        assertThat(vm.entries.asSnapshot().map { it.id }).containsExactly("grocer")
        assertThat(merchantEntries.categoryRequests.last()).containsExactly("g")
        assertThat(vm.state.value.merchantCategoryFilter).containsExactly("g")
    }

    /** Merchant categories belong to neither book: switching keeps the filter, and reads the new book. */
    @Test
    fun aFilter_outlivesABookSwitch_andReadsThatBook() = runTest(dispatcher) {
        merchantEntries.byMerchantCategory.value = mapOf(
            LedgerType.DEBIT to mapOf("g" to listOf(row("grocer"))),
            LedgerType.CREDIT to mapOf("g" to listOf(row("refund", LedgerType.CREDIT))),
        )
        val vm = viewModel()
        vm.onEvent(LedgerEvent.FilterToggled("g"))
        vm.onEvent(LedgerEvent.LedgerSelected(LedgerType.CREDIT))
        dispatcher.scheduler.advanceUntilIdle()

        assertThat(vm.state.value.merchantCategoryFilter).containsExactly("g")
        assertThat(vm.entries.asSnapshot().map { it.id }).containsExactly("refund")
    }

    /** Drafts are not ledger entries and mostly have no filed merchant: hidden while filtering. */
    @Test
    fun aFilter_hidesTheUnsavedRows_andClearingBringsThemBack() = runTest(dispatcher) {
        drafts.seed("draft-1", LedgerType.DEBIT, "{}", summary = DraftSummaryFields(2_000L))
        val vm = viewModel()
        assertThat(vm.state.value.unsaved).hasSize(1)

        vm.onEvent(LedgerEvent.FilterToggled(MerchantCategoryBreakdown.UNCATEGORISED_ID))
        dispatcher.scheduler.advanceUntilIdle()
        assertThat(vm.state.value.unsaved).isEmpty()

        vm.onEvent(LedgerEvent.FilterCleared)
        dispatcher.scheduler.advanceUntilIdle()
        assertThat(vm.state.value.unsaved).hasSize(1)
        assertThat(vm.state.value.merchantCategoryFilter).isEmpty()
    }

    @Test
    fun togglingTwice_unchooses_andThePickerOpensAndCloses() = runTest(dispatcher) {
        val vm = viewModel()

        vm.onEvent(LedgerEvent.FilterOpened)
        vm.onEvent(LedgerEvent.FilterToggled("f"))
        vm.onEvent(LedgerEvent.FilterToggled("f"))
        dispatcher.scheduler.advanceUntilIdle()
        assertThat(vm.state.value.choosingFilter).isTrue()
        assertThat(vm.state.value.merchantCategoryFilter).isEmpty()

        vm.onEvent(LedgerEvent.FilterClosed)
        dispatcher.scheduler.advanceUntilIdle()
        assertThat(vm.state.value.choosingFilter).isFalse()
    }

    private companion object {
        const val FIXED_NOW = 1_755_540_000_000L
        val TODAY: Int = (FIXED_NOW / 86_400_000L).toInt()
    }
}
