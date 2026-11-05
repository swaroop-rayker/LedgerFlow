package com.ledgerflow.feature.ledger

import androidx.lifecycle.SavedStateHandle
import com.google.common.truth.Truth.assertThat
import com.ledgerflow.core.domain.ledger.MerchantTotal
import com.ledgerflow.core.model.LedgerListItem
import com.ledgerflow.core.model.LedgerType
import com.ledgerflow.core.model.Money
import com.ledgerflow.core.testing.ledger.FakeLedgerRepository
import com.ledgerflow.core.testing.ledger.FakeMerchantEntriesRepository
import androidx.paging.testing.asSnapshot
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test

/**
 * The merchant entries page (V2-6): it shows the book, merchant and window it
 * was opened with, and its total counts that merchant's entries in that window
 * and book only (Law 2).
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class MerchantEntriesViewModelTest {

    private val dispatcher = StandardTestDispatcher()
    private val ledger = FakeLedgerRepository()
    private val pages = FakeMerchantEntriesRepository()

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() = Dispatchers.resetMain()

    private fun entry(id: String, day: Int, minor: Long, book: LedgerType = LedgerType.DEBIT) = LedgerListItem(
        id = id, ledger = book, amount = Money(minor), currency = "INR", occurredAt = 0L, localDate = day,
        categoryName = null, categoryColorArgb = null, merchantName = "Swiggy", note = null,
    )

    private fun viewModel(
        book: String = "DEBIT",
        filtered: Boolean = false,
    ): Pair<MerchantEntriesViewModel, CoroutineScope> {
        val vm = MerchantEntriesViewModel(
            pages,
            ledger,
            SavedStateHandle(
                mapOf(
                    MerchantEntriesViewModel.LEDGER_ARG to book,
                    MerchantEntriesViewModel.MERCHANT_ID_ARG to "swiggy",
                    MerchantEntriesViewModel.MERCHANT_NAME_ARG to "Swiggy",
                    MerchantEntriesViewModel.FROM_ARG to FROM,
                    MerchantEntriesViewModel.TO_ARG to TO,
                    MerchantEntriesViewModel.FILTERED_ARG to filtered,
                ),
            ),
        )
        val scope = CoroutineScope(dispatcher)
        scope.launch { vm.state.collect {} }
        dispatcher.scheduler.advanceUntilIdle()
        return vm to scope
    }

    @Test
    fun thePage_isWhatTheRouteNamed() {
        val (vm, scope) = viewModel(filtered = true)
        val state = vm.state.value

        assertThat(state.ledger).isEqualTo(LedgerType.DEBIT)
        assertThat(state.merchantName).isEqualTo("Swiggy")
        assertThat(state.from to state.to).isEqualTo(FROM to TO)
        assertThat(state.analyticsFiltered).isTrue()
        scope.cancel()
    }

    /** The tapped figure: this merchant's entries inside the window, nothing either side of it. */
    @Test
    fun theTotal_countsTheWindowOnly() {
        pages.entries.value = mapOf(
            LedgerType.DEBIT to mapOf(
                "swiggy" to listOf(entry("a", FROM, 400), entry("b", TO, 600), entry("c", TO + 1, 9_999)),
            ),
        )
        val (vm, scope) = viewModel()

        assertThat(vm.state.value.total).isEqualTo(MerchantTotal(Money(1_000), 2))
        scope.cancel()
    }

    /** Law 2: the income book opened is the income book read. */
    @Test
    fun theTotal_readsTheBookItWasOpenedOn() {
        pages.entries.value = mapOf(
            LedgerType.DEBIT to mapOf("swiggy" to listOf(entry("a", FROM, 400))),
            LedgerType.CREDIT to mapOf("swiggy" to listOf(entry("r", FROM, 50, LedgerType.CREDIT))),
        )
        val (vm, scope) = viewModel(book = "CREDIT")

        assertThat(vm.state.value.ledger).isEqualTo(LedgerType.CREDIT)
        assertThat(vm.state.value.total).isEqualTo(MerchantTotal(Money(50), 1))
        scope.cancel()
    }

    /** The title names the merchant, so the rows do not repeat it. */
    @Test
    fun theRows_leaveTheMerchantToTheTitle() = kotlinx.coroutines.test.runTest(dispatcher) {
        pages.entries.value = mapOf(LedgerType.DEBIT to mapOf("swiggy" to listOf(entry("a", FROM, 400))))
        val (vm, scope) = viewModel()

        val rows = vm.entries.asSnapshot()
        assertThat(rows.map { it.merchantName }).containsExactly(null)
        scope.cancel()
    }

    private companion object {
        const val FROM = 20_700
        const val TO = 20_729
    }
}
