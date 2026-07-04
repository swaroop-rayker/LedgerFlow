package com.ledgerflow.presentation.features.reports

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ledgerflow.domain.model.Result
import com.ledgerflow.domain.repository.CategoryRepository
import com.ledgerflow.domain.repository.TransactionRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import java.util.Calendar
import javax.inject.Inject

data class ReportsUiState(
    val totalIncome: Long = 0L,
    val totalExpense: Long = 0L,
    val netSavings: Long = 0L,
    val categorySummaries: List<CategoryReportSummary> = emptyList(),
    val isLoading: Boolean = false,
    val errorMessage: String? = null
)

data class CategoryReportSummary(
    val categoryName: String,
    val amount: Long,
    val percentage: Float
)

@HiltViewModel
class ReportsViewModel @Inject constructor(
    private val transactionRepository: TransactionRepository,
    private val categoryRepository: CategoryRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(ReportsUiState())
    val uiState: StateFlow<ReportsUiState> = _uiState.asStateFlow()

    init {
        generateReport()
    }

    fun generateReport() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, errorMessage = null) }

            val calendar = Calendar.getInstance()
            val endDate = calendar.timeInMillis
            calendar.add(Calendar.DAY_OF_YEAR, -30)
            val startDate = calendar.timeInMillis

            categoryRepository.getCategoriesFlow().combine(
                transactionRepository.getTransactionsFlow(startDate, endDate)
            ) { categories, txList ->
                var income = 0L
                var expense = 0L
                val categorySums = mutableMapOf<Long, Long>()

                txList.forEach { tx ->
                    if (tx.type.name == "INCOME") {
                        income += tx.totalAmount
                    } else if (tx.type.name == "EXPENSE") {
                        expense += tx.totalAmount
                    }
                }

                var totalSplitAmount = 0L
                txList.forEach { tx ->
                    val splitsRes = transactionRepository.getSplitsForTransaction(tx.id)
                    if (splitsRes is Result.Success) {
                        splitsRes.data.forEach { split ->
                            categorySums[split.categoryId] = (categorySums[split.categoryId] ?: 0L) + split.amount
                            if (tx.type.name == "EXPENSE") {
                                totalSplitAmount += split.amount
                            }
                        }
                    }
                }

                val summaries = categorySums.map { (catId, amt) ->
                    val catName = categories.find { it.id == catId }?.name ?: "Other"
                    val pct = if (totalSplitAmount > 0) (amt.toFloat() / totalSplitAmount.toFloat()) else 0f
                    CategoryReportSummary(catName, amt, pct)
                }.sortedByDescending { it.amount }

                ReportsUiState(
                    totalIncome = income,
                    totalExpense = expense,
                    netSavings = income - expense,
                    categorySummaries = summaries,
                    isLoading = false
                )
            }.catch { e ->
                _uiState.update { it.copy(isLoading = false, errorMessage = e.localizedMessage) }
            }.collect { state ->
                _uiState.value = state
            }
        }
    }
}
