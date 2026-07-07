package com.ledgerflow.presentation.features.review

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ledgerflow.domain.model.CategoryWithSubcategories
import com.ledgerflow.domain.model.PendingTransaction
import com.ledgerflow.domain.model.Result
import com.ledgerflow.domain.model.Transaction
import com.ledgerflow.domain.usecase.ApprovePendingTransactionUseCase
import com.ledgerflow.domain.usecase.CheckDuplicateTransactionUseCase
import com.ledgerflow.domain.usecase.DeletePendingTransactionUseCase
import com.ledgerflow.domain.usecase.GetPendingTransactionByIdUseCase
import com.ledgerflow.domain.usecase.GetCategoriesWithSubcategoriesUseCase
import com.ledgerflow.domain.usecase.ValidateTransactionCategoryUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject

data class ReviewExpenseUiState(
    val pendingTransaction: PendingTransaction? = null,
    val amount: Double = 0.0,
    val merchant: String = "",
    val category: String = "Others",
    val subcategory: String? = null,
    val paymentMethod: String = "Cash",
    val reference: String = "",
    val notes: String = "",
    val confidence: Int = 100,
    val isDuplicate: Boolean = false,
    val showDuplicateDialog: Boolean = false,
    val isApproved: Boolean = false,
    val isDiscarded: Boolean = false,
    val isPostponed: Boolean = false,
    val errorMessage: String? = null,
    val isLoading: Boolean = false,
    val isCredit: Boolean = false
)

