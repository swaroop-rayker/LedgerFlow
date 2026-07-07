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

data class GroupedItem(val key: String, val value: Long, val percentage: Float)

data class ReportsUiState(
    // Filter source lists
    val allCategories: List<String> = emptyList(),
    val allSubcategories: List<String> = emptyList(),
    val allPaymentMethods: List<String> = emptyList(),
    val allMerchants: List<String> = emptyList(),

    // Current active filters
    val selectedCategories: Set<String> = emptySet(),
    val selectedSubcategories: Set<String> = emptySet(),
    val selectedPaymentMethods: Set<String> = emptySet(),
    val selectedMerchants: Set<String> = emptySet(),
    val selectedDirection: String = "All", // All, Debit, Credit
    val minAmount: Long? = null,
    val maxAmount: Long? = null,
    val dateRange: Pair<Long, Long>? = null, // Start, End timestamp

    // Configuration
    val activeGrouping: String = "Category", // Category, Subcategory, Merchant, Payment Method, Day, Week, Month
    val activeAggregation: String = "Total Spend", // Total Spend, Average, Median, Min, Max, Count
    val activeVisualization: String = "Donut", // Donut, Horizontal Bar, Vertical Bar, Line

    // Report outputs
    val totalExpense: Long = 0L,
    val totalIncome: Long = 0L,
    val averageDailySpend: Long = 0L,
    val dailySpend: List<DailySpend> = emptyList(),
    val groupedData: List<GroupedItem> = emptyList(),
    val budgetUtilization: List<BudgetReportProgress> = emptyList(),
    val largestPurchases: List<Transaction> = emptyList(),
    val recurringMerchants: List<RecurringMerchant> = emptyList(),
    val filteredTransactions: List<Transaction> = emptyList(),
    val isLoading: Boolean = false,
    val errorMessage: String? = null
)

