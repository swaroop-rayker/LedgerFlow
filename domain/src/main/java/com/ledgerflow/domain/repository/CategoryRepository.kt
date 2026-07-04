package com.ledgerflow.domain.repository

import com.ledgerflow.domain.model.Category
import com.ledgerflow.domain.model.Result
import kotlinx.coroutines.flow.Flow

interface CategoryRepository {
    suspend fun saveCategory(category: Category): Result<Unit>
    suspend fun deleteCategory(categoryId: Long): Result<Unit>
    suspend fun getCategoryById(categoryId: Long): Result<Category?>
    fun getCategoriesFlow(): Flow<List<Category>>
    suspend fun mergeCategories(sourceCategoryId: Long, targetCategoryId: Long): Result<Unit>
    suspend fun archiveCategory(categoryId: Long): Result<Unit>
}
