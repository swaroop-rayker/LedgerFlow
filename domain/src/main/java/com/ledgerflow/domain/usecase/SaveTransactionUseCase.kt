package com.ledgerflow.domain.usecase

import com.ledgerflow.domain.model.Result
import com.ledgerflow.domain.model.Tag
import com.ledgerflow.domain.model.Transaction
import com.ledgerflow.domain.model.TransactionSplit
import com.ledgerflow.domain.repository.TransactionRepository
import javax.inject.Inject

class SaveTransactionUseCase @Inject constructor(
    private val transactionRepository: TransactionRepository
) {
    suspend operator fun invoke(
        transaction: Transaction,
        splits: List<TransactionSplit>,
        tags: List<Tag>
    ): Result<Unit> {
        // Validate transaction parameters
        if (transaction.totalAmount <= 0) {
            return Result.Failure.ValidationError("Transaction total amount must be greater than zero.")
        }
        
        if (splits.isEmpty()) {
            return Result.Failure.ValidationError("Transaction must have at least one split.")
        }
        
        // Ensure sum of splits equals total amount
        val splitSum = splits.sumOf { it.amount }
        if (splitSum != transaction.totalAmount) {
            return Result.Failure.ValidationError(
                "The sum of the splits ($splitSum) must equal the transaction total amount (${transaction.totalAmount})."
            )
        }
        
        // Save using repository
        return transactionRepository.saveTransaction(transaction, splits, tags)
    }
}
