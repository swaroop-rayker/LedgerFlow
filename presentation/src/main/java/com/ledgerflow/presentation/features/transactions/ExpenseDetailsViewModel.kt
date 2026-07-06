package com.ledgerflow.presentation.features.transactions

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ledgerflow.domain.model.*
import com.ledgerflow.domain.repository.*
import com.ledgerflow.domain.usecase.GetCategoriesWithSubcategoriesUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

data class ExpenseDetailsUiState(
    val transaction: Transaction? = null,
    val merchantInfo: Merchant? = null,
    val attachments: List<Attachment> = emptyList(),
    val categories: List<CategoryWithSubcategories> = emptyList(),
    val firstSeen: Long = 0L,
    val lastSeen: Long = 0L,
    val averageExpense: Long = 0L,
    val highestExpense: Long = 0L,
    val totalSpent: Long = 0L,
    val previousExpenses: List<Transaction> = emptyList(),
    val similarTransactions: List<Transaction> = emptyList(),
    val isLoading: Boolean = false,
    val errorMessage: String? = null,
    val isDeleted: Boolean = false,
    val isUndoAvailable: Boolean = false
)

@HiltViewModel
class ExpenseDetailsViewModel @Inject constructor(
    private val transactionRepository: TransactionRepository,
    private val merchantRepository: MerchantRepository,
    private val categoryRepository: CategoryRepository,
    private val attachmentRepository: AttachmentRepository,
    private val getCategoriesWithSubcategoriesUseCase: GetCategoriesWithSubcategoriesUseCase
) : ViewModel() {

    private val _uiState = MutableStateFlow(ExpenseDetailsUiState())
    val uiState: StateFlow<ExpenseDetailsUiState> = _uiState.asStateFlow()

    private var lastDeletedTransaction: Transaction? = null
    private var lastDeletedAttachments: List<Attachment> = emptyList()

    fun loadExpenseDetails(transactionId: Long) {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, errorMessage = null) }
            
            // 1. Fetch transaction
            when (val txRes = transactionRepository.getTransactionById(transactionId)) {
                is Result.Success -> {
                    val tx = txRes.data
                    if (tx == null) {
                        _uiState.update { it.copy(isLoading = false, errorMessage = "Expense not found") }
                        return@launch
                    }
                    _uiState.update { it.copy(transaction = tx) }
                    
                    // Asynchronously fetch other dependent data
                    fetchMerchantAndStatistics(tx)
                    fetchAttachments(tx.id)
                    fetchCategories()
                    fetchSimilarTransactions(tx)
                }
                is Result.Failure.DatabaseError -> {
                    _uiState.update { it.copy(isLoading = false, errorMessage = txRes.exception.localizedMessage) }
                }
                else -> {
                    _uiState.update { it.copy(isLoading = false, errorMessage = "Unknown database error") }
                }
            }
        }
    }

    private suspend fun fetchMerchantAndStatistics(tx: Transaction) {
        // Fetch matching merchant preferences if any
        val merchantRes = merchantRepository.getMerchantByNormalizedName(tx.merchant.lowercase().trim())
        var merchantInfo: Merchant? = null
        if (merchantRes is Result.Success) {
            merchantInfo = merchantRes.data
        }
        
        // Fetch all transactions with this merchant to compute statistics
        val historyRes = transactionRepository.getTransactionsByMerchant(tx.merchant)
        var firstSeen = 0L
        var lastSeen = 0L
        var avgExpense = 0L
        var maxExpense = 0L
        var totalSpent = 0L
        var previousExpenses = emptyList<Transaction>()

        if (historyRes is Result.Success) {
            val list = historyRes.data
            if (list.isNotEmpty()) {
                firstSeen = list.minOf { it.timestamp }
                lastSeen = list.maxOf { it.timestamp }
                totalSpent = list.sumOf { it.amount }
                avgExpense = totalSpent / list.size
                maxExpense = list.maxOf { it.amount }
                previousExpenses = list.filter { it.id != tx.id }.take(5)
            }
        }

        _uiState.update {
            it.copy(
                merchantInfo = merchantInfo,
                firstSeen = firstSeen,
                lastSeen = lastSeen,
                averageExpense = avgExpense,
                highestExpense = maxExpense,
                totalSpent = totalSpent,
                previousExpenses = previousExpenses
            )
        }
    }

    private suspend fun fetchAttachments(transactionId: Long) {
        val attachRes = attachmentRepository.getAttachmentsForTransaction(transactionId)
        if (attachRes is Result.Success) {
            _uiState.update { it.copy(attachments = attachRes.data, isLoading = false) }
        } else {
            _uiState.update { it.copy(isLoading = false) }
        }
    }

    private suspend fun fetchCategories() {
        getCategoriesWithSubcategoriesUseCase().take(1).collect { list ->
            _uiState.update { it.copy(categories = list) }
        }
    }

    private suspend fun fetchSimilarTransactions(tx: Transaction) {
        val similarRes = transactionRepository.getTransactionsByCategory(tx.category, 6)
        if (similarRes is Result.Success) {
            _uiState.update { 
                it.copy(similarTransactions = similarRes.data.filter { s -> s.id != tx.id }.take(5)) 
            }
        }
    }

    fun updateCategory(categoryName: String, subcategoryName: String?) {
        val currentTx = _uiState.value.transaction ?: return
        viewModelScope.launch {
            val updated = currentTx.copy(category = categoryName, subcategory = subcategoryName)
            when (val res = transactionRepository.saveTransaction(updated)) {
                is Result.Success -> {
                    _uiState.update { it.copy(transaction = updated) }
                    // Update similar transactions
                    fetchSimilarTransactions(updated)
                }
                is Result.Failure.ValidationError -> {
                    _uiState.update { it.copy(errorMessage = res.message) }
                }
                is Result.Failure.DatabaseError -> {
                    _uiState.update { it.copy(errorMessage = res.exception.localizedMessage) }
                }
                else -> {}
            }
        }
    }

    fun updateMerchant(newMerchantName: String) {
        val currentTx = _uiState.value.transaction ?: return
        if (newMerchantName.isBlank()) return
        viewModelScope.launch {
            val updated = currentTx.copy(merchant = newMerchantName)
            when (val res = transactionRepository.saveTransaction(updated)) {
                is Result.Success -> {
                    _uiState.update { it.copy(transaction = updated) }
                    // Recompute merchant statistics
                    fetchMerchantAndStatistics(updated)
                }
                is Result.Failure.ValidationError -> {
                    _uiState.update { it.copy(errorMessage = res.message) }
                }
                is Result.Failure.DatabaseError -> {
                    _uiState.update { it.copy(errorMessage = res.exception.localizedMessage) }
                }
                else -> {}
            }
        }
    }

    fun updateNotes(notes: String) {
        val currentTx = _uiState.value.transaction ?: return
        viewModelScope.launch {
            val updated = currentTx.copy(notes = notes.ifBlank { null })
            when (val res = transactionRepository.saveTransaction(updated)) {
                is Result.Success -> {
                    _uiState.update { it.copy(transaction = updated) }
                }
                is Result.Failure.ValidationError -> {
                    _uiState.update { it.copy(errorMessage = res.message) }
                }
                is Result.Failure.DatabaseError -> {
                    _uiState.update { it.copy(errorMessage = res.exception.localizedMessage) }
                }
                else -> {}
            }
        }
    }

    fun deleteTransaction() {
        val currentTx = _uiState.value.transaction ?: return
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true) }
            
            // Capture for undo
            lastDeletedTransaction = currentTx
            lastDeletedAttachments = _uiState.value.attachments
            
            when (val res = transactionRepository.deleteTransaction(currentTx.id)) {
                is Result.Success -> {
                    _uiState.update { 
                        it.copy(
                            isLoading = false, 
                            isDeleted = true, 
                            isUndoAvailable = true 
                        ) 
                    }
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

    fun undoDelete() {
        val txToRestore = lastDeletedTransaction ?: return
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true) }
            when (val res = transactionRepository.saveTransaction(txToRestore)) {
                is Result.Success -> {
                    // Restore attachments too
                    lastDeletedAttachments.forEach { attach ->
                        attachmentRepository.saveAttachment(attach)
                    }
                    _uiState.update { 
                        it.copy(
                            transaction = txToRestore,
                            isLoading = false,
                            isDeleted = false,
                            isUndoAvailable = false
                        ) 
                    }
                    fetchMerchantAndStatistics(txToRestore)
                    fetchAttachments(txToRestore.id)
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

    fun addAttachment(filePath: String, fileType: String) {
        val tx = _uiState.value.transaction ?: return
        viewModelScope.launch {
            val attachment = Attachment(
                transactionId = tx.id,
                filePath = filePath,
                fileType = fileType,
                ocrData = null
            )
            when (attachmentRepository.saveAttachment(attachment)) {
                is Result.Success -> {
                    fetchAttachments(tx.id)
                }
                else -> {}
            }
        }
    }

    fun deleteAttachment(attachmentId: Long) {
        val tx = _uiState.value.transaction ?: return
        viewModelScope.launch {
            when (attachmentRepository.deleteAttachment(attachmentId)) {
                is Result.Success -> {
                    fetchAttachments(tx.id)
                }
                else -> {}
            }
        }
    }
}
