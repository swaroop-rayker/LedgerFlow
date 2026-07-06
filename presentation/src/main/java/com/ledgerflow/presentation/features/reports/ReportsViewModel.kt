package com.ledgerflow.presentation.features.reports

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ledgerflow.domain.model.Result
import com.ledgerflow.domain.model.Transaction
import com.ledgerflow.domain.model.Budget
import com.ledgerflow.domain.model.Category
import com.ledgerflow.domain.repository.BudgetRepository
import com.ledgerflow.domain.repository.CategoryRepository
import com.ledgerflow.domain.repository.TransactionRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import javax.inject.Inject

data class DailySpend(val dateStr: String, val amount: Long)
data class WeeklySpend(val weekStr: String, val amount: Long)
data class MerchantSpend(val merchant: String, val amount: Long, val percentage: Float)
data class PaymentMethodSpend(val paymentMethod: String, val amount: Long, val percentage: Float)
data class RecurringMerchant(val merchant: String, val count: Int, val totalAmount: Long)
data class BudgetReportProgress(val categoryName: String, val limitAmount: Long, val spentAmount: Long, val percentage: Float)

data class ReportsUiState(
    val totalExpense: Long = 0L,
    val averageDailySpend: Long = 0L,
    val dailySpend: List<DailySpend> = emptyList(),
    val weeklySpend: List<WeeklySpend> = emptyList(),
    val categorySummaries: List<CategoryReportSummary> = emptyList(),
    val merchantBreakdown: List<MerchantSpend> = emptyList(),
    val paymentMethodDistribution: List<PaymentMethodSpend> = emptyList(),
    val largestPurchases: List<Transaction> = emptyList(),
    val recurringMerchants: List<RecurringMerchant> = emptyList(),
    val budgetUtilization: List<BudgetReportProgress> = emptyList(),
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
    private val categoryRepository: CategoryRepository,
    private val budgetRepository: BudgetRepository
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
            
            val startCalendar = Calendar.getInstance().apply {
                add(Calendar.DAY_OF_YEAR, -30)
                set(Calendar.HOUR_OF_DAY, 0)
                set(Calendar.MINUTE, 0)
                set(Calendar.SECOND, 0)
            }
            val startDate = startCalendar.timeInMillis

            combine(
                transactionRepository.getTransactionsFlow(startDate, endDate),
                budgetRepository.getBudgetsFlow(),
                categoryRepository.getCategoriesFlow()
            ) { txList, budgets, categories ->
                val totalExpense = txList.sumOf { it.amount }
                val averageDaily = totalExpense / 30

                // 1. Daily spend trend
                val dateFormat = SimpleDateFormat("MMM dd", Locale.getDefault())
                val dailyMap = mutableMapOf<String, Long>()
                val tempCal = Calendar.getInstance()
                for (i in 0..29) {
                    dailyMap[dateFormat.format(tempCal.time)] = 0L
                    tempCal.add(Calendar.DAY_OF_YEAR, -1)
                }
                txList.forEach { tx ->
                    val dateKey = dateFormat.format(Date(tx.timestamp))
                    if (dailyMap.containsKey(dateKey)) {
                        dailyMap[dateKey] = dailyMap.getValue(dateKey) + tx.amount
                    }
                }
                val dailyList = dailyMap.map { DailySpend(it.key, it.value) }.reversed()

                // 2. Weekly spend
                val weekMap = mutableMapOf<String, Long>()
                txList.forEach { tx ->
                    val cal = Calendar.getInstance().apply { timeInMillis = tx.timestamp }
                    val weekKey = "Week " + cal.get(Calendar.WEEK_OF_YEAR)
                    weekMap[weekKey] = (weekMap[weekKey] ?: 0L) + tx.amount
                }
                val weeklyList = weekMap.map { WeeklySpend(it.key, it.value) }

                // 3. Category Breakdown
                val categorySums = mutableMapOf<String, Long>()
                txList.forEach { tx ->
                    categorySums[tx.category] = (categorySums[tx.category] ?: 0L) + tx.amount
                }
                val catSummaries = categorySums.map { (catName, amt) ->
                    val pct = if (totalExpense > 0) (amt.toFloat() / totalExpense.toFloat()) else 0f
                    CategoryReportSummary(catName, amt, pct)
                }.sortedByDescending { it.amount }

                // 4. Merchant Breakdown
                val merchantSums = mutableMapOf<String, Long>()
                txList.forEach { tx ->
                    merchantSums[tx.merchant] = (merchantSums[tx.merchant] ?: 0L) + tx.amount
                }
                val merchantList = merchantSums.map { (m, amt) ->
                    val pct = if (totalExpense > 0) (amt.toFloat() / totalExpense.toFloat()) else 0f
                    MerchantSpend(m, amt, pct)
                }.sortedByDescending { it.amount }.take(5)

                // 5. Payment Method Distribution
                val pmSums = mutableMapOf<String, Long>()
                txList.forEach { tx ->
                    val pm = tx.paymentMethod ?: "Cash"
                    pmSums[pm] = (pmSums[pm] ?: 0L) + tx.amount
                }
                val pmList = pmSums.map { (pm, amt) ->
                    val pct = if (totalExpense > 0) (amt.toFloat() / totalExpense.toFloat()) else 0f
                    PaymentMethodSpend(pm, amt, pct)
                }.sortedByDescending { it.amount }

                // 6. Largest purchases
                val largest = txList.sortedByDescending { it.amount }.take(5)

                // 7. Recurring Merchants
                val recMap = mutableMapOf<String, Pair<Int, Long>>()
                txList.forEach { tx ->
                    val current = recMap[tx.merchant] ?: Pair(0, 0L)
                    recMap[tx.merchant] = Pair(current.first + 1, current.second + tx.amount)
                }
                val recurringList = recMap.map { (m, pair) ->
                    RecurringMerchant(m, pair.first, pair.second)
                }.sortedByDescending { it.count }.take(5)

                // 8. Budget progress allocation
                val catMap = categories.associateBy { it.id }
                val budgetList = budgets.map { b ->
                    val catName = catMap[b.categoryId]?.name ?: "Unknown"
                    val spent = txList.filter { it.category.equals(catName, ignoreCase = true) }.sumOf { it.amount }
                    val pct = if (b.amount > 0) (spent.toFloat() / b.amount.toFloat()).coerceIn(0f, 1f) else 0f
                    BudgetReportProgress(catName, b.amount, spent, pct)
                }

                ReportsUiState(
                    totalExpense = totalExpense,
                    averageDailySpend = averageDaily,
                    dailySpend = dailyList,
                    weeklySpend = weeklyList,
                    categorySummaries = catSummaries,
                    merchantBreakdown = merchantList,
                    paymentMethodDistribution = pmList,
                    largestPurchases = largest,
                    recurringMerchants = recurringList,
                    budgetUtilization = budgetList,
                    isLoading = false,
                    errorMessage = null
                )
            }.catch { e ->
                _uiState.update { it.copy(isLoading = false, errorMessage = e.localizedMessage) }
            }.collect { state ->
                _uiState.value = state
            }
        }
    }

    fun exportTransactionsToCsv(onExportReady: (String) -> Unit) {
        viewModelScope.launch {
            val calendar = Calendar.getInstance()
            val endDate = calendar.timeInMillis
            val startCalendar = Calendar.getInstance().apply {
                add(Calendar.DAY_OF_YEAR, -90)
            }
            val startDate = startCalendar.timeInMillis
            val txList = transactionRepository.getTransactionsFlow(startDate, endDate).first()
            
            val csvBuilder = java.lang.StringBuilder()
            csvBuilder.append("ID,Date,Merchant,Category,Amount,Payment Method,Notes\n")
            
            val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
            txList.forEach { tx ->
                val dateStr = sdf.format(Date(tx.timestamp))
                val amountDouble = tx.amount / 100.0
                val cleanMerchant = tx.merchant.replace("\"", "\"\"")
                val cleanCategory = tx.category.replace("\"", "\"\"")
                val cleanNotes = (tx.notes ?: "").replace("\"", "\"\"")
                val cleanPayment = (tx.paymentMethod ?: "Cash").replace("\"", "\"\"")
                csvBuilder.append("${tx.id},\"$dateStr\",\"$cleanMerchant\",\"$cleanCategory\",$amountDouble,\"$cleanPayment\",\"$cleanNotes\"\n")
            }
            
            onExportReady(csvBuilder.toString())
        }
    }
}
