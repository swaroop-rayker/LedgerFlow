package com.ledgerflow.feature.tools.goals

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ledgerflow.core.domain.ledger.LedgerRepository
import com.ledgerflow.core.domain.usecase.ObserveGoalsUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.stateIn

/**
 * The Savings goals page (V2-PLAN §4.1): goals still being saved for, then
 * the ones reached. Observed, so an approval into a linked category moves a
 * bar while the page is open.
 */
@HiltViewModel
public class GoalsViewModel @Inject constructor(
    observeGoals: ObserveGoalsUseCase,
    ledger: LedgerRepository,
) : ViewModel() {

    public val state: StateFlow<GoalsUiState> = combine(
        observeGoals(),
        flow { emit(ledger.baseCurrency() ?: DEFAULT_CURRENCY) },
    ) { overview, currency ->
        val (reached, active) = overview.goals.partition { it.isReached }
        GoalsUiState(
            loading = false,
            active = active.map { it.toRow(overview.today, currency) },
            reached = reached.map { it.toRow(overview.today, currency) },
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS), GoalsUiState())

    private companion object {
        const val DEFAULT_CURRENCY = "INR"
        const val STOP_TIMEOUT_MILLIS = 5_000L
    }
}
