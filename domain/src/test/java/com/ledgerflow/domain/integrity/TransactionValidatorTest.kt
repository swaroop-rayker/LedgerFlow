package com.ledgerflow.domain.integrity

import com.ledgerflow.domain.model.Transaction
import org.junit.Assert.assertTrue
import org.junit.Test

class TransactionValidatorTest {

    @Test
    fun testValidTransaction() {
        val transaction = Transaction(
            id = 1,
            amount = 1000L,
            merchant = "Test Merchant",
            category = "Food",
            timestamp = System.currentTimeMillis()
        )
        val result = TransactionValidator.validate(transaction)
        assertTrue(result is TransactionValidator.ValidationResult.Valid)
    }

    @Test
    fun testInvalidTransactionAmount() {
        val transaction = Transaction(
            id = 1,
            amount = 0L,
            merchant = "Test Merchant",
            category = "Food",
            timestamp = System.currentTimeMillis()
        )
        val result = TransactionValidator.validate(transaction)
        assertTrue(result is TransactionValidator.ValidationResult.Invalid)
    }
}