@HiltViewModel
class ReviewExpenseViewModel @Inject constructor(
    private val getPendingTransactionByIdUseCase: GetPendingTransactionByIdUseCase,
    private val checkDuplicateTransactionUseCase: CheckDuplicateTransactionUseCase,
    private val approvePendingTransactionUseCase: ApprovePendingTransactionUseCase,
    private val deletePendingTransactionUseCase: DeletePendingTransactionUseCase,
    private val getCategoriesWithSubcategoriesUseCase: GetCategoriesWithSubcategoriesUseCase,
    private val validateTransactionCategoryUseCase: ValidateTransactionCategoryUseCase
) : ViewModel() {

    private val _uiState = MutableStateFlow(ReviewExpenseUiState())
    val uiState: StateFlow<ReviewExpenseUiState> = _uiState.asStateFlow()

    private val _categories = MutableStateFlow<List<CategoryWithSubcategories>>(emptyList())
    val categories: StateFlow<List<CategoryWithSubcategories>> = _categories.asStateFlow()

    private var pendingId: Long = 0L

    init {
        viewModelScope.launch {
            getCategoriesWithSubcategoriesUseCase().collect { categoryTrees ->
                _categories.value = categoryTrees
                
                // Concurrency Category deletion check
                val currentCategory = _uiState.value.category
                val categoryExists = categoryTrees.any { it.category.name.equals(currentCategory, ignoreCase = true) }
                
                if (!categoryExists && currentCategory != "Others" && currentCategory.isNotEmpty() && _uiState.value.pendingTransaction != null) {
                    _uiState.update {
                        it.copy(
                            category = "Others",
                            subcategory = null,
                            errorMessage = "The previously selected category '$currentCategory' was deleted. Reset to 'Others'."
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

    fun loadPendingTransaction(id: Long) {
        pendingId = id
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, errorMessage = null) }
            Timber.d("ReviewExpenseViewModel: Load started for PendingTransaction ID = %d", id)
            
            when (val res = getPendingTransactionByIdUseCase(id)) {
                is Result.Success -> {
                    val pending = res.data
                    if (pending != null) {
                        Timber.d("Loaded PendingTransaction details: %s", pending)
                        val isCredit = pending.amount < 0
                        val displayAmount = if (isCredit) -pending.amount else pending.amount
                        _uiState.update {
                            it.copy(
                                isLoading = false,
                                pendingTransaction = pending,
                                amount = displayAmount / 100.0,
                                merchant = pending.merchant,
                                category = pending.category,
                                subcategory = pending.subcategory,
                                paymentMethod = pending.paymentMethod ?: "Cash",
                                reference = pending.reference ?: "",
                                notes = pending.notes ?: "",
                                confidence = pending.confidence,
                                isCredit = isCredit
                            )
                        }
                        checkDuplicate()
                    } else {
                        Timber.w("PendingTransaction with ID = %d not found in DB.", id)
                        _uiState.update { it.copy(isLoading = false, errorMessage = "Expense details not found.") }
                    }
                }
                is Result.Failure.DatabaseError -> {
                    Timber.e(res.exception, "Database error loading pending transaction ID = %d", id)
                    _uiState.update { it.copy(isLoading = false, errorMessage = res.exception.localizedMessage) }
                }
                else -> {
                    _uiState.update { it.copy(isLoading = false) }
                }
            }
        }
    }

    private suspend fun checkDuplicate() {
        val state = _uiState.value
        val isDup = checkDuplicateTransactionUseCase(
            amount = (state.amount * 100).toLong(),
            merchant = state.merchant,
            timestamp = state.pendingTransaction?.timestamp ?: System.currentTimeMillis(),
            reference = state.reference.ifBlank { null }
        )
        if (isDup) {
            Timber.d("Duplicate transaction check returned: POSSIBLY DUPLICATE")
        }
        _uiState.update { it.copy(isDuplicate = isDup) }
    }

    fun onMerchantChanged(merchant: String) {
        _uiState.update { it.copy(merchant = merchant) }
        viewModelScope.launch { checkDuplicate() }
    }

    fun onCategoryChanged(category: String) {
        _uiState.update { it.copy(category = category, subcategory = null) }
    }

    fun onSubcategoryChanged(subcategory: String?) {
        _uiState.update { it.copy(subcategory = subcategory) }
    }

    fun onDirectionChanged(isCredit: Boolean) {
        _uiState.update { it.copy(isCredit = isCredit) }
    }

    fun onPaymentMethodChanged(paymentMethod: String) {
        _uiState.update { it.copy(paymentMethod = paymentMethod) }
    }

    fun onReferenceChanged(reference: String) {
        _uiState.update { it.copy(reference = reference) }
        viewModelScope.launch { checkDuplicate() }
    }

    fun onNotesChanged(notes: String) {
        _uiState.update { it.copy(notes = notes) }
    }

    fun hideDuplicateDialog() {
        _uiState.update { it.copy(showDuplicateDialog = false) }
    }

    fun approveTransaction(force: Boolean = false) {
        val state = _uiState.value
        if (state.isDuplicate && !force) {
            Timber.d("Approve triggered but duplicate warning flagged. Showing warning dialog.")
            _uiState.update { it.copy(showDuplicateDialog = true) }
            return
        }

        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, errorMessage = null) }
            
            // Shared Validation Rule Check
            when (val validationRes = validateTransactionCategoryUseCase(state.category, state.subcategory)) {
                is Result.Failure.ValidationError -> {
                    _uiState.update { it.copy(isLoading = false, errorMessage = validationRes.message) }
                    return@launch
                }
                else -> {}
            }

            val centsAmount = (state.amount * 100).toLong()
            val finalAmount = if (state.isCredit) -centsAmount else centsAmount

            val approvedTxn = Transaction(
                amount = finalAmount,
                merchant = state.merchant.trim(),
                category = state.category,
                subcategory = state.subcategory,
                paymentMethod = state.paymentMethod,
                reference = state.reference.trim().ifBlank { null },
                timestamp = state.pendingTransaction?.timestamp ?: System.currentTimeMillis(),
                notes = state.notes.trim().ifBlank { null }
            )

            when (val res = approvePendingTransactionUseCase(pendingId, approvedTxn)) {
                is Result.Success -> {
                    Timber.d("Approved and converted PendingTransaction ID = %d successfully.", pendingId)
                    _uiState.update { it.copy(isLoading = false, isApproved = true) }
                }
                is Result.Failure.ValidationError -> {
                    _uiState.update { it.copy(isLoading = false, errorMessage = res.message) }
                }
                is Result.Failure.DatabaseError -> {
                    Timber.e(res.exception, "Database error approving transaction ID = %d", pendingId)
                    _uiState.update { it.copy(isLoading = false, errorMessage = res.exception.localizedMessage) }
                }
                else -> {
                    _uiState.update { it.copy(isLoading = false) }
                }
            }
        }
    }

    fun discardTransaction() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, errorMessage = null) }
            Timber.d("Discarding PendingTransaction ID = %d", pendingId)
            when (val res = deletePendingTransactionUseCase(pendingId)) {
                is Result.Success -> {
                    _uiState.update { it.copy(isLoading = false, isDiscarded = true) }
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

    fun postponeReview() {
        Timber.d("Postponing review for PendingTransaction ID = %d", pendingId)
        _uiState.update { it.copy(isPostponed = true) }
    }
}
