package com.ledgerflow.presentation.features.transactions

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ledgerflow.domain.model.Result
import com.ledgerflow.domain.model.TransactionWithDetails
import com.ledgerflow.domain.repository.CategoryRepository
import com.ledgerflow.domain.repository.MerchantRepository
import com.ledgerflow.domain.repository.PaymentMethodRepository
import com.ledgerflow.domain.repository.TransactionRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class TransactionListUiState(
    val transactions: List<TransactionWithDetails> = emptyList(),
    val categories: List<com.ledgerflow.domain.model.Category> = emptyList(),
    val paymentMethods: List<com.ledgerflow.domain.model.PaymentMethod> = emptyList(),
    val isLoading: Boolean = false,
    val errorMessage: String? = null
)

@HiltViewModel
class TransactionListViewModel @Inject constructor(
    private val transactionRepository: TransactionRepository,
    private val merchantRepository: MerchantRepository,
    private val categoryRepository: CategoryRepository,
    private val paymentMethodRepository: PaymentMethodRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(TransactionListUiState())
    val uiState: StateFlow<TransactionListUiState> = _uiState.asStateFlow()

    init {
        loadTransactions()
        loadCategories()
        loadPaymentMethods()
    }

    fun loadTransactions() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, errorMessage = null) }
            when (val res = transactionRepository.getPagedTransactions(limit = 100, offset = 0)) {
                is Result.Success -> {
                    val list = res.data.map { txn ->
                        val merchant = txn.merchantId?.let { id ->
                            when (val mRes = merchantRepository.getMerchantById(id)) {
                                is Result.Success -> mRes.data
                                else -> null
                            }
                        }
                        val splits = when (val sRes = transactionRepository.getSplitsForTransaction(txn.id)) {
                            is Result.Success -> sRes.data
                            else -> emptyList()
                        }
                        TransactionWithDetails(txn, merchant, splits)
                    }
                    _uiState.update { it.copy(isLoading = false, transactions = list) }
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

    private fun loadCategories() {
        viewModelScope.launch {
            categoryRepository.getCategoriesFlow().collect { cats ->
                _uiState.update { it.copy(categories = cats) }
            }
        }
    }

    private fun loadPaymentMethods() {
        viewModelScope.launch {
            paymentMethodRepository.getPaymentMethodsFlow().collect { pm ->
                _uiState.update { it.copy(paymentMethods = pm) }
            }
        }
    }
}
