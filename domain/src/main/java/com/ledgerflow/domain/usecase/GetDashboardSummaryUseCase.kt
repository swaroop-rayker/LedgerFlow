package com.ledgerflow.domain.usecase

import com.ledgerflow.domain.model.*
import com.ledgerflow.domain.repository.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import java.util.Calendar
import javax.inject.Inject

class GetDashboardSummaryUseCase @Inject constructor(
    private val transactionRepository: TransactionRepository,
    private val categoryRepository: CategoryRepository,
    private val budgetRepository: BudgetRepository,
    private val pendingTransactionRepository: PendingTransactionRepository
) {
    operator fun invoke(): Flow<Result<DashboardSummary>> {
        val nowCalendar = Calendar.getInstance()
        val nowTime = nowCalendar.timeInMillis

        // Start of today
        val todayCalendar = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        val startOfToday = todayCalendar.timeInMillis

        // Start of week
        val weekCalendar = Calendar.getInstance().apply {
            set(Calendar.DAY_OF_WEEK, firstDayOfWeek)
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        val startOfWeek = weekCalendar.timeInMillis

        // Start of month
        val monthCalendar = Calendar.getInstance().apply {
            set(Calendar.DAY_OF_MONTH, 1)
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        val startOfMonth = monthCalendar.timeInMillis
        val endOfMonth = Calendar.getInstance().apply {
            set(Calendar.DAY_OF_MONTH, getActualMaximum(Calendar.DAY_OF_MONTH))
            set(Calendar.HOUR_OF_DAY, 23)
            set(Calendar.MINUTE, 59)
            set(Calendar.SECOND, 59)
            set(Calendar.MILLISECOND, 999)
        }.timeInMillis

        return combine(
            transactionRepository.getTransactionsFlow(startOfMonth, endOfMonth),
            categoryRepository.getCategoriesFlow(),
            budgetRepository.getBudgetsFlow(),
            pendingTransactionRepository.getPendingTransactionsFlow()
        ) { monthTransactions, categories, activeBudgets, pendingTransactions ->
            try {
                val todaySpending = monthTransactions.filter { it.timestamp >= startOfToday }.sumOf { it.amount }
                val weekSpending = monthTransactions.filter { it.timestamp >= startOfWeek }.sumOf { it.amount }
                val monthSpending = monthTransactions.sumOf { it.amount }

                // Top categories calculation
                val categoryByName = categories.associateBy { it.name }
                
                val categoryGroups = monthTransactions.groupBy { it.category }
                val topCategories = categoryGroups.map { (catName, txs) ->
                    val matchingCat = categoryByName[catName]
                    CategorySpending(
                        categoryName = catName,
                        amount = txs.sumOf { it.amount },
                        color = matchingCat?.color,
                        icon = matchingCat?.icon
                    )
                }.sortedByDescending { it.amount }

                // Recent and Largest Expenses
                val recentExpenses = monthTransactions.sortedByDescending { it.timestamp }.take(5)
                val largestExpenses = monthTransactions.sortedByDescending { it.amount }.take(5)

                // Budget Progress
                val categoriesById = categories.associateBy { it.id }

                val budgetProgressList = activeBudgets.map { budget ->
                    val category = categoriesById[budget.categoryId] ?: Category(id = budget.categoryId, name = "Unknown")
                    val spent = monthTransactions
                        .filter { it.category.equals(category.name, ignoreCase = true) }
                        .sumOf { it.amount }

                    BudgetProgress(budget, category, spent)
                }

                // Top Merchant calculation
                val topMerchant = monthTransactions
                    .groupBy { it.merchant }
                    .mapValues { entry -> entry.value.sumOf { it.amount } }
                    .maxByOrNull { it.value }
                    ?.key

                // Largest Expense
                val largestExpense = monthTransactions.maxByOrNull { it.amount }

                // Pending Drafts Count
                val pendingCount = pendingTransactions.size

                Result.Success(
                    DashboardSummary(
                        totalExpenses = monthSpending,
                        todaySpending = todaySpending,
                        thisWeekSpending = weekSpending,
                        thisMonthSpending = monthSpending,
                        topCategories = topCategories,
                        recentExpenses = recentExpenses,
                        largestExpenses = largestExpenses,
                        budgetProgress = budgetProgressList,
                        largestExpense = largestExpense,
                        topMerchant = topMerchant,
                        topCategory = topCategories.firstOrNull()?.categoryName,
                        pendingCount = pendingCount
                    )
                )
            } catch (e: Exception) {
                Result.Failure.DatabaseError(e)
            }
        }
    }
}
