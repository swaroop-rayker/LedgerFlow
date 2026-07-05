package com.ledgerflow.presentation.features.review

import com.ledgerflow.domain.model.*
import com.ledgerflow.domain.usecase.*
import com.ledgerflow.domain.repository.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ReviewExpenseViewModelTest {

    private val testDispatcher = StandardTestDispatcher()

    private class FakeCategoryRepository : CategoryRepository {
        val categoriesFlow = MutableSharedFlow<List<Category>>(replay = 1)
        
        init {
            categoriesFlow.tryEmit(emptyList())
        }

        override suspend fun saveCategory(category: Category): Result<Unit> = Result.Success(Unit)
        override suspend fun deleteCategory(categoryId: Long): Result<Unit> = Result.Success(Unit)
        override suspend fun getCategoryById(categoryId: Long): Result<Category?> = Result.Success(null)
        override fun getCategoriesFlow(): Flow<List<Category>> = categoriesFlow
        override suspend fun mergeCategories(sourceCategoryId: Long, targetCategoryId: Long): Result<Unit> = Result.Success(Unit)
        override suspend fun archiveCategory(categoryId: Long): Result<Unit> = Result.Success(Unit)
    }

    private class FakeTransactionRepository : TransactionRepository {
        override suspend fun saveTransaction(transaction: Transaction): Result<Unit> = Result.Success(Unit)
        override suspend fun deleteTransaction(transactionId: Long): Result<Unit> = Result.Success(Unit)
        override suspend fun getTransactionById(transactionId: Long): Result<Transaction?> = Result.Success(null)
        override fun getTransactionsFlow(startDate: Long, endDate: Long): Flow<List<Transaction>> = flowOf(emptyList())
        override suspend fun getPagedTransactions(limit: Int, offset: Int): Result<List<Transaction>> = Result.Success(emptyList())
        override fun getRecentCategoriesFlow(limit: Int): Flow<List<String>> = flowOf(emptyList())
    }

    private class FakePendingTransactionRepository(var pt: PendingTransaction?) : PendingTransactionRepository {
        override suspend fun savePendingTransaction(pendingTransaction: PendingTransaction): Result<Long> = Result.Success(1L)
        override suspend fun deletePendingTransaction(id: Long): Result<Unit> = Result.Success(Unit)
        override suspend fun getPendingTransactionById(id: Long): Result<PendingTransaction?> = Result.Success(pt)
        override fun getPendingTransactionsFlow(): Flow<List<PendingTransaction>> = flowOf(emptyList())
        override suspend fun updatePendingTransactionStatus(id: Long, status: String): Result<Unit> = Result.Success(Unit)
    }

    private class FakeMerchantPreferenceRepository : MerchantPreferenceRepository {
        override suspend fun saveMerchantPreference(preference: MerchantPreference): Result<Unit> = Result.Success(Unit)
        override suspend fun getMerchantPreference(merchant: String): Result<MerchantPreference?> = Result.Success(null)
        override suspend fun incrementUsageCount(merchant: String): Result<Unit> = Result.Success(Unit)
    }

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun testConcurrencyDeletesResetCategory() = runTest {
        val catRepo = FakeCategoryRepository()
        catRepo.categoriesFlow.tryEmit(listOf(Category(id = 1, name = "Food")))

        val txRepo = FakeTransactionRepository()
        val getCatsUseCase = GetCategoriesWithSubcategoriesUseCase(catRepo, txRepo)
        val valUseCase = ValidateTransactionCategoryUseCase(catRepo)

        val ptRepo = FakePendingTransactionRepository(
            PendingTransaction(id = 1, amount = 1000L, merchant = "Swiggy", category = "Food", subcategory = null, timestamp = System.currentTimeMillis())
        )
        val getPtUseCase = GetPendingTransactionByIdUseCase(ptRepo)
        val checkDupUseCase = CheckDuplicateTransactionUseCase(txRepo)
        val approveUseCase = ApprovePendingTransactionUseCase(txRepo, ptRepo, FakeMerchantPreferenceRepository())
        val deletePtUseCase = DeletePendingTransactionUseCase(ptRepo)

        val viewModel = ReviewExpenseViewModel(
            getPtUseCase,
            checkDupUseCase,
            approveUseCase,
            deletePtUseCase,
            getCatsUseCase,
            valUseCase
        )

        viewModel.loadPendingTransaction(1L)
        testDispatcher.scheduler.advanceUntilIdle()

        // Verify initially loaded category
        assertEquals("Food", viewModel.uiState.value.category)

        // Concurrently delete category from database
        catRepo.categoriesFlow.tryEmit(emptyList())
        testDispatcher.scheduler.advanceUntilIdle()

        // Verify fallback to "Others" and error message is displayed
        assertEquals("Others", viewModel.uiState.value.category)
        assertNotNull(viewModel.uiState.value.errorMessage)
        assertTrue(viewModel.uiState.value.errorMessage!!.contains("deleted"))
    }
}
