package com.ledgerflow.domain.repository

import com.ledgerflow.domain.model.Budget
import com.ledgerflow.domain.model.Result
import kotlinx.coroutines.flow.Flow

interface BudgetRepository {
    suspend fun saveBudget(budget: Budget): Result<Unit>
    suspend fun deleteBudget(budgetId: Long): Result<Unit>
    suspend fun getBudgetById(budgetId: Long): Result<Budget?>
    fun getBudgetsFlow(): Flow<List<Budget>>
    fun getBudgetsForCategoryFlow(categoryId: Long): Flow<List<Budget>>
}
