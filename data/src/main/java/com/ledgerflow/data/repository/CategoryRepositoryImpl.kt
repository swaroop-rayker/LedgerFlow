package com.ledgerflow.data.repository

import com.ledgerflow.data.db.dao.CategoryDao
import com.ledgerflow.data.db.entity.CategoryEntity
import com.ledgerflow.domain.model.Category
import com.ledgerflow.domain.model.Result
import com.ledgerflow.domain.repository.CategoryRepository
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import javax.inject.Inject

class CategoryRepositoryImpl @Inject constructor(
    private val categoryDao: CategoryDao,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) : CategoryRepository {

    override suspend fun saveCategory(category: Category): Result<Unit> = withContext(ioDispatcher) {
        try {
            categoryDao.insertCategory(CategoryEntity.fromDomain(category))
            Result.Success(Unit)
        } catch (e: Exception) {
            Result.Failure.DatabaseError(e)
        }
    }

    override suspend fun deleteCategory(categoryId: Long): Result<Unit> = withContext(ioDispatcher) {
        try {
            categoryDao.deleteCategory(categoryId)
            Result.Success(Unit)
        } catch (e: Exception) {
            Result.Failure.DatabaseError(e)
        }
    }

    override suspend fun getCategoryById(categoryId: Long): Result<Category?> = withContext(ioDispatcher) {
        try {
            val entity = categoryDao.getCategoryById(categoryId)
            Result.Success(entity?.toDomain())
        } catch (e: Exception) {
            Result.Failure.DatabaseError(e)
        }
    }

    override fun getCategoriesFlow(): Flow<List<Category>> {
        return categoryDao.getCategoriesFlow().map { list ->
            list.map { it.toDomain() }
        }
    }

    override suspend fun mergeCategories(sourceCategoryId: Long, targetCategoryId: Long): Result<Unit> = withContext(ioDispatcher) {
        try {
            categoryDao.mergeCategories(sourceCategoryId, targetCategoryId)
            Result.Success(Unit)
        } catch (e: Exception) {
            Result.Failure.DatabaseError(e)
        }
    }

    override suspend fun archiveCategory(categoryId: Long): Result<Unit> = withContext(ioDispatcher) {
        try {
            categoryDao.archiveCategory(categoryId)
            Result.Success(Unit)
        } catch (e: Exception) {
            Result.Failure.DatabaseError(e)
        }
    }
}
