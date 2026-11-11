package com.ledgerflow.feature.tools.calculator

import com.google.common.truth.Truth.assertThat
import com.ledgerflow.core.testing.ledger.FakeLedgerRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test

/** The calculator's screen state: the answer in the base currency, and when it can be an entry's amount. */
@OptIn(ExperimentalCoroutinesApi::class)
class CalculatorViewModelTest {

    private val dispatcher = UnconfinedTestDispatcher()

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() = Dispatchers.resetMain()

    private fun CalculatorViewModel.press(vararg keys: CalcKey) =
        keys.forEach { onEvent(CalculatorEvent.KeyPressed(it)) }

    @Test
    fun theAnswer_isInTheBaseCurrency_andUsable() = runTest(dispatcher) {
        val vm = CalculatorViewModel(FakeLedgerRepository())

        vm.press(CalcKey.Digit(1), CalcKey.Digit(0), CalcKey.Digit(0), CalcKey.Operator(CalcOp.PLUS))
        vm.press(CalcKey.Digit(2), CalcKey.Digit(0), CalcKey.Operator(CalcOp.TIMES), CalcKey.Digit(3))

        val state = vm.state.value
        assertThat(state.working).isEqualTo("100 + 20 × 3")
        assertThat(state.result).contains("160.00")
        assertThat(state.usableMinor).isEqualTo(160_00L)
        assertThat(state.problem).isNull()
    }

    @Test
    fun nothingAboveZero_isNotAnAmount() = runTest(dispatcher) {
        val vm = CalculatorViewModel(FakeLedgerRepository())
        assertThat(vm.state.value.usableMinor).isNull()

        vm.press(CalcKey.Digit(1), CalcKey.Operator(CalcOp.MINUS), CalcKey.Digit(3))

        assertThat(vm.state.value.usableMinor).isNull()
    }

    @Test
    fun aDivisionByZero_isExplained_andNotUsable() = runTest(dispatcher) {
        val vm = CalculatorViewModel(FakeLedgerRepository())

        vm.press(CalcKey.Digit(5), CalcKey.Operator(CalcOp.DIVIDE), CalcKey.Digit(0))

        assertThat(vm.state.value.problem).isEqualTo("Can't divide by zero")
        assertThat(vm.state.value.usableMinor).isNull()
    }

    @Test
    fun aZeroDecimalBase_roundsToTheWholeUnit() = runTest(dispatcher) {
        val vm = CalculatorViewModel(FakeLedgerRepository().apply { installBaseCurrency = "JPY" })

        vm.press(CalcKey.Digit(1), CalcKey.Digit(0), CalcKey.Operator(CalcOp.DIVIDE), CalcKey.Digit(4))

        assertThat(vm.state.value.usableMinor).isEqualTo(3L)
    }
}
