package com.ledgerflow.domain.usecase

import com.ledgerflow.domain.model.*
import com.ledgerflow.domain.repository.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ApprovePendingTransactionUseCaseTest {

    private class FakeTransactionRepository : TransactionRepository {
        val saved = mutableListOf<Transaction>()
        override suspend fun saveTransaction(transaction: Transaction): Result<Unit> {
            saved.add(transaction)
            return Result.Success(Unit)
        }
        override suspend fun deleteTransaction(transactionId: Long): Result<Unit> = Result.Success(Unit)
        override suspend fun getTransactionById(transactionId: Long): Result<Transaction?> = Result.Success(null)
        override fun getTransactionsFlow(startDate: Long, endDate: Long): Flow<List<Transaction>> = flowOf(emptyList())
        override suspend fun getPagedTransactions(limit: Int, offset: Int): Result<List<Transaction>> = Result.Success(saved)
        override fun getRecentCategoriesFlow(limit: Int): Flow<List<String>> = flowOf(emptyList())
        override suspend fun getTransactionsByMerchant(merchant: String): Result<List<Transaction>> = Result.Success(emptyList())
        override suspend fun getTransactionsByCategory(category: String, limit: Int): Result<List<Transaction>> = Result.Success(emptyList())
    }

    private class FakePendingTransactionRepository : PendingTransactionRepository {
        val deletedIds = mutableListOf<Long>()
        override suspend fun savePendingTransaction(pendingTransaction: PendingTransaction): Result<Long> = Result.Success(1L)
        override suspend fun deletePendingTransaction(id: Long): Result<Unit> {
            deletedIds.add(id)
            return Result.Success(Unit)
        }
        override suspend fun getPendingTransactionById(id: Long): Result<PendingTransaction?> = Result.Success(null)
        override fun getPendingTransactionsFlow(): Flow<List<PendingTransaction>> = flowOf(emptyList())
        override suspend fun updatePendingTransactionStatus(id: Long, status: String): Result<Unit> = Result.Success(Unit)
    }

    private class FakeMerchantPreferenceRepository : MerchantPreferenceRepository {
        val preferences = mutableMapOf<String, MerchantPreference>()
        override suspend fun saveMerchantPreference(preference: MerchantPreference): Result<Unit> {
            preferences[preference.merchant.lowercase()] = preference
            return Result.Success(Unit)
        }
        override suspend fun getMerchantPreference(merchant: String): Result<MerchantPreference?> {
            return Result.Success(preferences[merchant.lowercase()])
        }
        override suspend fun incrementUsageCount(merchant: String): Result<Unit> = Result.Success(Unit)
    }

    private class FakeAuditLogRepository : AuditLogRepository {
        val logged = mutableListOf<AuditLog>()
        override suspend fun insertAuditLog(log: AuditLog): Result<Unit> {
            logged.add(log)
            return Result.Success(Unit)
        }
        override fun getAuditLogsFlow(): Flow<List<AuditLog>> = flowOf(logged)
        override suspend fun getAuditLogs(): Result<List<AuditLog>> = Result.Success(logged)
        override suspend fun clearAuditLogs(): Result<Unit> {
            logged.clear()
            return Result.Success(Unit)
        }
    }

    @Test
    fun testApprovePendingTransactionSavesAndDelete() = runBlocking {
        val txRepo = FakeTransactionRepository()
        val pendingRepo = FakePendingTransactionRepository()
        val prefRepo = FakeMerchantPreferenceRepository()
        val auditRepo = FakeAuditLogRepository()

        val useCase = ApprovePendingTransactionUseCase(txRepo, pendingRepo, prefRepo, auditRepo)

        val txn = Transaction(
            id = 0,
            amount = 5000L,
            merchant = "JioMart",
            category = "Shopping",
            subcategory = null,
            paymentMethod = "UPI",
            timestamp = System.currentTimeMillis()
        )

        val result = useCase(pendingId = 42L, approvedTxn = txn)
        assertTrue(result is Result.Success)

        assertEquals(1, txRepo.saved.size)
        assertEquals("JioMart", txRepo.saved[0].merchant)
        assertEquals(42L, pendingRepo.deletedIds[0])

        // Verify audit log
        assertEquals(1, auditRepo.logged.size)
        assertEquals("APPROVE_TRANSACTION", auditRepo.logged[0].operation)
        assertTrue(auditRepo.logged[0].details.contains("JioMart"))

        val prefRes = prefRepo.getMerchantPreference("JioMart")
        assertTrue(prefRes is Result.Success)
        val pref = (prefRes as Result.Success).data
        assertEquals("Shopping", pref?.preferredCategory)
        assertEquals(1, pref?.usageCount)
    }
}
