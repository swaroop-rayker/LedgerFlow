package com.ledgerflow.data.repository

import com.ledgerflow.data.db.dao.AuditLogDao
import com.ledgerflow.data.db.entity.AuditLogEntity
import com.ledgerflow.domain.model.AuditLog
import com.ledgerflow.domain.model.Result
import com.ledgerflow.domain.repository.AuditLogRepository
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import javax.inject.Inject

class AuditLogRepositoryImpl @Inject constructor(
    private val auditLogDao: AuditLogDao,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) : AuditLogRepository {

    override suspend fun insertAuditLog(log: AuditLog): Result<Unit> = withContext(ioDispatcher) {
        try {
            auditLogDao.insertAuditLog(
                AuditLogEntity(
                    id = log.id,
                    operation = log.operation,
                    timestamp = log.timestamp,
                    details = log.details
                )
            )
            Result.Success(Unit)
        } catch (e: Exception) {
            Result.Failure.DatabaseError(e)
        }
    }

    override fun getAuditLogsFlow(): Flow<List<AuditLog>> {
        return auditLogDao.getAuditLogsFlow().map { list ->
            list.map {
                AuditLog(
                    id = it.id,
                    operation = it.operation,
                    timestamp = it.timestamp,
                    details = it.details
                )
            }
        }
    }

    override suspend fun getAuditLogs(): Result<List<AuditLog>> = withContext(ioDispatcher) {
        try {
            val logs = auditLogDao.getAuditLogs().map {
                AuditLog(
                    id = it.id,
                    operation = it.operation,
                    timestamp = it.timestamp,
                    details = it.details
                )
            }
            Result.Success(logs)
        } catch (e: Exception) {
            Result.Failure.DatabaseError(e)
        }
    }

    override suspend fun clearAuditLogs(): Result<Unit> = withContext(ioDispatcher) {
        try {
            auditLogDao.clearAuditLogs()
            Result.Success(Unit)
        } catch (e: Exception) {
            Result.Failure.DatabaseError(e)
        }
    }
}
