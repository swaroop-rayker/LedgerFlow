package com.ledgerflow.domain.usecase

import com.ledgerflow.domain.model.*
import com.ledgerflow.domain.repository.*
import kotlinx.coroutines.flow.first
import java.util.Calendar
import javax.inject.Inject

class GetDashboardSummaryUseCase @Inject constructor(
    private val transactionRepository: TransactionRepository,
    private val categoryRepository: CategoryRepository,
    private val merchantRepository: MerchantRepository,
    private val budgetRepository: BudgetRepository
) {
    suspend operator fun invoke(): Result<DashboardSummary> {
        return try {
            // Determine active month time range
            val calendar = Calendar.getInstance()
            calendar.set(Calendar.DAY_OF_MONTH, 1)
            calendar.set(Calendar.HOUR_OF_DAY, 0)
            calendar.set(Calendar.MINUTE, 0)
            calendar.set(Calendar.SECOND, 0)
            calendar.set(Calendar.MILLISECOND, 0)
            val startOfMonth = calendar.timeInMillis

            calendar.set(Calendar.DAY_OF_MONTH, calendar.getActualMaximum(Calendar.DAY_OF_MONTH))
            calendar.set(Calendar.HOUR_OF_DAY, 23)
            calendar.set(Calendar.MINUTE, 59)
            calendar.set(Calendar.SECOND, 59)
            calendar.set(Calendar.MILLISECOND, 999)
            val endOfMonth = calendar.timeInMillis

            // Fetch transactions for the active month
            val transactionsFlow = transactionRepository.getTransactionsFlow(startOfMonth, endOfMonth)
            val currentMonthTransactions = transactionsFlow.first()

            var incomeSum = 0L
            var expenseSum = 0L

            for (txn in currentMonthTransactions) {
                when (txn.type) {
                    TransactionType.INCOME -> incomeSum += txn.totalAmount
                    TransactionType.EXPENSE -> expenseSum += txn.totalAmount
                    else -> {} // Transfer/Refund can be handled later
                }
            }

            // Fetch last 5 transactions (recent)
            val recentTxns = transactionRepository.getPagedTransactions(limit = 5, offset = 0).let { result ->
                when (result) {
                    is Result.Success -> result.data
                    else -> emptyList()
                }
            }

            val recentWithDetails = recentTxns.map { txn ->
                val merchant = txn.merchantId?.let { id ->
                    when (val res = merchantRepository.getMerchantById(id)) {
                        is Result.Success -> res.data
                        else -> null
                    }
                }
                val splits = when (val res = transactionRepository.getSplitsForTransaction(txn.id)) {
                    is Result.Success -> res.data
                    else -> emptyList()
                }
                TransactionWithDetails(txn, merchant, splits)
            }

            // Fetch budget progress
            val activeBudgets = budgetRepository.getBudgetsFlow().first()
            val categories = categoryRepository.getCategoriesFlow().first().associateBy { it.id }

            val budgetProgressList = activeBudgets.map { budget ->
                val category = categories[budget.categoryId] ?: Category(id = budget.categoryId, name = "Unknown")
                
                // Calculate spent amount from transactions
                val spent = currentMonthTransactions.sumOf { txn ->
                    val splits = when (val res = transactionRepository.getSplitsForTransaction(txn.id)) {
                        is Result.Success -> res.data
                        else -> emptyList()
                    }
                    splits.filter { it.categoryId == budget.categoryId }.sumOf { it.amount }
                }

                BudgetProgress(budget, category, spent)
            }

            Result.Success(
                DashboardSummary(
                    monthlyIncome = incomeSum,
                    monthlyExpense = expenseSum,
                    recentTransactions = recentWithDetails,
                    budgetProgress = budgetProgressList
                )
            )
        } catch (e: Exception) {
            Result.Failure.DatabaseError(e)
        }
    }
}
