package com.ledgerflow.domain.repository

import com.ledgerflow.domain.model.Result
import com.ledgerflow.domain.model.Tag
import com.ledgerflow.domain.model.Transaction
import com.ledgerflow.domain.model.TransactionSplit
import kotlinx.coroutines.flow.Flow

interface TransactionRepository {
    suspend fun saveTransaction(
        transaction: Transaction,
        splits: List<TransactionSplit>,
        tags: List<Tag>
    ): Result<Unit>

    suspend fun deleteTransaction(transactionId: Long): Result<Unit>
    suspend fun getTransactionById(transactionId: Long): Result<Transaction?>
    suspend fun getSplitsForTransaction(transactionId: Long): Result<List<TransactionSplit>>
    suspend fun getTagsForTransaction(transactionId: Long): Result<List<Tag>>
    fun getTransactionsFlow(startDate: Long, endDate: Long): Flow<List<Transaction>>
    
    // For pagination supporting 100k+ transactions
    suspend fun getPagedTransactions(limit: Int, offset: Int): Result<List<Transaction>>
}
