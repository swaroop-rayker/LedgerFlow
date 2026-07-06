package com.ledgerflow.presentation.features.transactions

import com.ledgerflow.domain.model.*
import com.ledgerflow.domain.repository.*
import com.ledgerflow.domain.usecase.GetCategoriesWithSubcategoriesUseCase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ExpenseDetailsViewModelTest {

    private val testDispatcher = StandardTestDispatcher()

    private class FakeTransactionRepository : TransactionRepository {
        var txToReturn: Transaction? = null
        val transactionsByMerchant = mutableListOf<Transaction>()
        val transactionsByCategory = mutableListOf<Transaction>()
        var savedTx: Transaction? = null
        var deletedId: Long? = null

        override suspend fun saveTransaction(transaction: Transaction): Result<Unit> {
            savedTx = transaction
            return Result.Success(Unit)
        }
        override suspend fun deleteTransaction(transactionId: Long): Result<Unit> {
            deletedId = transactionId
            return Result.Success(Unit)
        }
        override suspend fun getTransactionById(transactionId: Long): Result<Transaction?> {
            return Result.Success(txToReturn)
        }
        override fun getTransactionsFlow(startDate: Long, endDate: Long): Flow<List<Transaction>> = flowOf(emptyList())
        override suspend fun getPagedTransactions(limit: Int, offset: Int): Result<List<Transaction>> = Result.Success(emptyList())
        override fun getRecentCategoriesFlow(limit: Int): Flow<List<String>> = flowOf(emptyList())
        override suspend fun getTransactionsByMerchant(merchant: String): Result<List<Transaction>> = Result.Success(transactionsByMerchant)
        override suspend fun getTransactionsByCategory(category: String, limit: Int): Result<List<Transaction>> = Result.Success(transactionsByCategory)
    }

    private class FakeMerchantRepository : MerchantRepository {
        var merchantToReturn: Merchant? = null
        override suspend fun saveMerchant(merchant: Merchant): Result<Unit> = Result.Success(Unit)
        override suspend fun getMerchantById(merchantId: Long): Result<Merchant?> = Result.Success(merchantToReturn)
        override suspend fun getMerchantByNormalizedName(normalizedName: String): Result<Merchant?> = Result.Success(merchantToReturn)
        override fun getMerchantsFlow(): Flow<List<Merchant>> = flowOf(emptyList())
        override suspend fun archiveMerchant(merchantId: Long): Result<Unit> = Result.Success(Unit)
    }

    private class FakeCategoryRepository : CategoryRepository {
        val categories = mutableListOf<Category>()
        override suspend fun saveCategory(category: Category): Result<Unit> = Result.Success(Unit)
        override suspend fun deleteCategory(categoryId: Long): Result<Unit> = Result.Success(Unit)
        override suspend fun getCategoryById(categoryId: Long): Result<Category?> = Result.Success(categories.firstOrNull { it.id == categoryId })
        override fun getCategoriesFlow(): Flow<List<Category>> = flowOf(categories)
        override suspend fun mergeCategories(sourceCategoryId: Long, targetCategoryId: Long): Result<Unit> = Result.Success(Unit)
        override suspend fun archiveCategory(categoryId: Long): Result<Unit> = Result.Success(Unit)
    }

    private class FakeAttachmentRepository : AttachmentRepository {
        val attachments = mutableListOf<Attachment>()
        var savedAttach: Attachment? = null
        var deletedId: Long? = null

        override suspend fun saveAttachment(attachment: Attachment): Result<Unit> {
            savedAttach = attachment
            attachments.add(attachment)
            return Result.Success(Unit)
        }
        override suspend fun deleteAttachment(attachmentId: Long): Result<Unit> {
            deletedId = attachmentId
            attachments.removeIf { it.id == attachmentId }
            return Result.Success(Unit)
        }
        override suspend fun getAttachmentsForTransaction(transactionId: Long): Result<List<Attachment>> {
            return Result.Success(attachments)
        }
    }

    private lateinit var transactionRepository: FakeTransactionRepository
    private lateinit var merchantRepository: FakeMerchantRepository
    private lateinit var categoryRepository: FakeCategoryRepository
    private lateinit var attachmentRepository: FakeAttachmentRepository
    private lateinit var viewModel: ExpenseDetailsViewModel

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        transactionRepository = FakeTransactionRepository()
        merchantRepository = FakeMerchantRepository()
        categoryRepository = FakeCategoryRepository()
        attachmentRepository = FakeAttachmentRepository()

        val getCategoriesWithSubcategoriesUseCase = GetCategoriesWithSubcategoriesUseCase(
            categoryRepository,
            transactionRepository
        )
        
        viewModel = ExpenseDetailsViewModel(
            transactionRepository,
            merchantRepository,
            categoryRepository,
            attachmentRepository,
            getCategoriesWithSubcategoriesUseCase
        )
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun testLoadingDetailsSetsStateCorrectly() = runTest {
        val mockTx = Transaction(
            id = 10L,
            amount = 1200L,
            merchant = "Uber",
            category = "Transport",
            timestamp = 1000L
        )
        transactionRepository.txToReturn = mockTx
        transactionRepository.transactionsByMerchant.add(mockTx)

        viewModel.loadExpenseDetails(10L)
        testDispatcher.scheduler.advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals(mockTx, state.transaction)
        assertEquals(1200L, state.totalSpent)
        assertEquals(1200L, state.averageExpense)
        assertEquals(1200L, state.highestExpense)
        assertFalse(state.isLoading)
        assertNull(state.errorMessage)
    }

    @Test
    fun testDeleteAndUndoRestoresTransaction() = runTest {
        val mockTx = Transaction(
            id = 10L,
            amount = 1200L,
            merchant = "Uber",
            category = "Transport",
            timestamp = 1000L
        )
        transactionRepository.txToReturn = mockTx

        viewModel.loadExpenseDetails(10L)
        testDispatcher.scheduler.advanceUntilIdle()

        // Delete
        viewModel.deleteTransaction()
        testDispatcher.scheduler.advanceUntilIdle()

        assertTrue(viewModel.uiState.value.isDeleted)
        assertTrue(viewModel.uiState.value.isUndoAvailable)
        assertEquals(10L, transactionRepository.deletedId)

        // Undo
        viewModel.undoDelete()
        testDispatcher.scheduler.advanceUntilIdle()

        assertFalse(viewModel.uiState.value.isDeleted)
        assertFalse(viewModel.uiState.value.isUndoAvailable)
        assertEquals(mockTx, transactionRepository.savedTx)
    }
}
