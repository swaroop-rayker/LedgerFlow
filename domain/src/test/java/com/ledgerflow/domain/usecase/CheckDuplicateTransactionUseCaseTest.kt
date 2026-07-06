package com.ledgerflow.domain.usecase

import com.ledgerflow.domain.model.Result
import com.ledgerflow.domain.model.Transaction
import com.ledgerflow.domain.repository.TransactionRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CheckDuplicateTransactionUseCaseTest {

    private class FakeTransactionRepository(val list: List<Transaction>) : TransactionRepository {
        override suspend fun saveTransaction(transaction: Transaction): Result<Unit> = Result.Success(Unit)
        override suspend fun deleteTransaction(transactionId: Long): Result<Unit> = Result.Success(Unit)
        override suspend fun getTransactionById(transactionId: Long): Result<Transaction?> = Result.Success(null)
        override fun getTransactionsFlow(startDate: Long, endDate: Long): Flow<List<Transaction>> = flowOf(list)
        override suspend fun getPagedTransactions(limit: Int, offset: Int): Result<List<Transaction>> = Result.Success(list)
        override fun getRecentCategoriesFlow(limit: Int): Flow<List<String>> = flowOf(emptyList())
        override suspend fun getTransactionsByMerchant(merchant: String): Result<List<Transaction>> = Result.Success(emptyList())
        override suspend fun getTransactionsByCategory(category: String, limit: Int): Result<List<Transaction>> = Result.Success(emptyList())
    }

    @Test
    fun testCheckDuplicateTransactionCatchesDuplicate() = runBlocking {
        val now = System.currentTimeMillis()
        val existingTxn = Transaction(
            id = 1,
            amount = 1500L,
            merchant = "Swiggy",
            category = "Food",
            subcategory = null,
            paymentMethod = "UPI",
            timestamp = now,
            reference = "TXN123456"
        )
        val txRepo = FakeTransactionRepository(listOf(existingTxn))
        val useCase = CheckDuplicateTransactionUseCase(txRepo)

        val dup1 = useCase(
            amount = 1500L,
            merchant = "Swiggy",
            timestamp = now + 10000,
            reference = "TXN123456"
        )
        assertTrue(dup1)

        val dup2 = useCase(
            amount = 1500L,
            merchant = "swiggy",
            timestamp = now - 60000,
            reference = null
        )
        assertTrue(dup2)

        val dup3 = useCase(
            amount = 2000L,
            merchant = "Swiggy",
            timestamp = now,
            reference = null
        )
        assertFalse(dup3)

        val dup4 = useCase(
            amount = 1500L,
            merchant = "Swiggy",
            timestamp = now + 600000,
            reference = null
        )
        assertFalse(dup4)
    }
}
