package com.ledgerflow.domain.integrity

import com.ledgerflow.domain.model.Transaction
import com.ledgerflow.domain.model.TransactionSplit
import com.ledgerflow.domain.model.TransactionType
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar

class TransactionValidatorTest {

    @Test
    fun testValidTransaction() {
        val transaction = Transaction(
            id = 1,
            timestamp = Calendar.getInstance().timeInMillis,
            totalAmount = 1000L, // 10.00
            type = TransactionType.EXPENSE,
            paymentMethodId = 1,
            merchantId = 1,
            notes = "Test"
        )
        val splits = listOf(
            TransactionSplit(id = 1, transactionId = 1, categoryId = 1, amount = 600L),
            TransactionSplit(id = 2, transactionId = 1, categoryId = 2, amount = 400L)
        )
        val result = TransactionValidator.validate(transaction, splits)
        assertTrue(result is TransactionValidator.ValidationResult.Valid)
    }

    @Test
    fun testInvalidSplitsSum() {
        val transaction = Transaction(
            id = 1,
            timestamp = Calendar.getInstance().timeInMillis,
            totalAmount = 1000L,
            type = TransactionType.EXPENSE,
            paymentMethodId = 1,
            merchantId = 1,
            notes = "Test"
        )
        val splits = listOf(
            TransactionSplit(id = 1, transactionId = 1, categoryId = 1, amount = 600L),
            TransactionSplit(id = 2, transactionId = 1, categoryId = 2, amount = 500L) // Sum is 1100
        )
        val result = TransactionValidator.validate(transaction, splits)
        assertTrue(result is TransactionValidator.ValidationResult.Invalid)
    }
}
