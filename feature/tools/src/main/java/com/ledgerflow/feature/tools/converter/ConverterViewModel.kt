package com.ledgerflow.feature.tools.converter

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ledgerflow.core.designsystem.format.DisplayDates
import com.ledgerflow.core.designsystem.format.DisplayLocale
import com.ledgerflow.core.designsystem.format.MoneyFormat
import com.ledgerflow.core.designsystem.format.RateFormat
import com.ledgerflow.core.domain.ledger.LedgerRepository
import com.ledgerflow.core.model.CurrencyExponent
import com.ledgerflow.core.model.Money
import com.ledgerflow.core.rates.FetchFailure
import com.ledgerflow.core.rates.RateTable
import com.ledgerflow.core.rates.RatesFetch
import com.ledgerflow.core.rates.RatesSource
import com.ledgerflow.core.ui.picker.LfPickerOption
import dagger.hilt.android.lifecycle.HiltViewModel
import java.time.LocalDate
import java.util.Currency
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

public enum class ConverterSide { FROM, TO }

public enum class RateChoice { ECB, TYPED }

/**
 * The converter (ADR-0036; the owner's mockup of 2026-11-10). What it shows is
 * for reference only: nothing here reaches an entry (§6), and there is no
 * "Use as amount".
 */
@Immutable
public data class ConverterUiState(
    val loading: Boolean = true,
    val from: String = "USD",
    val to: String = "INR",
    val amountText: String = "",
    /** The converted amount, in [to]; empty until there is an amount and a rate. */
    val result: String = "",
    val choice: RateChoice = RateChoice.TYPED,
    /** True when the cached table has both currencies, so its row can be chosen. */
    val ecbAvailable: Boolean = false,
    val ecbRate: String? = null,
    /** "Indicative · ECB reference rate of 9 Nov 2026", or why there is none. */
    val ecbCaption: String = "",
    val typedRateText: String = "",
    /** "1 USD = 93.9368 INR" for the rate in use. */
    val rateLine: String? = null,
    val fetching: Boolean = false,
    val message: String? = null,
    val openPicker: ConverterSide? = null,
    val pickerQuery: String = "",
    val currencyOptions: List<LfPickerOption> = emptyList(),
)

public sealed interface ConverterEvent {
    public data class AmountChanged(val text: String) : ConverterEvent

    public data class PickerOpened(val side: ConverterSide) : ConverterEvent

    public data class PickerQueryChanged(val text: String) : ConverterEvent

    public data class CurrencyPicked(val code: String) : ConverterEvent

    public data object PickerDismissed : ConverterEvent

    public data object SwapClicked : ConverterEvent

    public data object FetchClicked : ConverterEvent

    public data class ChoicePicked(val choice: RateChoice) : ConverterEvent

    public data class TypedRateChanged(val text: String) : ConverterEvent

    public data object MessageShown : ConverterEvent
}

/**
 * **A fetch runs only on "Get today's rates"** (ADR-0036 §3): opening the
 * screen reads the cached table and nothing else. **A typed rate always
 * works**, offline included, and is forgotten when the screen goes (owner,
 * 2026-11-10): it is never written anywhere.
 */
