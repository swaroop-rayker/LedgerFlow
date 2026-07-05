package com.ledgerflow.domain.usecase

import com.ledgerflow.domain.model.Category
import com.ledgerflow.domain.model.Result
import com.ledgerflow.domain.repository.CategoryRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test

class ValidateTransactionCategoryUseCaseTest {

    private class FakeCategoryRepository(private val categories: List<Category>) : CategoryRepository {
        override suspend fun saveCategory(category: Category): Result<Unit> = Result.Success(Unit)
        override suspend fun deleteCategory(categoryId: Long): Result<Unit> = Result.Success(Unit)
        override suspend fun getCategoryById(categoryId: Long): Result<Category?> = Result.Success(null)
        override fun getCategoriesFlow(): Flow<List<Category>> = flowOf(categories)
        override suspend fun mergeCategories(sourceCategoryId: Long, targetCategoryId: Long): Result<Unit> = Result.Success(Unit)
        override suspend fun archiveCategory(categoryId: Long): Result<Unit> = Result.Success(Unit)
    }

    private val testCategories = listOf(
        Category(id = 1, name = "Food"),
        Category(id = 2, name = "Transport"),
        Category(id = 101, name = "Cafes", parentId = 1),
        Category(id = 102, name = "Fuel", parentId = 2)
    )

    @Test
    fun testValidCategoryAndSubcategory() = runBlocking {
        val repo = FakeCategoryRepository(testCategories)
        val useCase = ValidateTransactionCategoryUseCase(repo)

        val result = useCase("Food", "Cafes")
        assertTrue(result is Result.Success)
    }

    @Test
    fun testBlankCategoryFails() = runBlocking {
        val repo = FakeCategoryRepository(testCategories)
        val useCase = ValidateTransactionCategoryUseCase(repo)

        val result = useCase("", null)
        assertTrue(result is Result.Failure.ValidationError)
    }

    @Test
    fun testNonExistentCategoryFails() = runBlocking {
        val repo = FakeCategoryRepository(testCategories)
        val useCase = ValidateTransactionCategoryUseCase(repo)

        val result = useCase("Entertainment", null)
        assertTrue(result is Result.Failure.ValidationError)
    }

    @Test
    fun testInvalidSubcategoryRelationFails() = runBlocking {
        val repo = FakeCategoryRepository(testCategories)
        val useCase = ValidateTransactionCategoryUseCase(repo)

        // "Fuel" belongs to "Transport", not "Food"
        val result = useCase("Food", "Fuel")
        assertTrue(result is Result.Failure.ValidationError)
    }
}
