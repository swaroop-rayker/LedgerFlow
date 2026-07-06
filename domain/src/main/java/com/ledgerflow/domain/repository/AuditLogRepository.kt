package com.ledgerflow.domain.repository

import com.ledgerflow.domain.model.AuditLog
import com.ledgerflow.domain.model.Result
import kotlinx.coroutines.flow.Flow

interface AuditLogRepository {
    suspend fun insertAuditLog(log: AuditLog): Result<Unit>
    fun getAuditLogsFlow(): Flow<List<AuditLog>>
    suspend fun getAuditLogs(): Result<List<AuditLog>>
    suspend fun clearAuditLogs(): Result<Unit>
}
