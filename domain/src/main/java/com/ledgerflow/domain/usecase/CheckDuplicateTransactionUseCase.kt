package com.ledgerflow.domain.usecase

import com.ledgerflow.domain.model.Result
import com.ledgerflow.domain.repository.TransactionRepository
import javax.inject.Inject

class CheckDuplicateTransactionUseCase @Inject constructor(
    private val transactionRepository: TransactionRepository
) {
    suspend operator fun invoke(
        amount: Long,
        merchant: String,
        timestamp: Long,
        reference: String?
    ): Boolean {
        val res = transactionRepository.getPagedTransactions(limit = 100, offset = 0)
        if (res is Result.Success) {
            return res.data.any { txn ->
                val sameAmount = txn.amount == amount
                val sameMerchant = txn.merchant.equals(merchant, ignoreCase = true)
                val sameReference = reference != null && txn.reference != null && txn.reference.equals(reference, ignoreCase = true)
                val nearTime = Math.abs(txn.timestamp - timestamp) < 300000 // 5 minutes
                
                sameAmount && sameMerchant && (sameReference || nearTime)
            }
        }
        return false
    }
}
