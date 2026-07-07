package com.ledgerflow.presentation.features.transactions

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ledgerflow.domain.model.Result
import com.ledgerflow.domain.model.Transaction
import com.ledgerflow.domain.repository.TransactionRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.Calendar
import javax.inject.Inject

data class TransactionListUiState(
    val transactions: List<Transaction> = emptyList(),
    val isLoading: Boolean = false,
    val errorMessage: String? = null,
    val selectedIds: Set<Long> = emptySet(),
    val isMultiSelectMode: Boolean = false
)

@HiltViewModel
class TransactionListViewModel @Inject constructor(
    private val transactionRepository: TransactionRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(TransactionListUiState())
    val uiState: StateFlow<TransactionListUiState> = _uiState.asStateFlow()

    private var recentlyDeletedTransaction: Transaction? = null

    init {
        loadTransactions()
    }

    fun loadTransactions() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, errorMessage = null) }
            when (val res = transactionRepository.getPagedTransactions(limit = 150, offset = 0)) {
                is Result.Success -> {
                    _uiState.update { it.copy(isLoading = false, transactions = res.data) }
                }
                is Result.Failure.DatabaseError -> {
                    _uiState.update { it.copy(isLoading = false, errorMessage = res.exception.localizedMessage) }
                }
                else -> {
                    _uiState.update { it.copy(isLoading = false) }
                }
            }
        }
    }

    fun toggleSelection(id: Long) {
        _uiState.update { state ->
            val updated = if (state.selectedIds.contains(id)) {
                state.selectedIds - id
            } else {
                state.selectedIds + id
            }
            state.copy(
                selectedIds = updated,
                isMultiSelectMode = updated.isNotEmpty()
            )
        }
    }

    fun clearSelection() {
        _uiState.update { it.copy(selectedIds = emptySet(), isMultiSelectMode = false) }
    }

    fun deleteTransaction(id: Long) {
        viewModelScope.launch {
            val txn = _uiState.value.transactions.find { it.id == id }
            if (txn != null) {
                recentlyDeletedTransaction = txn
            }
            when (transactionRepository.deleteTransaction(id)) {
                is Result.Success -> {
                    loadTransactions()
                }
                else -> {}
            }
        }
    }

    fun undoDelete() {
        val deleted = recentlyDeletedTransaction
        if (deleted != null) {
            viewModelScope.launch {
                when (transactionRepository.saveTransaction(deleted)) {
                    is Result.Success -> {
                        recentlyDeletedTransaction = null
                        loadTransactions()
                    }
                    else -> {}
                }
            }
        }
    }

    fun duplicateTransaction(txn: Transaction) {
        viewModelScope.launch {
            val duplicate = txn.copy(
                id = 0, // Generate new ID
                timestamp = Calendar.getInstance().timeInMillis
            )
            when (transactionRepository.saveTransaction(duplicate)) {
                is Result.Success -> {
                    loadTransactions()
                }
                else -> {}
            }
        }
    }

    fun bulkDelete() {
        viewModelScope.launch {
            val ids = _uiState.value.selectedIds
            ids.forEach { id ->
                transactionRepository.deleteTransaction(id)
            }
            clearSelection()
            loadTransactions()
        }
    }

    fun bulkRecategorize(category: String) {
        viewModelScope.launch {
            val ids = _uiState.value.selectedIds
            _uiState.value.transactions.filter { ids.contains(it.id) }.forEach { txn ->
                val updated = txn.copy(category = category, subcategory = null)
                transactionRepository.saveTransaction(updated)
            }
            clearSelection()
            loadTransactions()
        }
    }
}
