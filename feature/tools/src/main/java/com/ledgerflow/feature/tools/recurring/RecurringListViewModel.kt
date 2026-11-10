package com.ledgerflow.feature.tools.recurring

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ledgerflow.core.designsystem.format.MoneyFormat
import com.ledgerflow.core.domain.ledger.LedgerRepository
import com.ledgerflow.core.domain.recurring.RecurringRepository
import com.ledgerflow.core.domain.usecase.GetRecurringSuggestionsUseCase
import com.ledgerflow.core.model.LedgerType
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * The Recurring page (V2-7; mockup 1): suggestions from detection, then each
 * book's items in its own panel. Suggestions are re-read whenever the items
 * or the dismissals change, so adding or dismissing one takes it off the list.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
public class RecurringListViewModel @Inject constructor(
    private val recurring: RecurringRepository,
    suggestions: GetRecurringSuggestionsUseCase,
    ledger: LedgerRepository,
) : ViewModel() {

    private val suggested = combine(recurring.observeItems(), recurring.observeDismissed()) { _, _ -> }
        .mapLatest { suggestions() }

    public val state: StateFlow<RecurringListUiState> = combine(
        recurring.observeItems(),
        suggested,
        flow { emit(ledger.baseCurrency() ?: DEFAULT_CURRENCY) },
    ) { items, offers, currency ->
        val rows = items.map { item ->
            item.ledger to RecurringRowUi(
                id = item.id,
                name = item.name,
                amount = RecurringWords.amount(item, currency),
                caption = RecurringWords.caption(item),
                paused = item.paused,
            )
        }
        RecurringListUiState(
            loading = false,
            suggestions = offers.map { offer ->
                SuggestionRowUi(
                    merchantId = offer.merchantId,
                    ledger = offer.ledger,
                    name = offer.name,
                    amount = MoneyFormat.symbolised(offer.amount.minor, currency),
                    caption = RecurringWords.suggestion(offer),
                )
            },
            expense = rows.filter { it.first == LedgerType.DEBIT }.map { it.second },
            income = rows.filter { it.first == LedgerType.CREDIT }.map { it.second },
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS), RecurringListUiState())

    public fun onEvent(event: RecurringListEvent) {
        when (event) {
            is RecurringListEvent.NotRecurring -> viewModelScope.launch {
                recurring.dismiss(event.merchantId, event.ledger)
            }
        }
    }

    private companion object {
        const val DEFAULT_CURRENCY = "INR"
        const val STOP_TIMEOUT_MILLIS = 5_000L
    }
}
