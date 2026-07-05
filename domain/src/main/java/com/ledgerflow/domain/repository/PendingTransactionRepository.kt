package com.ledgerflow.domain.repository

import com.ledgerflow.domain.model.PendingTransaction
import com.ledgerflow.domain.model.Result
import kotlinx.coroutines.flow.Flow

interface PendingTransactionRepository {
    suspend fun savePendingTransaction(pending: PendingTransaction): Result<Long>
    suspend fun deletePendingTransaction(id: Long): Result<Unit>
    suspend fun getPendingTransactionById(id: Long): Result<PendingTransaction?>
    fun getPendingTransactionsFlow(): Flow<List<PendingTransaction>>
    suspend fun updatePendingTransactionStatus(id: Long, status: String): Result<Unit>
}
