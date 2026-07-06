package com.ledgerflow.data.repository

import com.ledgerflow.data.db.dao.TransactionDao
import com.ledgerflow.data.db.entity.TransactionEntity
import com.ledgerflow.domain.model.Result
import com.ledgerflow.domain.model.Transaction
import com.ledgerflow.domain.repository.TransactionRepository
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import javax.inject.Inject

class TransactionRepositoryImpl @Inject constructor(
    private val transactionDao: TransactionDao,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) : TransactionRepository {

    override suspend fun saveTransaction(
        transaction: Transaction
    ): Result<Unit> = withContext(ioDispatcher) {
        try {
            val transactionEntity = TransactionEntity.fromDomain(transaction)
            transactionDao.insertTransaction(transactionEntity)
            Result.Success(Unit)
        } catch (e: Exception) {
            Result.Failure.DatabaseError(e)
        }
    }

    override suspend fun deleteTransaction(transactionId: Long): Result<Unit> = withContext(ioDispatcher) {
        try {
            transactionDao.deleteTransaction(transactionId)
            Result.Success(Unit)
        } catch (e: Exception) {
            Result.Failure.DatabaseError(e)
        }
    }

    override suspend fun getTransactionById(transactionId: Long): Result<Transaction?> = withContext(ioDispatcher) {
        try {
            val entity = transactionDao.getTransactionById(transactionId)
            Result.Success(entity?.toDomain())
        } catch (e: Exception) {
            Result.Failure.DatabaseError(e)
        }
    }

    override fun getTransactionsFlow(startDate: Long, endDate: Long): Flow<List<Transaction>> {
        return transactionDao.getTransactionsFlow(startDate, endDate).map { list ->
            list.map { it.toDomain() }
        }
    }

    override suspend fun getPagedTransactions(limit: Int, offset: Int): Result<List<Transaction>> = withContext(ioDispatcher) {
        try {
            val entities = transactionDao.getPagedTransactions(limit, offset)
            Result.Success(entities.map { it.toDomain() })
        } catch (e: Exception) {
            Result.Failure.DatabaseError(e)
        }
    }

    override fun getRecentCategoriesFlow(limit: Int): Flow<List<String>> {
        return transactionDao.getRecentCategoriesFlow(limit)
    }

    override suspend fun getTransactionsByMerchant(merchant: String): Result<List<Transaction>> = withContext(ioDispatcher) {
        try {
            val entities = transactionDao.getTransactionsByMerchant(merchant)
            Result.Success(entities.map { it.toDomain() })
        } catch (e: Exception) {
            Result.Failure.DatabaseError(e)
        }
    }

    override suspend fun getTransactionsByCategory(category: String, limit: Int): Result<List<Transaction>> = withContext(ioDispatcher) {
        try {
            val entities = transactionDao.getTransactionsByCategory(category, limit)
            Result.Success(entities.map { it.toDomain() })
        } catch (e: Exception) {
            Result.Failure.DatabaseError(e)
        }
    }
}
