package com.ledgerflow.feature.tools.converter

import com.google.common.truth.Truth.assertThat
import com.ledgerflow.core.rates.FetchFailure
import com.ledgerflow.core.rates.RateTable
import com.ledgerflow.core.rates.RatesFetch
import com.ledgerflow.core.rates.RatesSource
import com.ledgerflow.core.testing.ledger.FakeLedgerRepository
import java.time.LocalDate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test

/**
 * The converter (ADR-0036): it opens on the cached table and **fetches only on
 * the tap** (§3); a typed rate always works (§1, decision c); a currency the
 * ECB lacks takes a typed rate only; a failed fetch keeps the last table.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ConverterViewModelTest {

    private val dispatcher = UnconfinedTestDispatcher()

    /** The ECB's table of 9 Nov 2026, the three currencies these tests use. */
    private val table = RateTable(
        date = LocalDate.of(2026, 11, 9).toEpochDay().toInt(),
        perEuroMicro = mapOf("USD" to 1_119_300L, "INR" to 105_143_500L, "JPY" to 176_790_000L),
    )

    private class FakeRates(var cached: RateTable?, var answer: RatesFetch) : RatesSource {
        var fetches = 0

        override suspend fun cached(): RateTable? = cached

        override suspend fun fetch(): RatesFetch {
            fetches++
            (answer as? RatesFetch.Fetched)?.let { cached = it.table }
            return answer
        }
    }

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() = Dispatchers.resetMain()

    private fun viewModel(rates: RatesSource) = ConverterViewModel(rates, FakeLedgerRepository())

    @Test
    fun opening_readsTheCachedTable_andFetchesNothing() = runTest(dispatcher) {
        val rates = FakeRates(cached = table, answer = RatesFetch.Failed(FetchFailure.OFFLINE))
        val vm = viewModel(rates)

        vm.onEvent(ConverterEvent.AmountChanged("120.00"))

        val state = vm.state.value
        assertThat(rates.fetches).isEqualTo(0)
        assertThat(state.from).isEqualTo("USD")
        assertThat(state.to).isEqualTo("INR")
        assertThat(state.choice).isEqualTo(RateChoice.ECB)
        assertThat(state.ecbRate).isEqualTo("93.9368")
        assertThat(state.ecbCaption).startsWith("Indicative · ECB reference rate of ")
        assertThat(state.rateLine).isEqualTo("1 USD = 93.9368 INR")
        // Rounded once from the cross rate, not through the shown one.
        assertThat(state.result).contains("11,272.42")
    }

    @Test
    fun withNoTable_aTypedRateStillConverts() = runTest(dispatcher) {
        val rates = FakeRates(cached = null, answer = RatesFetch.Failed(FetchFailure.OFFLINE))
        val vm = viewModel(rates)

        vm.onEvent(ConverterEvent.AmountChanged("120.00"))
        vm.onEvent(ConverterEvent.TypedRateChanged("88.027"))

        val state = vm.state.value
        assertThat(state.ecbAvailable).isFalse()
        assertThat(state.choice).isEqualTo(RateChoice.TYPED)
        assertThat(state.ecbCaption).isEqualTo("No rates yet. Get today's rates, or type your own.")
        assertThat(state.result).contains("10,563.24")
        assertThat(rates.fetches).isEqualTo(0)
    }

    @Test
    fun aCurrencyTheEcbLacks_takesATypedRateOnly() = runTest(dispatcher) {
        val vm = viewModel(FakeRates(cached = table, answer = RatesFetch.Failed(FetchFailure.OFFLINE)))

        vm.onEvent(ConverterEvent.PickerOpened(ConverterSide.FROM))
        vm.onEvent(ConverterEvent.CurrencyPicked("AED"))
        vm.onEvent(ConverterEvent.ChoicePicked(RateChoice.ECB))

        val state = vm.state.value
        assertThat(state.from).isEqualTo("AED")
        assertThat(state.ecbAvailable).isFalse()
        assertThat(state.choice).isEqualTo(RateChoice.TYPED)
        assertThat(state.ecbCaption).isEqualTo("The ECB doesn't publish AED. Type your own rate.")
    }

    @Test
    fun choosingYourRate_overTheEcbs_usesYours() = runTest(dispatcher) {
        val vm = viewModel(FakeRates(cached = table, answer = RatesFetch.Failed(FetchFailure.OFFLINE)))

        vm.onEvent(ConverterEvent.AmountChanged("120.00"))
        vm.onEvent(ConverterEvent.ChoicePicked(RateChoice.TYPED))
        vm.onEvent(ConverterEvent.TypedRateChanged("88.027"))

        assertThat(vm.state.value.choice).isEqualTo(RateChoice.TYPED)
        assertThat(vm.state.value.result).contains("10,563.24")
        assertThat(vm.state.value.rateLine).isEqualTo("1 USD = 88.0270 INR")
    }

    @Test
    fun theTap_fetches_andUsesTheNewTable() = runTest(dispatcher) {
        val rates = FakeRates(cached = null, answer = RatesFetch.Fetched(table))
        val vm = viewModel(rates)

        vm.onEvent(ConverterEvent.FetchClicked)

        assertThat(rates.fetches).isEqualTo(1)
        assertThat(vm.state.value.ecbAvailable).isTrue()
        assertThat(vm.state.value.choice).isEqualTo(RateChoice.ECB)
        assertThat(vm.state.value.message).startsWith("ECB rates of ")
    }

    @Test
    fun aFailedFetch_saysSo_andTheLastTableStays() = runTest(dispatcher) {
        val vm = viewModel(FakeRates(cached = table, answer = RatesFetch.Failed(FetchFailure.OFFLINE)))

        vm.onEvent(ConverterEvent.FetchClicked)

        assertThat(vm.state.value.message).isEqualTo("Couldn't reach the ECB. Your last rates still work.")
        assertThat(vm.state.value.ecbAvailable).isTrue()
        assertThat(vm.state.value.fetching).isFalse()
    }

    @Test
    fun swap_turnsTheTypedRateOver() = runTest(dispatcher) {
        val vm = viewModel(FakeRates(cached = null, answer = RatesFetch.Failed(FetchFailure.OFFLINE)))
        vm.onEvent(ConverterEvent.TypedRateChanged("80"))

        vm.onEvent(ConverterEvent.SwapClicked)

        assertThat(vm.state.value.from).isEqualTo("INR")
        assertThat(vm.state.value.to).isEqualTo("USD")
        assertThat(vm.state.value.typedRateText).isEqualTo("0.0125")
    }

    @Test
    fun aNewPairOfCurrencies_startsWithoutATypedRate() = runTest(dispatcher) {
        val vm = viewModel(FakeRates(cached = null, answer = RatesFetch.Failed(FetchFailure.OFFLINE)))
        vm.onEvent(ConverterEvent.TypedRateChanged("80"))

        vm.onEvent(ConverterEvent.PickerOpened(ConverterSide.FROM))
        vm.onEvent(ConverterEvent.CurrencyPicked("JPY"))

        assertThat(vm.state.value.typedRateText).isEmpty()
    }

    @Test
    fun theCurrencyPicker_listsRealCurrencies_andSearches() = runTest(dispatcher) {
        val vm = viewModel(FakeRates(cached = null, answer = RatesFetch.Failed(FetchFailure.OFFLINE)))

        vm.onEvent(ConverterEvent.PickerOpened(ConverterSide.TO))
        val all = vm.state.value.currencyOptions.map { it.id }
        vm.onEvent(ConverterEvent.PickerQueryChanged("dirham"))

        assertThat(all).containsAtLeast("AED", "EUR", "INR", "JPY", "USD")
        assertThat(all).doesNotContain("XAU")
        assertThat(vm.state.value.currencyOptions.map { it.id }).contains("AED")
    }
}
