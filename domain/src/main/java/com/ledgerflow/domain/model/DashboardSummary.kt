package com.ledgerflow.domain.model

data class DashboardSummary(
    val totalExpenses: Long,
    val todaySpending: Long,
    val thisWeekSpending: Long,
    val thisMonthSpending: Long,
    val topCategories: List<CategorySpending>,
    val recentExpenses: List<Transaction>,
    val largestExpenses: List<Transaction>,
    val budgetProgress: List<BudgetProgress>,
    val largestExpense: Transaction? = null,
    val topMerchant: String? = null,
    val topCategory: String? = null,
    val pendingCount: Int = 0
)

data class CategorySpending(
    val categoryName: String,
    val amount: Long,
    val color: String?,
    val icon: String?
)

data class BudgetProgress(
    val budget: Budget,
    val category: Category,
    val spentAmount: Long
)
