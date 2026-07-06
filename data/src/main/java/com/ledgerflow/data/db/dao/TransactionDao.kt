package com.ledgerflow.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.ledgerflow.data.db.entity.TransactionEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface TransactionDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertTransaction(transaction: TransactionEntity): Long

    @Query("DELETE FROM transactions WHERE id = :transactionId")
    suspend fun deleteTransaction(transactionId: Long)

    @Query("SELECT * FROM transactions WHERE id = :transactionId")
    suspend fun getTransactionById(transactionId: Long): TransactionEntity?

    @Query("SELECT * FROM transactions WHERE timestamp >= :startDate AND timestamp <= :endDate ORDER BY timestamp DESC")
    fun getTransactionsFlow(startDate: Long, endDate: Long): Flow<List<TransactionEntity>>

    @Query("SELECT * FROM transactions ORDER BY timestamp DESC LIMIT :limit OFFSET :offset")
    suspend fun getPagedTransactions(limit: Int, offset: Int): List<TransactionEntity>

    @Query("SELECT DISTINCT category FROM transactions ORDER BY timestamp DESC LIMIT :limit")
    fun getRecentCategoriesFlow(limit: Int): Flow<List<String>>

    @Query("SELECT * FROM transactions WHERE merchant LIKE '%' || :query || '%' OR (notes IS NOT NULL AND notes LIKE '%' || :query || '%') ORDER BY timestamp DESC")
    suspend fun searchTransactions(query: String): List<TransactionEntity>

    @Query("SELECT * FROM transactions WHERE merchant = :merchant ORDER BY timestamp DESC")
    suspend fun getTransactionsByMerchant(merchant: String): List<TransactionEntity>

    @Query("SELECT * FROM transactions WHERE category = :category ORDER BY timestamp DESC LIMIT :limit")
    suspend fun getTransactionsByCategory(category: String, limit: Int): List<TransactionEntity>
}
