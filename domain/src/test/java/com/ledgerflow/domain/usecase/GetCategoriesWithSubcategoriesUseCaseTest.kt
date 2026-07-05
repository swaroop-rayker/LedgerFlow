package com.ledgerflow.domain.usecase

import com.ledgerflow.domain.model.Category
import com.ledgerflow.domain.model.Result
import com.ledgerflow.domain.repository.CategoryRepository
import com.ledgerflow.domain.repository.TransactionRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

class GetCategoriesWithSubcategoriesUseCaseTest {

    private class FakeCategoryRepository(private val categories: List<Category>) : CategoryRepository {
        override suspend fun saveCategory(category: Category): Result<Unit> = Result.Success(Unit)
        override suspend fun deleteCategory(categoryId: Long): Result<Unit> = Result.Success(Unit)
        override suspend fun getCategoryById(categoryId: Long): Result<Category?> = Result.Success(null)
        override fun getCategoriesFlow(): Flow<List<Category>> = flowOf(categories)
        override suspend fun mergeCategories(sourceCategoryId: Long, targetCategoryId: Long): Result<Unit> = Result.Success(Unit)
        override suspend fun archiveCategory(categoryId: Long): Result<Unit> = Result.Success(Unit)
    }

    private class FakeTransactionRepository(private val recent: List<String>) : TransactionRepository {
        override suspend fun saveTransaction(transaction: com.ledgerflow.domain.model.Transaction): Result<Unit> = Result.Success(Unit)
        override suspend fun deleteTransaction(transactionId: Long): Result<Unit> = Result.Success(Unit)
        override suspend fun getTransactionById(transactionId: Long): Result<com.ledgerflow.domain.model.Transaction?> = Result.Success(null)
        override fun getTransactionsFlow(startDate: Long, endDate: Long): Flow<List<com.ledgerflow.domain.model.Transaction>> = flowOf(emptyList())
        override suspend fun getPagedTransactions(limit: Int, offset: Int): Result<List<com.ledgerflow.domain.model.Transaction>> = Result.Success(emptyList())
        override fun getRecentCategoriesFlow(limit: Int): Flow<List<String>> = flowOf(recent)
    }

    @Test
    fun testSortingAndGrouping() = runBlocking {
        val categories = listOf(
            Category(id = 1, name = "Food", isPinned = false),
            Category(id = 2, name = "Transport", isPinned = true), // Pinned
            Category(id = 3, name = "Shopping", isPinned = false),
            Category(id = 101, name = "Cafes", parentId = 1, isPinned = false),
            Category(id = 102, name = "Restaurants", parentId = 1, isPinned = true), // Pinned Sub
            Category(id = 103, name = "Metro", parentId = 2, isPinned = false)
        )

        // Shopping is recently used
        val recentCategories = listOf("Shopping")

        val catRepo = FakeCategoryRepository(categories)
        val txRepo = FakeTransactionRepository(recentCategories)
        val useCase = GetCategoriesWithSubcategoriesUseCase(catRepo, txRepo)

        val result = useCase().first()

        // Total of 3 parent categories: Transport (Pinned), Shopping (Recent), Food (Alphabetical remaining)
        assertEquals(3, result.size)

        // Check parent order
        assertEquals("Transport", result[0].category.name) // Pinned
        assertEquals("Shopping", result[1].category.name)  // Recent
        assertEquals("Food", result[2].category.name)      // Alphabetical

        // Check child subcategory order for Food: Restaurants (Pinned sub) -> Cafes (Alphabetical sub)
        val foodTree = result.find { it.category.name == "Food" }!!
        assertEquals(2, foodTree.subcategories.size)
        assertEquals("Restaurants", foodTree.subcategories[0].name) // Pinned child first
        assertEquals("Cafes", foodTree.subcategories[1].name)
    }
}
