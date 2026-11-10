package com.ledgerflow.feature.tools.recurring

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ledgerflow.core.domain.ledger.LedgerRepository
import com.ledgerflow.core.domain.recurring.RecurringItem
import com.ledgerflow.core.domain.recurring.RecurringRepository
import com.ledgerflow.core.domain.recurring.RecurringResult
import com.ledgerflow.core.domain.taxonomy.CategoryRepository
import com.ledgerflow.core.domain.taxonomy.MerchantRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** One recurring item's page (V2-7; mockup 2): its schedule, and what each recent due date turned into. */
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
public class RecurringItemViewModel @Inject constructor(
    private val recurring: RecurringRepository,
    private val merchants: MerchantRepository,
    private val categories: CategoryRepository,
    ledger: LedgerRepository,
    savedStateHandle: SavedStateHandle,
) : ViewModel() {

    private val itemId: String = savedStateHandle.get<String>(ITEM_ID_ARG).orEmpty()
    private val local = MutableStateFlow(RecurringItemUiState())

    private val item = recurring.observeItems().map { items -> items.firstOrNull { it.id == itemId } }

    /** The merchant's and category's names, for the item's facts. */
    private val names = item.mapLatest { it?.let { names(it) }.orEmpty() }

    public val state: StateFlow<RecurringItemUiState> = combine(
        item,
        names,
        recurring.observeOccurrences(itemId, RECENT),
        flow { emit(ledger.baseCurrency() ?: DEFAULT_CURRENCY) },
        local,
    ) { found, named, log, currency, local ->
        if (found == null) return@combine local.copy(loading = false, gone = true)
        local.copy(
            loading = false,
            gone = false,
            name = found.name,
            amount = RecurringWords.amount(found, currency),
            subtitle = RecurringWords.book(found.ledger) + " · " +
                RecurringWords.cadence(found.cadence, found.everyDays),
            facts = RecurringWords.facts(found, named),
            recent = log.map { OccurrenceRowUi(RecurringWords.day(it.dueDate), RecurringWords.outcome(it)) },
            paused = found.paused,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS), RecurringItemUiState())

    public fun onEvent(event: RecurringItemEvent) {
        when (event) {
            RecurringItemEvent.OptionsClicked -> local.update { it.copy(optionsOpen = true) }
            RecurringItemEvent.OptionsDismissed -> local.update { it.copy(optionsOpen = false) }
            RecurringItemEvent.PauseToggled -> togglePause()
            RecurringItemEvent.DeleteClicked -> local.update { it.copy(optionsOpen = false, confirmingDelete = true) }
            RecurringItemEvent.DeleteCancelled -> local.update { it.copy(confirmingDelete = false) }
            RecurringItemEvent.DeleteConfirmed -> delete()
            RecurringItemEvent.MessageShown -> local.update { it.copy(message = null) }
        }
    }

    private fun togglePause() {
        val pausing = !state.value.paused
        local.update { it.copy(optionsOpen = false) }
        viewModelScope.launch {
            val message = when (recurring.setPaused(itemId, pausing)) {
                is RecurringResult.Success -> if (pausing) "Paused" else "Resumed"
                is RecurringResult.Failure -> "Could not change it"
            }
            local.update { it.copy(message = message) }
        }
    }

    private fun delete() {
        local.update { it.copy(confirmingDelete = false) }
        viewModelScope.launch {
            // On success the item leaves the list and the page closes itself.
            if (recurring.delete(itemId) is RecurringResult.Failure) {
                local.update { it.copy(message = "Could not delete") }
            }
        }
    }

    private suspend fun names(item: RecurringItem): List<String> = listOfNotNull(
        item.merchantId?.let { merchants.find(it)?.canonicalName },
        (item.subcategoryId ?: item.categoryId)?.let { categories.find(it)?.name },
    )

    public companion object {
        /** The route's argument: `Destination.RecurringItem(itemId)`. */
        public const val ITEM_ID_ARG: String = "itemId"
        private const val RECENT = 12
        private const val DEFAULT_CURRENCY = "INR"
        private const val STOP_TIMEOUT_MILLIS = 5_000L
    }
}
