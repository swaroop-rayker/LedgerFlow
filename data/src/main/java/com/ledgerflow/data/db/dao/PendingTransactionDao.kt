package com.ledgerflow.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.ledgerflow.data.db.entity.PendingTransactionEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface PendingTransactionDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertPendingTransaction(pending: PendingTransactionEntity): Long

    @Query("DELETE FROM pending_transactions WHERE id = :id")
    suspend fun deletePendingTransaction(id: Long)

    @Query("SELECT * FROM pending_transactions WHERE id = :id")
    suspend fun getPendingTransactionById(id: Long): PendingTransactionEntity?

    @Query("SELECT * FROM pending_transactions ORDER BY timestamp DESC")
    fun getPendingTransactionsFlow(): Flow<List<PendingTransactionEntity>>

    @Query("UPDATE pending_transactions SET status = :status, updated_at = :updatedAt WHERE id = :id")
    suspend fun updatePendingTransactionStatus(id: Long, status: String, updatedAt: Long)
}
