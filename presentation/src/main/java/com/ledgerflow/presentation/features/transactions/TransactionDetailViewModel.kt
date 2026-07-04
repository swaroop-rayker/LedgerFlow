package com.ledgerflow.presentation.features.transactions

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ledgerflow.domain.model.*
import com.ledgerflow.domain.repository.CategoryRepository
import com.ledgerflow.domain.repository.MerchantRepository
import com.ledgerflow.domain.repository.PaymentMethodRepository
import com.ledgerflow.domain.usecase.SaveTransactionUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import java.util.Calendar
import javax.inject.Inject

data class TransactionDetailUiState(
    val categories: List<Category> = emptyList(),
    val merchants: List<Merchant> = emptyList(),
    val paymentMethods: List<PaymentMethod> = emptyList(),
    val selectedMerchantId: Long? = null,
    val selectedPaymentMethodId: Long? = null,
    val totalAmount: Double = 0.0,
    val type: TransactionType = TransactionType.EXPENSE,
    val notes: String = "",
    val splits: List<TransactionSplitDraft> = emptyList(),
    val tagsString: String = "",
    val isSaved: Boolean = false,
    val errorMessage: String? = null
)

data class TransactionSplitDraft(
    val categoryId: Long,
    val amount: Double,
    val notes: String = ""
)

@HiltViewModel
class TransactionDetailViewModel @Inject constructor(
    private val saveTransactionUseCase: SaveTransactionUseCase,
    private val categoryRepository: CategoryRepository,
    private val merchantRepository: MerchantRepository,
    private val paymentMethodRepository: PaymentMethodRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(TransactionDetailUiState())
    val uiState: StateFlow<TransactionDetailUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            // Load setup metadata
            val cats = categoryRepository.getCategoriesFlow().first()
            val merches = merchantRepository.getMerchantsFlow().first()
            val payMethods = paymentMethodRepository.getPaymentMethodsFlow().first()
            
            _uiState.update {
                it.copy(
                    categories = cats,
                    merchants = merches,
                    paymentMethods = payMethods,
                    selectedPaymentMethodId = payMethods.firstOrNull()?.id
                )
            }
        }
    }

    fun onTotalAmountChanged(amount: Double) {
        _uiState.update { it.copy(totalAmount = amount) }
    }

    fun onTypeChanged(type: TransactionType) {
        _uiState.update { it.copy(type = type) }
    }

    fun onMerchantSelected(merchantId: Long?) {
        _uiState.update { it.copy(selectedMerchantId = merchantId) }
    }

    fun onPaymentMethodSelected(paymentMethodId: Long?) {
        _uiState.update { it.copy(selectedPaymentMethodId = paymentMethodId) }
    }

    fun onNotesChanged(notes: String) {
        _uiState.update { it.copy(notes = notes) }
    }

    fun onTagsChanged(tags: String) {
        _uiState.update { it.copy(tagsString = tags) }
    }

    fun addSplit(categoryId: Long, amount: Double) {
        _uiState.update {
            val updatedSplits = it.splits + TransactionSplitDraft(categoryId, amount)
            it.copy(splits = updatedSplits)
        }
    }

    fun removeSplit(index: Int) {
        _uiState.update {
            val updatedSplits = it.splits.toMutableList().apply { removeAt(index) }
            it.copy(splits = updatedSplits)
        }
    }

    fun saveTransaction() {
        viewModelScope.launch {
            val state = _uiState.value
            
            // Map inputs to Cents
            val centsTotal = (state.totalAmount * 100).toLong()
            
            val transaction = Transaction(
                timestamp = Calendar.getInstance().timeInMillis,
                totalAmount = centsTotal,
                type = state.type,
                merchantId = state.selectedMerchantId,
                paymentMethodId = state.selectedPaymentMethodId,
                notes = state.notes.ifBlank { null }
            )

            // If no splits added, default to a single split spanning the whole amount
            val splits = if (state.splits.isEmpty()) {
                val defaultCategoryId = state.categories.firstOrNull()?.id ?: 0L
                listOf(
                    TransactionSplit(
                        transactionId = 0L,
                        categoryId = defaultCategoryId,
                        amount = centsTotal
                    )
                )
            } else {
                state.splits.map { splitDraft ->
                    TransactionSplit(
                        transactionId = 0L,
                        categoryId = splitDraft.categoryId,
                        amount = (splitDraft.amount * 100).toLong(),
                        notes = splitDraft.notes.ifBlank { null }
                    )
                }
            }

            val tags = state.tagsString.split(",")
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .map { Tag(name = it) }

            when (val result = saveTransactionUseCase(transaction, splits, tags)) {
                is Result.Success -> {
                    _uiState.update { it.copy(isSaved = true, errorMessage = null) }
                }
                is Result.Failure.ValidationError -> {
                    _uiState.update { it.copy(errorMessage = result.message) }
                }
                is Result.Failure.DatabaseError -> {
                    _uiState.update { it.copy(errorMessage = "Database error: ${result.exception.localizedMessage}") }
                }
                else -> {
                    _uiState.update { it.copy(errorMessage = "An unknown error occurred.") }
                }
            }
        }
    }
}
