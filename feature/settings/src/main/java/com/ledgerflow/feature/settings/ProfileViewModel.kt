package com.ledgerflow.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ledgerflow.core.domain.usecase.ObserveGoalCountUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn

/**
 * Profile's one live figure: how many savings goals there are, beside the
 * Savings goals row (V2-7). Null until read, so the row draws without a count
 * rather than with a wrong one.
 */
@HiltViewModel
public class ProfileViewModel @Inject constructor(
    observeGoalCount: ObserveGoalCountUseCase,
) : ViewModel() {

    public val goalCount: StateFlow<Int?> = observeGoalCount()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS), null)

    private companion object {
        const val STOP_TIMEOUT_MILLIS = 5_000L
    }
}
