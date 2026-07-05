package com.ledgerflow.data.repository

import com.ledgerflow.data.db.dao.PendingTransactionDao
import com.ledgerflow.data.db.entity.PendingTransactionEntity
import com.ledgerflow.domain.model.PendingTransaction
import com.ledgerflow.domain.model.Result
import com.ledgerflow.domain.repository.PendingTransactionRepository
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import javax.inject.Inject

class PendingTransactionRepositoryImpl @Inject constructor(
    private val pendingTransactionDao: PendingTransactionDao,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) : PendingTransactionRepository {

    override suspend fun savePendingTransaction(pending: PendingTransaction): Result<Long> = withContext(ioDispatcher) {
        try {
            val id = pendingTransactionDao.insertPendingTransaction(PendingTransactionEntity.fromDomain(pending))
            Result.Success(id)
        } catch (e: Exception) {
            Result.Failure.DatabaseError(e)
        }
    }

    override suspend fun deletePendingTransaction(id: Long): Result<Unit> = withContext(ioDispatcher) {
        try {
            pendingTransactionDao.deletePendingTransaction(id)
            Result.Success(Unit)
        } catch (e: Exception) {
            Result.Failure.DatabaseError(e)
        }
    }

    override suspend fun getPendingTransactionById(id: Long): Result<PendingTransaction?> = withContext(ioDispatcher) {
        try {
            val entity = pendingTransactionDao.getPendingTransactionById(id)
            Result.Success(entity?.toDomain())
        } catch (e: Exception) {
            Result.Failure.DatabaseError(e)
        }
    }

    override fun getPendingTransactionsFlow(): Flow<List<PendingTransaction>> {
        return pendingTransactionDao.getPendingTransactionsFlow().map { list ->
            list.map { it.toDomain() }
        }
    }

    override suspend fun updatePendingTransactionStatus(id: Long, status: String): Result<Unit> = withContext(ioDispatcher) {
        try {
            pendingTransactionDao.updatePendingTransactionStatus(id, status, System.currentTimeMillis())
            Result.Success(Unit)
        } catch (e: Exception) {
            Result.Failure.DatabaseError(e)
        }
    }
}
