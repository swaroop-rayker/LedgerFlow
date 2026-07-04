package com.ledgerflow.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import com.ledgerflow.data.db.entity.TransactionEntity
import com.ledgerflow.data.db.entity.TransactionSplitEntity
import com.ledgerflow.data.db.entity.TransactionTagCrossRef
import kotlinx.coroutines.flow.Flow

@Dao
interface TransactionDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertTransaction(transaction: TransactionEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSplits(splits: List<TransactionSplitEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertTransactionTags(crossRefs: List<TransactionTagCrossRef>)

    @Query("DELETE FROM transaction_tags WHERE transaction_id = :transactionId")
    suspend fun deleteTransactionTags(transactionId: Long)

    @Query("DELETE FROM transactions WHERE id = :transactionId")
    suspend fun deleteTransaction(transactionId: Long)

    @Transaction
    suspend fun saveTransactionWithSplitsAndTags(
        transaction: TransactionEntity,
        splits: List<TransactionSplitEntity>,
        crossRefs: List<TransactionTagCrossRef>
    ) {
        val transactionId = insertTransaction(transaction)
        val updatedSplits = splits.map { it.copy(transactionId = transactionId) }
        val updatedRefs = crossRefs.map { it.copy(transactionId = transactionId) }
        
        insertSplits(updatedSplits)
        deleteTransactionTags(transactionId)
        insertTransactionTags(updatedRefs)
    }

    @Query("SELECT * FROM transactions WHERE id = :transactionId")
    suspend fun getTransactionById(transactionId: Long): TransactionEntity?

    @Query("SELECT * FROM transaction_splits WHERE transaction_id = :transactionId")
    suspend fun getSplitsForTransaction(transactionId: Long): List<TransactionSplitEntity>

    @Query("SELECT * FROM transaction_tags WHERE transaction_id = :transactionId")
    suspend fun getTagsForTransaction(transactionId: Long): List<TransactionTagCrossRef>

    @Query("SELECT * FROM transactions WHERE timestamp >= :startDate AND timestamp <= :endDate ORDER BY timestamp DESC")
    fun getTransactionsFlow(startDate: Long, endDate: Long): Flow<List<TransactionEntity>>

    @Query("SELECT * FROM transactions ORDER BY timestamp DESC LIMIT :limit OFFSET :offset")
    suspend fun getPagedTransactions(limit: Int, offset: Int): List<TransactionEntity>
}
