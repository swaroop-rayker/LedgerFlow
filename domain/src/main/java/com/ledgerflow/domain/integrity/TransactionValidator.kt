package com.ledgerflow.domain.integrity

import com.ledgerflow.domain.model.Transaction

object TransactionValidator {

    sealed interface ValidationResult {
        data object Valid : ValidationResult
        data class Invalid(val reason: String) : ValidationResult
    }

    /**
     * Validates that a transaction amount is positive and has required fields.
     */
    fun validate(transaction: Transaction): ValidationResult {
        if (transaction.amount <= 0) {
            return ValidationResult.Invalid("Transaction amount must be positive.")
        }
        if (transaction.merchant.isBlank()) {
            return ValidationResult.Invalid("Transaction merchant name cannot be blank.")
        }
        if (transaction.category.isBlank()) {
            return ValidationResult.Invalid("Transaction category cannot be blank.")
        }
        return ValidationResult.Valid
    }
}
