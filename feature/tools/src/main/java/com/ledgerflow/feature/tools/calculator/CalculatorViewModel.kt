package com.ledgerflow.feature.tools.calculator

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ledgerflow.core.designsystem.format.MoneyFormat
import com.ledgerflow.core.domain.ledger.LedgerRepository
import com.ledgerflow.core.model.CurrencyExponent
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * The calculator (V2-PLAN §4.4; owner's mockup of 2026-11-10). The answer is
 * in the base currency, and "Use as amount" hands it to a new manual entry.
 * Nothing here writes anything: the entry form does, once the user saves it.
 */
@Immutable
public data class CalculatorUiState(
    val currency: String = DEFAULT_CURRENCY,
    val working: String = "",
    /** The answer so far, formatted; "₹0.00" on an empty line. */
    val result: String = "",
    /** Why there is no answer: a division by zero, or too large a number. */
    val problem: String? = null,
    /** The answer in minor units when it can be an entry's amount (above zero). */
    val usableMinor: Long? = null,
) {
    internal companion object {
        const val DEFAULT_CURRENCY = "INR"
    }
}

public sealed interface CalculatorEvent {
    public data class KeyPressed(val key: CalcKey) : CalculatorEvent
}

@HiltViewModel
public class CalculatorViewModel @Inject constructor(
    private val ledger: LedgerRepository,
) : ViewModel() {

    private var expression = Expression()
    private val _state = MutableStateFlow(CalculatorUiState())
    public val state: StateFlow<CalculatorUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            val currency = ledger.baseCurrency() ?: CalculatorUiState.DEFAULT_CURRENCY
            _state.update { it.copy(currency = currency) }
            publish()
        }
        publish()
    }

    public fun onEvent(event: CalculatorEvent) {
        when (event) {
            is CalculatorEvent.KeyPressed -> {
                expression = Calculator.press(expression, event.key, exponent())
                publish()
            }
        }
    }

    private fun exponent(): Int = CurrencyExponent.of(_state.value.currency)

    private fun publish() {
        val currency = _state.value.currency
        val answer = Calculator.evaluate(expression, exponent())
        _state.update {
            it.copy(
                working = expression.working,
                result = when (answer) {
                    is CalcResult.Value -> MoneyFormat.symbolised(answer.amount.minor, currency)
                    CalcResult.Empty, CalcResult.DivideByZero, CalcResult.TooLarge ->
                        MoneyFormat.symbolised(0, currency)
                },
                problem = when (answer) {
                    CalcResult.DivideByZero -> "Can't divide by zero"
                    CalcResult.TooLarge -> "That number is too large"
                    CalcResult.Empty, is CalcResult.Value -> null
                },
                usableMinor = (answer as? CalcResult.Value)?.amount?.minor?.takeIf { minor -> minor > 0L },
            )
        }
    }
}
