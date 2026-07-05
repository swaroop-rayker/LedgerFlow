package com.ledgerflow.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import com.ledgerflow.data.db.entity.CategoryEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface CategoryDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertCategory(category: CategoryEntity)

    @Query("DELETE FROM categories WHERE id = :categoryId")
    suspend fun deleteCategory(categoryId: Long)

    @Query("SELECT * FROM categories WHERE id = :categoryId")
    suspend fun getCategoryById(categoryId: Long): CategoryEntity?

    @Query("SELECT * FROM categories ORDER BY name ASC")
    fun getCategoriesFlow(): Flow<List<CategoryEntity>>

    @Query("UPDATE transactions SET category = (SELECT name FROM categories WHERE id = :targetCategoryId) WHERE category = (SELECT name FROM categories WHERE id = :sourceCategoryId)")
    suspend fun updateTransactionCategoryReferences(sourceCategoryId: Long, targetCategoryId: Long)

    @Query("UPDATE subcategories SET category_id = :targetCategoryId WHERE category_id = :sourceCategoryId")
    suspend fun updateSubcategoryParentReferences(sourceCategoryId: Long, targetCategoryId: Long)

    @Transaction
    suspend fun mergeCategories(sourceCategoryId: Long, targetCategoryId: Long) {
        updateTransactionCategoryReferences(sourceCategoryId, targetCategoryId)
        updateSubcategoryParentReferences(sourceCategoryId, targetCategoryId)
        deleteCategory(sourceCategoryId)
    }

    @Query("UPDATE categories SET is_archived = 1 WHERE id = :categoryId")
    suspend fun archiveCategory(categoryId: Long)
}
