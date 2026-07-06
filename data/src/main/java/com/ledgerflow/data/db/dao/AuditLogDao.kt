package com.ledgerflow.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.ledgerflow.data.db.entity.AuditLogEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface AuditLogDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAuditLog(log: AuditLogEntity)

    @Query("SELECT * FROM audit_logs ORDER BY timestamp DESC")
    fun getAuditLogsFlow(): Flow<List<AuditLogEntity>>

    @Query("SELECT * FROM audit_logs ORDER BY timestamp DESC")
    suspend fun getAuditLogs(): List<AuditLogEntity>

    @Query("DELETE FROM audit_logs")
    suspend fun clearAuditLogs()
}
