package com.ledgerflow.presentation.features.transactions

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
class TransactionDetailViewModelTest {

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
        val saved = mutableListOf<Transaction>()
        override suspend fun saveTransaction(transaction: Transaction): Result<Unit> {
            saved.add(transaction)
            return Result.Success(Unit)
        }
        override suspend fun deleteTransaction(transactionId: Long): Result<Unit> = Result.Success(Unit)
        override suspend fun getTransactionById(transactionId: Long): Result<Transaction?> = Result.Success(null)
        override fun getTransactionsFlow(startDate: Long, endDate: Long): Flow<List<Transaction>> = flowOf(emptyList())
        override suspend fun getPagedTransactions(limit: Int, offset: Int): Result<List<Transaction>> = Result.Success(emptyList())
        override fun getRecentCategoriesFlow(limit: Int): Flow<List<String>> = flowOf(emptyList())
        override suspend fun getTransactionsByMerchant(merchant: String): Result<List<Transaction>> = Result.Success(emptyList())
        override suspend fun getTransactionsByCategory(category: String, limit: Int): Result<List<Transaction>> = Result.Success(emptyList())
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
    fun testCategoryDeletionSafeguardResetsToOthers() = runTest {
        val catRepo = FakeCategoryRepository()
        catRepo.categoriesFlow.tryEmit(listOf(Category(id = 1, name = "Food")))

        val txRepo = FakeTransactionRepository()
        val getCatsUseCase = GetCategoriesWithSubcategoriesUseCase(catRepo, txRepo)
        val valUseCase = ValidateTransactionCategoryUseCase(catRepo)
        val saveUseCase = SaveTransactionUseCase(txRepo)

        val viewModel = TransactionDetailViewModel(
            saveUseCase,
            getCatsUseCase,
            valUseCase
        )

        // Select Food category
        viewModel.onCategoryChanged("Food")
        testDispatcher.scheduler.advanceUntilIdle()

        assertEquals("Food", viewModel.uiState.value.category)

        // Concurrently delete category Food
        catRepo.categoriesFlow.tryEmit(emptyList())
        testDispatcher.scheduler.advanceUntilIdle()

        // Verify fallback to "Others" and error message is displayed
        assertEquals("Others", viewModel.uiState.value.category)
        assertNotNull(viewModel.uiState.value.errorMessage)
        assertTrue(viewModel.uiState.value.errorMessage!!.contains("deleted"))
    }
}
