package com.ledgerflow.presentation.features.transactions

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ledgerflow.domain.model.CategoryWithSubcategories
import com.ledgerflow.domain.model.Result
import com.ledgerflow.domain.model.Transaction
import com.ledgerflow.domain.usecase.SaveTransactionUseCase
import com.ledgerflow.domain.usecase.GetCategoriesWithSubcategoriesUseCase
import com.ledgerflow.domain.usecase.ValidateTransactionCategoryUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.Calendar
import javax.inject.Inject

data class TransactionDetailUiState(
    val amount: Double = 0.0,
    val merchant: String = "",
    val category: String = "Others",
    val subcategory: String? = null,
    val paymentMethod: String = "Cash",
    val reference: String = "",
    val notes: String = "",
    val isSaved: Boolean = false,
    val errorMessage: String? = null
)

@HiltViewModel
class TransactionDetailViewModel @Inject constructor(
    private val saveTransactionUseCase: SaveTransactionUseCase,
    private val getCategoriesWithSubcategoriesUseCase: GetCategoriesWithSubcategoriesUseCase,
    private val validateTransactionCategoryUseCase: ValidateTransactionCategoryUseCase
) : ViewModel() {

    private val _uiState = MutableStateFlow(TransactionDetailUiState())
    val uiState: StateFlow<TransactionDetailUiState> = _uiState.asStateFlow()

    private val _categories = MutableStateFlow<List<CategoryWithSubcategories>>(emptyList())
    val categories: StateFlow<List<CategoryWithSubcategories>> = _categories.asStateFlow()

    init {
        viewModelScope.launch {
            getCategoriesWithSubcategoriesUseCase().collect { categoryTrees ->
                _categories.value = categoryTrees
                
                // Concurrency Category deletion check
                val currentCategory = _uiState.value.category
                val categoryExists = categoryTrees.any { it.category.name.equals(currentCategory, ignoreCase = true) }
                
                if (!categoryExists && currentCategory != "Others" && currentCategory.isNotEmpty()) {
                    _uiState.update {
                        it.copy(
                            category = "Others",
                            subcategory = null,
                            errorMessage = "The category '$currentCategory' was deleted. Reset to 'Others'."
                        )
                    }
                } else if (categoryExists) {
                    val sub = _uiState.value.subcategory
                    if (sub != null) {
                        val tree = categoryTrees.find { it.category.name.equals(currentCategory, ignoreCase = true) }
                        val subExists = tree?.subcategories?.any { it.name.equals(sub, ignoreCase = true) } == true
                        if (!subExists) {
                            _uiState.update {
                                it.copy(
                                    subcategory = null,
                                    errorMessage = "Selected subcategory '$sub' was deleted."
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    fun onAmountChanged(amount: Double) {
        _uiState.update { it.copy(amount = amount) }
    }

    fun onMerchantChanged(merchant: String) {
        _uiState.update { it.copy(merchant = merchant) }
    }

    fun onCategoryChanged(category: String) {
        _uiState.update { it.copy(category = category, subcategory = null) }
    }

    fun onSubcategoryChanged(subcategory: String?) {
        _uiState.update { it.copy(subcategory = subcategory) }
    }

    fun onPaymentMethodChanged(paymentMethod: String) {
        _uiState.update { it.copy(paymentMethod = paymentMethod) }
    }

    fun onReferenceChanged(reference: String) {
        _uiState.update { it.copy(reference = reference) }
    }

    fun onNotesChanged(notes: String) {
        _uiState.update { it.copy(notes = notes) }
    }

    fun saveTransaction() {
        viewModelScope.launch {
            val state = _uiState.value
            _uiState.update { it.copy(errorMessage = null) }

            // Category Selection Validation check
            when (val validationRes = validateTransactionCategoryUseCase(state.category, state.subcategory)) {
                is Result.Failure.ValidationError -> {
                    _uiState.update { it.copy(errorMessage = validationRes.message) }
                    return@launch
                }
                else -> {}
            }

            val centsAmount = (state.amount * 100).toLong()

            val transaction = Transaction(
                amount = centsAmount,
                merchant = state.merchant.trim(),
                category = state.category,
                subcategory = state.subcategory,
                paymentMethod = state.paymentMethod,
                reference = state.reference.trim().ifBlank { null },
                timestamp = Calendar.getInstance().timeInMillis,
                notes = state.notes.trim().ifBlank { null }
            )

            when (val result = saveTransactionUseCase(transaction)) {
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
