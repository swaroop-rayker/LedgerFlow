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
import java.util.UUID
import javax.inject.Inject

enum class SplitMode {
    MANUAL, OCR
}

data class SplitItem(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val amount: Double,
    val category: String = "Others",
    val subcategory: String? = null
)

data class MultiCategorySplitUiState(
    val merchant: String = "",
    val paymentMethod: String = "Cash",
    val items: List<SplitItem> = emptyList(),
    val isSaved: Boolean = false,
    val errorMessage: String? = null,
    val isLoading: Boolean = false,
    val mode: SplitMode = SplitMode.MANUAL
)

@HiltViewModel
class MultiCategorySplitViewModel @Inject constructor(
    private val saveTransactionUseCase: SaveTransactionUseCase,
    private val getCategoriesWithSubcategoriesUseCase: GetCategoriesWithSubcategoriesUseCase,
    private val validateTransactionCategoryUseCase: ValidateTransactionCategoryUseCase
) : ViewModel() {

    private val _uiState = MutableStateFlow(MultiCategorySplitUiState())
    val uiState: StateFlow<MultiCategorySplitUiState> = _uiState.asStateFlow()

    private val _categories = MutableStateFlow<List<CategoryWithSubcategories>>(emptyList())
    val categories: StateFlow<List<CategoryWithSubcategories>> = _categories.asStateFlow()

    init {
        viewModelScope.launch {
            getCategoriesWithSubcategoriesUseCase().collect { categoryTrees ->
                _categories.value = categoryTrees
            }
        }
    }

    fun onMerchantChanged(merchant: String) {
        _uiState.update { it.copy(merchant = merchant) }
    }

    fun onPaymentMethodChanged(method: String) {
        _uiState.update { it.copy(paymentMethod = method) }
    }

    fun onModeChanged(mode: SplitMode) {
        _uiState.update { it.copy(mode = mode) }
    }

    fun addManualSplit() {
        val newItem = SplitItem(
            name = "Split Allocation #${_uiState.value.items.size + 1}",
            amount = 0.0,
            category = "Others"
        )
        _uiState.update { it.copy(items = it.items + newItem) }
    }

    fun updateItemName(id: String, name: String) {
        _uiState.update { state ->
            state.copy(
                items = state.items.map {
                    if (it.id == id) it.copy(name = name) else it
                }
            )
        }
    }

    fun updateItemAmount(id: String, amount: Double) {
        _uiState.update { state ->
            state.copy(
                items = state.items.map {
                    if (it.id == id) it.copy(amount = amount) else it
                }
            )
        }
    }

    fun updateItemCategory(id: String, category: String, subcategory: String?) {
        _uiState.update { state ->
            state.copy(
                items = state.items.map {
                    if (it.id == id) it.copy(category = category, subcategory = subcategory) else it
                }
            )
        }
    }

    fun removeItem(id: String) {
        _uiState.update { state ->
            state.copy(
                items = state.items.filter { it.id != id }
            )
        }
    }

    fun parseOcrText(text: String) {
        val parsedItems = mutableListOf<SplitItem>()
        val lines = text.split("\n")
        val priceRegex = Regex("""(?:\$|₹|Rs\.?|INR)?\s*(\d+(?:\.\d{1,2})?)""")
        val skipKeywords = listOf("total", "subtotal", "tax", "gst", "cgst", "sgst", "vat", "cash", "change", "card", "discount", "savings", "balance", "net")

        for (line in lines) {
            val trimmed = line.trim()
            if (trimmed.isEmpty()) continue
            
            val lower = trimmed.lowercase()
            if (skipKeywords.any { lower.contains(it) }) continue
            
            val match = priceRegex.find(trimmed)
            if (match != null) {
                val priceStr = match.groupValues[1]
                val price = priceStr.toDoubleOrNull() ?: 0.0
                if (price > 0.0) {
                    var itemName = trimmed.replace(match.value, "").trim()
                    itemName = itemName.replace(Regex("""^[-\s\d.:*•#+]+"""), "").trim()
                    itemName = itemName.replace(Regex("""[-\s\d.:*•#+]+$"""), "").trim()
                    if (itemName.isBlank()) {
                        itemName = "Item @ $priceStr"
                    }
                    parsedItems.add(
                        SplitItem(
                            id = UUID.randomUUID().toString(),
                            name = itemName,
                            amount = price,
                            category = "Others"
                        )
                    )
                }
            }
        }
        
        _uiState.update { state ->
            state.copy(
                items = state.items + parsedItems,
                errorMessage = if (parsedItems.isEmpty()) "No items with prices could be parsed from receipt. Try adding manually or load demo receipt." else null
            )
        }
    }

    fun loadDemoOcr() {
        val demoItems = listOf(
            SplitItem(id = UUID.randomUUID().toString(), name = "Whole Wheat Bread", amount = 45.0, category = "Others"),
            SplitItem(id = UUID.randomUUID().toString(), name = "Organic Milk 1L", amount = 75.0, category = "Others"),
            SplitItem(id = UUID.randomUUID().toString(), name = "Fresh Apples 1kg", amount = 180.0, category = "Others"),
            SplitItem(id = UUID.randomUUID().toString(), name = "Laundry Detergent", amount = 250.0, category = "Others"),
            SplitItem(id = UUID.randomUUID().toString(), name = "Dark Chocolate", amount = 120.0, category = "Others")
        )
        _uiState.update { state ->
            state.copy(
                items = state.items + demoItems,
                errorMessage = null
            )
        }
    }

    fun saveSplits() {
        val state = _uiState.value
        if (state.merchant.isBlank()) {
            _uiState.update { it.copy(errorMessage = "Merchant name is mandatory.") }
            return
        }
        if (state.items.isEmpty()) {
            _uiState.update { it.copy(errorMessage = "Please add at least one item/split.") }
            return
        }

        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, errorMessage = null) }

            // Validate all items
            for (item in state.items) {
                if (item.amount <= 0.0) {
                    _uiState.update { it.copy(isLoading = false, errorMessage = "Amount for item '${item.name}' must be greater than zero.") }
                    return@launch
                }
                when (val validationRes = validateTransactionCategoryUseCase(item.category, item.subcategory)) {
                    is Result.Failure.ValidationError -> {
                        _uiState.update { it.copy(isLoading = false, errorMessage = "Validation failed for '${item.name}': ${validationRes.message}") }
                        return@launch
                    }
                    else -> {}
                }
            }

            // Save items as separate transactions
            val baseTime = Calendar.getInstance().timeInMillis
            var successCount = 0
            for ((index, item) in state.items.withIndex()) {
                val centsAmount = (item.amount * 100).toLong()
                val transaction = Transaction(
                    amount = centsAmount, // Default to outflow (debit)
                    merchant = state.merchant.trim(),
                    category = item.category,
                    subcategory = item.subcategory,
                    paymentMethod = state.paymentMethod,
                    timestamp = baseTime - index * 1000, // micro-delay to maintain sorting order if listed together
                    notes = "Split item: ${item.name}"
                )
                when (saveTransactionUseCase(transaction)) {
                    is Result.Success -> {
                        successCount++
                    }
                    is Result.Failure.ValidationError -> {
                        _uiState.update { it.copy(isLoading = false, errorMessage = "Error saving '${item.name}': Validation error") }
                        return@launch
                    }
                    is Result.Failure.DatabaseError -> {
                        _uiState.update { it.copy(isLoading = false, errorMessage = "Database error saving '${item.name}'") }
                        return@launch
                    }
                    else -> {
                        _uiState.update { it.copy(isLoading = false, errorMessage = "Unknown error saving '${item.name}'") }
                        return@launch
                    }
                }
            }

            if (successCount == state.items.size) {
                _uiState.update { it.copy(isLoading = false, isSaved = true) }
            }
        }
    }
}
