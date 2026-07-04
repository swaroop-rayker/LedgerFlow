package com.ledgerflow.data.repository

import com.ledgerflow.data.db.dao.BudgetDao
import com.ledgerflow.data.db.entity.BudgetEntity
import com.ledgerflow.domain.model.Budget
import com.ledgerflow.domain.model.Result
import com.ledgerflow.domain.repository.BudgetRepository
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import javax.inject.Inject

class BudgetRepositoryImpl @Inject constructor(
    private val budgetDao: BudgetDao,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) : BudgetRepository {

    override suspend fun saveBudget(budget: Budget): Result<Unit> = withContext(ioDispatcher) {
        try {
            budgetDao.insertBudget(BudgetEntity.fromDomain(budget))
            Result.Success(Unit)
        } catch (e: Exception) {
            Result.Failure.DatabaseError(e)
        }
    }

    override suspend fun deleteBudget(budgetId: Long): Result<Unit> = withContext(ioDispatcher) {
        try {
            budgetDao.deleteBudget(budgetId)
            Result.Success(Unit)
        } catch (e: Exception) {
            Result.Failure.DatabaseError(e)
        }
    }

    override suspend fun getBudgetById(budgetId: Long): Result<Budget?> = withContext(ioDispatcher) {
        try {
            val entity = budgetDao.getBudgetById(budgetId)
            Result.Success(entity?.toDomain())
        } catch (e: Exception) {
            Result.Failure.DatabaseError(e)
        }
    }

    override fun getBudgetsFlow(): Flow<List<Budget>> {
        return budgetDao.getBudgetsFlow().map { list ->
            list.map { it.toDomain() }
        }
    }

    override fun getBudgetsForCategoryFlow(categoryId: Long): Flow<List<Budget>> {
        return budgetDao.getBudgetsForCategoryFlow(categoryId).map { list ->
            list.map { it.toDomain() }
        }
    }
}
