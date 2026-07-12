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
class MultiCategorySplitViewModelTest {

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
        val savedTransactions = mutableListOf<Transaction>()

        override suspend fun saveTransaction(transaction: Transaction): Result<Unit> {
            savedTransactions.add(transaction)
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

    private lateinit var catRepo: FakeCategoryRepository
    private lateinit var txRepo: FakeTransactionRepository
    private lateinit var saveTxUseCase: SaveTransactionUseCase
    private lateinit var getCatsUseCase: GetCategoriesWithSubcategoriesUseCase
    private lateinit var validateCatUseCase: ValidateTransactionCategoryUseCase
    private lateinit var viewModel: MultiCategorySplitViewModel

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        catRepo = FakeCategoryRepository()
        txRepo = FakeTransactionRepository()
        
        // Setup initial default categories
        catRepo.categoriesFlow.tryEmit(listOf(
            Category(id = 1, name = "Others"),
            Category(id = 2, name = "Food"),
            Category(id = 3, name = "Shopping")
        ))

        saveTxUseCase = SaveTransactionUseCase(txRepo)
        getCatsUseCase = GetCategoriesWithSubcategoriesUseCase(catRepo, txRepo)
        validateCatUseCase = ValidateTransactionCategoryUseCase(catRepo)

        viewModel = MultiCategorySplitViewModel(
            saveTxUseCase,
            getCatsUseCase,
            validateCatUseCase
        )
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun testInitialState() = runTest {
        val state = viewModel.uiState.value
        assertEquals("", state.merchant)
        assertEquals("Cash", state.paymentMethod)
        assertTrue(state.items.isEmpty())
        assertFalse(state.isSaved)
        assertNull(state.errorMessage)
    }

    @Test
    fun testAddManualSplitAndRemove() = runTest {
        viewModel.addManualSplit()
        var state = viewModel.uiState.value
        assertEquals(1, state.items.size)
        assertEquals("Split Allocation #1", state.items[0].name)
        assertEquals(0.0, state.items[0].amount, 0.001)
        assertEquals("Others", state.items[0].category)

        val id = state.items[0].id
        viewModel.updateItemName(id, "Coffee")
        viewModel.updateItemAmount(id, 120.0)
        viewModel.updateItemCategory(id, "Food", null)

        state = viewModel.uiState.value
        assertEquals("Coffee", state.items[0].name)
        assertEquals(120.0, state.items[0].amount, 0.001)
        assertEquals("Food", state.items[0].category)

        viewModel.removeItem(id)
        state = viewModel.uiState.value
        assertTrue(state.items.isEmpty())
    }

    @Test
    fun testParseOcrText() = runTest {
        val ocrReceiptText = """
            Starbucks Coffee
            Mocha Latte: 250.00
            Croissant: 180.00
            Subtotal: 430.00
            GST: 21.50
            Total: 451.50
        """.trimIndent()

        viewModel.parseOcrText(ocrReceiptText)
        val state = viewModel.uiState.value
        assertEquals(2, state.items.size)
        
        assertEquals("Mocha Latte", state.items[0].name)
        assertEquals(250.0, state.items[0].amount, 0.001)
        
        assertEquals("Croissant", state.items[1].name)
        assertEquals(180.0, state.items[1].amount, 0.001)
    }

    @Test
    fun testLoadDemoOcr() = runTest {
        viewModel.loadDemoOcr()
        val state = viewModel.uiState.value
        assertEquals(5, state.items.size)
        assertEquals("Whole Wheat Bread", state.items[0].name)
        assertEquals(45.0, state.items[0].amount, 0.001)
        assertEquals("Dark Chocolate", state.items[4].name)
        assertEquals(120.0, state.items[4].amount, 0.001)
    }

    @Test
    fun testSaveSplitsMerchantMissing() = runTest {
        viewModel.addManualSplit()
        val id = viewModel.uiState.value.items[0].id
        viewModel.updateItemAmount(id, 100.0)
        
        viewModel.saveSplits()
        testDispatcher.scheduler.advanceUntilIdle()

        val state = viewModel.uiState.value
        assertFalse(state.isSaved)
        assertEquals("Merchant name is mandatory.", state.errorMessage)
    }

    @Test
    fun testSaveSplitsSuccess() = runTest {
        viewModel.onMerchantChanged("D-Mart")
        viewModel.onPaymentMethodChanged("UPI")
        
        viewModel.addManualSplit()
        viewModel.addManualSplit()
        
        val id1 = viewModel.uiState.value.items[0].id
        val id2 = viewModel.uiState.value.items[1].id
        
        viewModel.updateItemName(id1, "Apples")
        viewModel.updateItemAmount(id1, 150.0)
        viewModel.updateItemCategory(id1, "Food", null)

        viewModel.updateItemName(id2, "T-Shirt")
        viewModel.updateItemAmount(id2, 450.0)
        viewModel.updateItemCategory(id2, "Shopping", null)

        viewModel.saveSplits()
        testDispatcher.scheduler.advanceUntilIdle()

        val state = viewModel.uiState.value
        assertTrue(state.isSaved)
        assertNull(state.errorMessage)

        assertEquals(2, txRepo.savedTransactions.size)
        
        assertEquals("D-Mart", txRepo.savedTransactions[0].merchant)
        assertEquals(15000L, txRepo.savedTransactions[0].amount) // 150.0 * 100 = 15000 cents
        assertEquals("Food", txRepo.savedTransactions[0].category)
        assertEquals("UPI", txRepo.savedTransactions[0].paymentMethod)

        assertEquals("D-Mart", txRepo.savedTransactions[1].merchant)
        assertEquals(45000L, txRepo.savedTransactions[1].amount) // 450.0 * 100 = 45000 cents
        assertEquals("Shopping", txRepo.savedTransactions[1].category)
        assertEquals("UPI", txRepo.savedTransactions[1].paymentMethod)
    }
}
