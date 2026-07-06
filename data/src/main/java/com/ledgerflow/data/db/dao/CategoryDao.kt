package com.ledgerflow.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import com.ledgerflow.data.db.entity.CategoryEntity
import com.ledgerflow.data.db.entity.SubcategoryEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface CategoryDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertCategory(category: CategoryEntity): Long

    @Query("DELETE FROM categories WHERE id = :categoryId")
    suspend fun deleteCategory(categoryId: Long)

    @Query("SELECT * FROM categories WHERE id = :categoryId")
    suspend fun getCategoryById(categoryId: Long): CategoryEntity?

    @Query("SELECT * FROM categories ORDER BY name ASC")
    fun getCategoriesFlow(): Flow<List<CategoryEntity>>

    @Query("UPDATE transactions SET category = (SELECT name FROM categories WHERE id = :targetCategoryId) WHERE category = (SELECT name FROM categories WHERE id = :sourceCategoryId)")
    suspend fun updateTransactionCategoryReferences(sourceCategoryId: Long, targetCategoryId: Long)

    @Query("SELECT * FROM subcategories WHERE category_id = :categoryId")
    suspend fun getSubcategoriesByCategoryId(categoryId: Long): List<SubcategoryEntity>

    @Query("DELETE FROM subcategories WHERE id = :id")
    suspend fun deleteSubcategoryRaw(id: Long)

    @Query("UPDATE subcategories SET category_id = :targetCategoryId WHERE id = :id")
    suspend fun reparentSubcategory(id: Long, targetCategoryId: Long)

    @Transaction
    suspend fun mergeCategories(sourceCategoryId: Long, targetCategoryId: Long) {
        // 1. Update all transaction category names from source to target
        updateTransactionCategoryReferences(sourceCategoryId, targetCategoryId)

        // 2. Safeguard against duplicate subcategory name conflicts
        val sourceSubcategories = getSubcategoriesByCategoryId(sourceCategoryId)
        val targetSubcategories = getSubcategoriesByCategoryId(targetCategoryId)
        val targetNames = targetSubcategories.map { it.name.lowercase().trim() }.toSet()

        sourceSubcategories.forEach { sourceSub ->
            if (targetNames.contains(sourceSub.name.lowercase().trim())) {
                // Delete duplicate source subcategory to prevent uniqueness constraint violation
                deleteSubcategoryRaw(sourceSub.id)
            } else {
                // Safely update parent reference
                reparentSubcategory(sourceSub.id, targetCategoryId)
            }
        }

        // 3. Delete source category
        deleteCategory(sourceCategoryId)
    }

    @Query("UPDATE categories SET is_archived = 1 WHERE id = :categoryId")
    suspend fun archiveCategory(categoryId: Long)

    @Query("SELECT * FROM categories WHERE name LIKE '%' || :query || '%' ORDER BY name ASC")
    suspend fun searchCategories(query: String): List<CategoryEntity>
}