@HiltViewModel
class ReportsViewModel @Inject constructor(
    private val transactionRepository: TransactionRepository,
    private val categoryRepository: CategoryRepository,
    private val budgetRepository: BudgetRepository
) : ViewModel() {

    // Filter flows
    private val _selectedCategories = MutableStateFlow<Set<String>>(emptySet())
    private val _selectedSubcategories = MutableStateFlow<Set<String>>(emptySet())
    private val _selectedPaymentMethods = MutableStateFlow<Set<String>>(emptySet())
    private val _selectedMerchants = MutableStateFlow<Set<String>>(emptySet())
    private val _selectedDirection = MutableStateFlow("All")
    private val _minAmount = MutableStateFlow<Long?>(null)
    private val _maxAmount = MutableStateFlow<Long?>(null)
    private val _dateRange = MutableStateFlow<Pair<Long, Long>?>(null)

    // Config flows
    private val _activeGrouping = MutableStateFlow("Category")
    private val _activeAggregation = MutableStateFlow("Total Spend")
    private val _activeVisualization = MutableStateFlow("Donut")

    private val _uiState = MutableStateFlow(ReportsUiState())
    val uiState: StateFlow<ReportsUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            // Collect metadata lists
            combine(
                transactionRepository.getTransactionsFlow(0L, System.currentTimeMillis()),
                categoryRepository.getCategoriesFlow()
            ) { txs, categories ->
                val cats = categories.filter { it.parentId == null }.map { it.name }.distinct().sorted()
                val subcats = categories.filter { it.parentId != null }.map { it.name }.distinct().sorted()
                val pm = txs.mapNotNull { it.paymentMethod }.distinct().sorted()
                val merchants = txs.map { it.merchant }.distinct().sorted()
                
                _uiState.update {
                    it.copy(
                        allCategories = cats,
                        allSubcategories = subcats,
                        allPaymentMethods = pm,
                        allMerchants = merchants
                    )
                }
            }.collect()
        }

        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true) }
            
            val calendar = Calendar.getInstance()
            val defaultEndDate = calendar.timeInMillis
            val startCalendar = Calendar.getInstance().apply {
                add(Calendar.DAY_OF_YEAR, -30)
                set(Calendar.HOUR_OF_DAY, 0)
                set(Calendar.MINUTE, 0)
                set(Calendar.SECOND, 0)
            }
            val defaultStartDate = startCalendar.timeInMillis

            combine(
                _dateRange.flatMapLatest { range ->
                    val start = range?.first ?: defaultStartDate
                    val end = range?.second ?: defaultEndDate
                    transactionRepository.getTransactionsFlow(start, end)
                },
                budgetRepository.getBudgetsFlow(),
                categoryRepository.getCategoriesFlow(),
                _selectedCategories,
                _selectedSubcategories,
                _selectedPaymentMethods,
                _selectedMerchants,
                _selectedDirection,
                _minAmount,
                _maxAmount,
                _activeGrouping,
                _activeAggregation,
                _activeVisualization
            ) { flowResults ->
                val txList = flowResults[0] as List<Transaction>
                val budgets = flowResults[1] as List<Budget>
                val categories = flowResults[2] as List<Category>
                val selCats = flowResults[3] as Set<String>
                val selSubs = flowResults[4] as Set<String>
                val selPM = flowResults[5] as Set<String>
                val selMerch = flowResults[6] as Set<String>
                val selDirection = flowResults[7] as String
                val minAmt = flowResults[8] as Long?
                val maxAmt = flowResults[9] as Long?
                val grouping = flowResults[10] as String
                val aggregation = flowResults[11] as String
                val visualization = flowResults[12] as String

                // Step 1: Filter transactions
                val filtered = txList.filter { tx ->
                    val matchesCategory = selCats.isEmpty() || selCats.contains(tx.category)
                    val matchesSubcategory = selSubs.isEmpty() || (tx.subcategory != null && selSubs.contains(tx.subcategory))
                    val matchesPayment = selPM.isEmpty() || (tx.paymentMethod != null && selPM.contains(tx.paymentMethod))
                    val matchesMerchant = selMerch.isEmpty() || selMerch.contains(tx.merchant)
                    
                    val absAmt = kotlin.math.abs(tx.amount)
                    val matchesMin = minAmt == null || absAmt >= minAmt
                    val matchesMax = maxAmt == null || absAmt <= maxAmt
                    
                    val matchesDirection = when (selDirection) {
                        "Debit" -> tx.amount >= 0
                        "Credit" -> tx.amount < 0
                        else -> true
                    }

                    matchesCategory && matchesSubcategory && matchesPayment && matchesMerchant && matchesMin && matchesMax && matchesDirection
                }

                // Step 2: Compute summary stats
                val totalExpense = filtered.filter { it.amount >= 0 }.sumOf { it.amount }
                val totalIncome = filtered.filter { it.amount < 0 }.sumOf { kotlin.math.abs(it.amount) }
                val overallSum = filtered.sumOf { kotlin.math.abs(it.amount) }
                val averageDaily = if (filtered.isNotEmpty()) overallSum / 30 else 0L

                // Step 3: Group transactions
                val groups: Map<String, List<Transaction>> = filtered.groupBy { tx ->
                    when (grouping) {
                        "Category" -> tx.category
                        "Subcategory" -> tx.subcategory ?: "No Subcategory"
                        "Merchant" -> tx.merchant
                        "Payment Method" -> tx.paymentMethod ?: "Cash"
                        "Day" -> SimpleDateFormat("MMM dd", Locale.getDefault()).format(Date(tx.timestamp))
                        "Week" -> "Week " + Calendar.getInstance().apply { timeInMillis = tx.timestamp }.get(Calendar.WEEK_OF_YEAR)
                        "Month" -> SimpleDateFormat("MMMM yyyy", Locale.getDefault()).format(Date(tx.timestamp))
                        else -> tx.category
                    }
                }

                // Step 4: Aggregate group values
                val groupedItems = groups.map { (key, groupList) ->
                    val value = when (aggregation) {
                        "Total Spend" -> groupList.sumOf { kotlin.math.abs(it.amount) }
                        "Average" -> if (groupList.isNotEmpty()) groupList.map { kotlin.math.abs(it.amount) }.average().toLong() else 0L
                        "Median" -> {
                            if (groupList.isEmpty()) 0L
                            else {
                                val sorted = groupList.map { kotlin.math.abs(it.amount) }.sorted()
                                if (sorted.size % 2 == 0) {
                                    (sorted[sorted.size / 2] + sorted[sorted.size / 2 - 1]) / 2
                                } else sorted[sorted.size / 2]
                            }
                        }
                        "Min" -> groupList.minOfOrNull { kotlin.math.abs(it.amount) } ?: 0L
                        "Max" -> groupList.maxOfOrNull { kotlin.math.abs(it.amount) } ?: 0L
                        "Count" -> groupList.size.toLong() * 100 // Scale by 100 for cents compatibility
                        else -> groupList.sumOf { kotlin.math.abs(it.amount) }
                    }
                    GroupedItem(key = key, value = value, percentage = 0f)
                }

                val totalAggregatedSum = groupedItems.sumOf { it.value }
                val finalGroupedData = groupedItems.map { item ->
                    val pct = if (totalAggregatedSum > 0) item.value.toFloat() / totalAggregatedSum.toFloat() else 0f
                    item.copy(percentage = pct)
                }.sortedByDescending { it.value }

                // Step 5: Daily Spend trend list (chronological)
                val dateFormat = SimpleDateFormat("MMM dd", Locale.getDefault())
                val dailyMap = mutableMapOf<String, Long>()
                val tempCal = Calendar.getInstance()
                for (i in 0..29) {
                    dailyMap[dateFormat.format(tempCal.time)] = 0L
                    tempCal.add(Calendar.DAY_OF_YEAR, -1)
                }
                filtered.forEach { tx ->
                    val dateKey = dateFormat.format(Date(tx.timestamp))
                    if (dailyMap.containsKey(dateKey)) {
                        dailyMap[dateKey] = dailyMap.getValue(dateKey) + kotlin.math.abs(tx.amount)
                    }
                }
                val dailyList = dailyMap.map { DailySpend(it.key, it.value) }.reversed()

                // Step 6: Budget utilization mapping
                val catMap = categories.associateBy { it.id }
                val budgetList = budgets.map { b ->
                    val catName = catMap[b.categoryId]?.name ?: "Unknown"
                    val spent = filtered.filter { it.category.equals(catName, ignoreCase = true) && it.amount >= 0 }.sumOf { it.amount }
                    val pct = if (b.amount > 0) (spent.toFloat() / b.amount.toFloat()).coerceIn(0f, 1f) else 0f
                    BudgetReportProgress(catName, b.amount, spent, pct)
                }

                // Step 7: Top Purchases
                val largest = filtered.sortedByDescending { kotlin.math.abs(it.amount) }.take(5)

                // Step 8: Frequent Merchants
                val recMap = mutableMapOf<String, Pair<Int, Long>>()
                filtered.forEach { tx ->
                    val current = recMap[tx.merchant] ?: Pair(0, 0L)
                    recMap[tx.merchant] = Pair(current.first + 1, current.second + kotlin.math.abs(tx.amount))
                }
                val recurringList = recMap.map { (m, pair) ->
                    RecurringMerchant(m, pair.first, pair.second)
                }.sortedByDescending { it.count }.take(5)

                ReportsUiState(
                    selectedCategories = selCats,
                    selectedSubcategories = selSubs,
                    selectedPaymentMethods = selPM,
                    selectedMerchants = selMerch,
                    selectedDirection = selDirection,
                    minAmount = minAmt,
                    maxAmount = maxAmt,
                    dateRange = _dateRange.value,
                    activeGrouping = grouping,
                    activeAggregation = aggregation,
                    activeVisualization = visualization,
                    totalExpense = totalExpense,
                    totalIncome = totalIncome,
                    averageDailySpend = averageDaily,
                    dailySpend = dailyList,
                    groupedData = finalGroupedData,
                    budgetUtilization = budgetList,
                    largestPurchases = largest,
                    recurringMerchants = recurringList,
                    filteredTransactions = filtered,
                    isLoading = false,
                    errorMessage = null
                )
            }.catch { e ->
                _uiState.update { it.copy(isLoading = false, errorMessage = e.localizedMessage) }
            }.collect { state ->
                // Maintain allCategories/allSubcategories/etc. lists from local state during collection
                _uiState.value = state.copy(
                    allCategories = _uiState.value.allCategories,
                    allSubcategories = _uiState.value.allSubcategories,
                    allPaymentMethods = _uiState.value.allPaymentMethods,
                    allMerchants = _uiState.value.allMerchants
                )
            }
        }
    }

    // Filter Setters
    fun toggleCategoryFilter(category: String) {
        val current = _selectedCategories.value
        _selectedCategories.value = if (current.contains(category)) current - category else current + category
    }

    fun toggleSubcategoryFilter(subcategory: String) {
        val current = _selectedSubcategories.value
        _selectedSubcategories.value = if (current.contains(subcategory)) current - subcategory else current + subcategory
    }

    fun togglePaymentMethodFilter(pm: String) {
        val current = _selectedPaymentMethods.value
        _selectedPaymentMethods.value = if (current.contains(pm)) current - pm else current + pm
    }

    fun setDirectionFilter(direction: String) {
        _selectedDirection.value = direction
    }

    fun setAmountRange(min: Long?, max: Long?) {
        _minAmount.value = min
        _maxAmount.value = max
    }

    fun setDateRange(start: Long?, end: Long?) {
        if (start != null && end != null) {
            _dateRange.value = Pair(start, end)
        } else {
            _dateRange.value = null
        }
    }

    fun clearFilters() {
        _selectedCategories.value = emptySet()
        _selectedSubcategories.value = emptySet()
        _selectedPaymentMethods.value = emptySet()
        _selectedMerchants.value = emptySet()
        _selectedDirection.value = "All"
        _minAmount.value = null
        _maxAmount.value = null
        _dateRange.value = null
    }

    // Configuration Setters
    fun setGrouping(grouping: String) {
        _activeGrouping.value = grouping
    }

    fun setAggregation(aggregation: String) {
        _activeAggregation.value = aggregation
    }

    fun setVisualization(visualization: String) {
        _activeVisualization.value = visualization
    }

    fun exportTransactionsToCsv(onExportReady: (String) -> Unit) {
        viewModelScope.launch {
            val txList = _uiState.value.filteredTransactions
            
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
