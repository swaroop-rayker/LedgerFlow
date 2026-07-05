package com.ledgerflow.domain.repository

import com.ledgerflow.domain.model.Result
import com.ledgerflow.domain.model.Transaction
import kotlinx.coroutines.flow.Flow

interface TransactionRepository {
    suspend fun saveTransaction(
        transaction: Transaction
    ): Result<Unit>

    suspend fun deleteTransaction(transactionId: Long): Result<Unit>
    suspend fun getTransactionById(transactionId: Long): Result<Transaction?>
    fun getTransactionsFlow(startDate: Long, endDate: Long): Flow<List<Transaction>>
    
    // For pagination supporting 100k+ transactions
    suspend fun getPagedTransactions(limit: Int, offset: Int): Result<List<Transaction>>

    fun getRecentCategoriesFlow(limit: Int): Flow<List<String>>
}
