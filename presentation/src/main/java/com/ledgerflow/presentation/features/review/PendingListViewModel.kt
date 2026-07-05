package com.ledgerflow.presentation.features.review

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ledgerflow.domain.model.PendingTransaction
import com.ledgerflow.domain.model.Result
import com.ledgerflow.domain.usecase.DeletePendingTransactionUseCase
import com.ledgerflow.domain.usecase.GetPendingTransactionsFlowUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

data class PendingListUiState(
    val pendingTransactions: List<PendingTransaction> = emptyList(),
    val isLoading: Boolean = false,
    val errorMessage: String? = null
)

@HiltViewModel
class PendingListViewModel @Inject constructor(
    private val getPendingTransactionsFlowUseCase: GetPendingTransactionsFlowUseCase,
    private val deletePendingTransactionUseCase: DeletePendingTransactionUseCase
) : ViewModel() {

    private val _uiState = MutableStateFlow(PendingListUiState())
    val uiState: StateFlow<PendingListUiState> = _uiState.asStateFlow()

    init {
        loadPendingTransactions()
    }

    private fun loadPendingTransactions() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true) }
            getPendingTransactionsFlowUseCase()
                .catch { e -> _uiState.update { it.copy(isLoading = false, errorMessage = e.localizedMessage) } }
                .collect { list ->
                    _uiState.update { it.copy(isLoading = false, pendingTransactions = list, errorMessage = null) }
                }
        }
    }

    fun discardPendingTransaction(id: Long) {
        viewModelScope.launch {
            deletePendingTransactionUseCase(id)
        }
    }
}