@HiltViewModel
public class ConverterViewModel @Inject constructor(
    private val rates: RatesSource,
    private val ledger: LedgerRepository,
) : ViewModel() {

    private var table: RateTable? = null

    /** The user picked "Your rate" while the ECB's could have been used. */
    private var prefersTyped = false

    private val allCurrencies: List<LfPickerOption> by lazy { currencyOptions() }

    private val _state = MutableStateFlow(ConverterUiState())
    public val state: StateFlow<ConverterUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            val base = ledger.baseCurrency() ?: DEFAULT_BASE
            table = rates.cached()
            _state.update {
                it.copy(loading = false, from = if (base == "USD") "EUR" else "USD", to = base)
            }
            refreshFigures()
        }
    }

    public fun onEvent(event: ConverterEvent) {
        when (event) {
            is ConverterEvent.AmountChanged -> change { it.copy(amountText = event.text) }
            is ConverterEvent.PickerOpened -> _state.update {
                it.copy(openPicker = event.side, pickerQuery = "", currencyOptions = allCurrencies)
            }
            is ConverterEvent.PickerQueryChanged -> _state.update {
                it.copy(pickerQuery = event.text, currencyOptions = filtered(event.text))
            }
            is ConverterEvent.CurrencyPicked -> pick(event.code)
            ConverterEvent.PickerDismissed -> _state.update { it.copy(openPicker = null) }
            ConverterEvent.SwapClicked -> swap()
            ConverterEvent.FetchClicked -> fetch()
            is ConverterEvent.ChoicePicked -> {
                prefersTyped = event.choice == RateChoice.TYPED
                refreshFigures()
            }
            is ConverterEvent.TypedRateChanged -> change { it.copy(typedRateText = event.text) }
            ConverterEvent.MessageShown -> _state.update { it.copy(message = null) }
        }
    }

    private fun change(transform: (ConverterUiState) -> ConverterUiState) {
        _state.update(transform)
        refreshFigures()
    }

    private fun pick(code: String) {
        val side = _state.value.openPicker ?: return
        _state.update {
            val before = if (side == ConverterSide.FROM) it.from else it.to
            // A typed rate is for one pair of currencies; a new pair starts without one.
            val typed = if (code == before) it.typedRateText else ""
            when (side) {
                ConverterSide.FROM -> it.copy(from = code, openPicker = null, typedRateText = typed)
                ConverterSide.TO -> it.copy(to = code, openPicker = null, typedRateText = typed)
            }
        }
        refreshFigures()
    }

    /** The two sides change places; a typed rate turns over with them. */
    private fun swap() {
        _state.update { current ->
            val typed = RateFormat.parse(current.typedRateText)
            val inverted = typed?.let { Money(Money.RATE_SCALE).converted(Money.RATE_SCALE, it, 0, 0)?.minor }
            current.copy(
                from = current.to,
                to = current.from,
                typedRateText = inverted?.takeIf { it > 0L }?.let(RateFormat::plain) ?: "",
                amountText = "",
            )
        }
        refreshFigures()
    }

    private fun fetch() {
        if (_state.value.fetching) return
        _state.update { it.copy(fetching = true) }
        viewModelScope.launch {
            when (val answer = rates.fetch()) {
                is RatesFetch.Fetched -> {
                    table = answer.table
                    prefersTyped = false
                    _state.update { it.copy(fetching = false, message = "ECB rates of ${dateOf(answer.table)}") }
                }
                is RatesFetch.Failed -> _state.update {
                    it.copy(fetching = false, message = failureMessage(answer.reason, table != null))
                }
            }
            refreshFigures()
        }
    }

    private fun refreshFigures() {
        _state.update { current ->
            val known = table
            val ecbAvailable = known != null && known.has(current.from) && known.has(current.to)
            val choice = if (ecbAvailable && !prefersTyped) RateChoice.ECB else RateChoice.TYPED
            val ecbMicro = if (ecbAvailable) known?.rateMicro(current.from, current.to) else null
            val typedMicro = RateFormat.parse(current.typedRateText)
            val amount = Money(MoneyFormat.parse(current.amountText, current.from))
            val converted = when (choice) {
                RateChoice.ECB -> known?.convert(amount, current.from, current.to)
                RateChoice.TYPED -> typedMicro?.let {
                    amount.converted(
                        rate = it,
                        per = Money.RATE_SCALE,
                        fromExponent = CurrencyExponent.of(current.from),
                        toExponent = CurrencyExponent.of(current.to),
                    )
                }
            }
            val shownMicro = if (choice == RateChoice.ECB) ecbMicro else typedMicro
            current.copy(
                choice = choice,
                ecbAvailable = ecbAvailable,
                ecbRate = ecbMicro?.let(RateFormat::display),
                ecbCaption = ecbCaption(known, current.from, current.to),
                rateLine = shownMicro?.let { "1 ${current.from} = ${RateFormat.display(it)} ${current.to}" },
                result = converted?.takeIf { amount.isPositive }?.let { MoneyFormat.symbolised(it.minor, current.to) }
                    .orEmpty(),
            )
        }
    }

    private fun ecbCaption(known: RateTable?, from: String, to: String): String {
        val missing = listOf(from, to).firstOrNull { known != null && !known.has(it) }
        return when {
            known == null -> "No rates yet. Get today's rates, or type your own."
            missing != null -> "The ECB doesn't publish $missing. Type your own rate."
            else -> "Indicative · ECB reference rate of ${dateOf(known)}"
        }
    }

    private fun filtered(query: String): List<LfPickerOption> {
        val needle = query.trim()
        return if (needle.isEmpty()) {
            allCurrencies
        } else {
            allCurrencies.filter { it.label.contains(needle, ignoreCase = true) }
        }
    }

    internal companion object {
        const val DEFAULT_BASE = "INR"

        /** The ECB's date, not the tap's: a table fetched on a Sunday is Friday's (§5). */
        fun dateOf(table: RateTable): String = DisplayDates.medium(LocalDate.ofEpochDay(table.date.toLong()))

        fun failureMessage(reason: FetchFailure, hasCached: Boolean): String {
            val first = when (reason) {
                FetchFailure.OFFLINE -> "Couldn't reach the ECB."
                FetchFailure.REFUSED -> "The ECB didn't send its rates."
                FetchFailure.TOO_LARGE, FetchFailure.MALFORMED -> "The ECB's file couldn't be read, so it wasn't used."
            }
            return if (hasCached) "$first Your last rates still work." else "$first You can type your own rate."
        }

        /** Every real currency the phone knows, by code: "AED · UAE Dirham". Funds and metals have no minor unit and are left out. */
        fun currencyOptions(): List<LfPickerOption> {
            val locale = DisplayLocale.current()
            return Currency.getAvailableCurrencies()
                .filter { it.defaultFractionDigits >= 0 }
                .sortedBy { it.currencyCode }
                .map { LfPickerOption(it.currencyCode, "${it.currencyCode} · ${it.getDisplayName(locale)}") }
        }
    }
}
