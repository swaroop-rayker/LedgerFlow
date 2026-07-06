package com.ledgerflow.presentation.features.dashboard

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ledgerflow.domain.model.DashboardSummary
import com.ledgerflow.domain.model.Result
import com.ledgerflow.domain.usecase.GetDashboardSummaryUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

sealed interface DashboardUiState {
    data object Loading : DashboardUiState
    data class Success(val summary: DashboardSummary) : DashboardUiState
    data class Error(val message: String) : DashboardUiState
}

@HiltViewModel
class DashboardViewModel @Inject constructor(
    private val getDashboardSummaryUseCase: GetDashboardSummaryUseCase
) : ViewModel() {

    private val _uiState = MutableStateFlow<DashboardUiState>(DashboardUiState.Loading)
    val uiState: StateFlow<DashboardUiState> = _uiState.asStateFlow()

    init {
        loadDashboardSummary()
    }

    fun loadDashboardSummary() {
        viewModelScope.launch {
            _uiState.update { DashboardUiState.Loading }
            getDashboardSummaryUseCase()
                .collect { result ->
                    when (result) {
                        is Result.Success -> {
                            _uiState.update { DashboardUiState.Success(result.data) }
                        }
                        is Result.Failure.DatabaseError -> {
                            _uiState.update { DashboardUiState.Error("Database error: ${result.exception.localizedMessage}") }
                        }
                        is Result.Failure.ValidationError -> {
                            _uiState.update { DashboardUiState.Error(result.message) }
                        }
                        else -> {
                            _uiState.update { DashboardUiState.Error("An unknown error occurred.") }
                        }
                    }
                }
        }
    }
}
