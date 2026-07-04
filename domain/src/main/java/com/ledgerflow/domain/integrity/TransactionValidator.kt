package com.ledgerflow.domain.integrity

import com.ledgerflow.domain.model.Transaction
import com.ledgerflow.domain.model.TransactionSplit

object TransactionValidator {

    sealed interface ValidationResult {
        data object Valid : ValidationResult
        data class Invalid(val reason: String) : ValidationResult
    }

    /**
     * Validates that a transaction amount is positive and matches the sum of its splits.
     */
    fun validate(transaction: Transaction, splits: List<TransactionSplit>): ValidationResult {
        if (transaction.totalAmount <= 0) {
            return ValidationResult.Invalid("Transaction total amount must be positive.")
        }

        if (splits.isEmpty()) {
            return ValidationResult.Invalid("Transaction must have at least one split.")
        }

        val splitSum = splits.sumOf { it.amount }
        if (splitSum != transaction.totalAmount) {
            return ValidationResult.Invalid(
                "Split amount sum ($splitSum) does not match parent transaction total amount (${transaction.totalAmount})."
            )
        }

        for (split in splits) {
            if (split.amount <= 0) {
                return ValidationResult.Invalid("Individual split amounts must be positive.")
            }
        }

        return ValidationResult.Valid
    }
}
