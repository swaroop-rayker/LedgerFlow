package com.ledgerflow.domain.model

data class DashboardSummary(
    val monthlyIncome: Long,
    val monthlyExpense: Long,
    val recentTransactions: List<TransactionWithDetails>,
    val budgetProgress: List<BudgetProgress>
)

data class TransactionWithDetails(
    val transaction: Transaction,
    val merchant: Merchant?,
    val splits: List<TransactionSplit>
)

data class BudgetProgress(
    val budget: Budget,
    val category: Category,
    val spentAmount: Long
)
