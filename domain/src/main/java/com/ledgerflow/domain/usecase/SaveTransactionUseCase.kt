package com.ledgerflow.domain.usecase

import com.ledgerflow.domain.model.Result
import com.ledgerflow.domain.model.Transaction
import com.ledgerflow.domain.repository.TransactionRepository
import javax.inject.Inject

class SaveTransactionUseCase @Inject constructor(
    private val transactionRepository: TransactionRepository
) {
    suspend operator fun invoke(
        transaction: Transaction
    ): Result<Unit> {
        if (transaction.amount <= 0) {
            return Result.Failure.ValidationError("Expense amount must be greater than zero.")
        }
        if (transaction.merchant.isBlank()) {
            return Result.Failure.ValidationError("Merchant name is mandatory.")
        }
        if (transaction.category.isBlank()) {
            return Result.Failure.ValidationError("Category is mandatory.")
        }
        
        return transactionRepository.saveTransaction(transaction)
    }
}
