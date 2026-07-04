package com.ledgerflow.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.ledgerflow.data.db.entity.PendingTransactionEntity

@Dao
interface PendingTransactionDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertPendingTransaction(pending: PendingTransactionEntity)

    @Query("SELECT * FROM pending_transactions ORDER BY timestamp DESC")
    suspend fun getAllPendingTransactions(): List<PendingTransactionEntity>

    @Query("DELETE FROM pending_transactions WHERE id = :id")
    suspend fun deletePendingTransaction(id: Long)

    @Query("DELETE FROM pending_transactions")
    suspend fun clearPendingQueue()
}
