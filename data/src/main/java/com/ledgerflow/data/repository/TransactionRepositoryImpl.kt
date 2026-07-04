package com.ledgerflow.data.repository

import com.ledgerflow.data.db.dao.TagDao
import com.ledgerflow.data.db.dao.TransactionDao
import com.ledgerflow.data.db.entity.TagEntity
import com.ledgerflow.data.db.entity.TransactionEntity
import com.ledgerflow.data.db.entity.TransactionSplitEntity
import com.ledgerflow.data.db.entity.TransactionTagCrossRef
import com.ledgerflow.domain.model.Result
import com.ledgerflow.domain.model.Tag
import com.ledgerflow.domain.model.Transaction
import com.ledgerflow.domain.model.TransactionSplit
import com.ledgerflow.domain.repository.TransactionRepository
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import javax.inject.Inject

class TransactionRepositoryImpl @Inject constructor(
    private val transactionDao: TransactionDao,
    private val tagDao: TagDao,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) : TransactionRepository {

    override suspend fun saveTransaction(
        transaction: Transaction,
        splits: List<TransactionSplit>,
        tags: List<Tag>
    ): Result<Unit> = withContext(ioDispatcher) {
        try {
            val transactionEntity = TransactionEntity.fromDomain(transaction)
            val splitEntities = splits.map { TransactionSplitEntity.fromDomain(it) }
            
            // Resolve tag IDs
            val tagEntities = tags.map { TagEntity.fromDomain(it) }
            val tagIds = tagDao.getOrCreateTags(tagEntities)
            
            // Construct CrossRefs
            val crossRefs = tagIds.map { tagId ->
                TransactionTagCrossRef(transactionId = transaction.id, tagId = tagId)
            }
            
            transactionDao.saveTransactionWithSplitsAndTags(transactionEntity, splitEntities, crossRefs)
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

    override suspend fun getSplitsForTransaction(transactionId: Long): Result<List<TransactionSplit>> = withContext(ioDispatcher) {
        try {
            val entities = transactionDao.getSplitsForTransaction(transactionId)
            Result.Success(entities.map { it.toDomain() })
        } catch (e: Exception) {
            Result.Failure.DatabaseError(e)
        }
    }

    override suspend fun getTagsForTransaction(transactionId: Long): Result<List<Tag>> = withContext(ioDispatcher) {
        try {
            val entities = tagDao.getTagsForTransaction(transactionId)
            Result.Success(entities.map { it.toDomain() })
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
}
