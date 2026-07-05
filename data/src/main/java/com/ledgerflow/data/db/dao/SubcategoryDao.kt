package com.ledgerflow.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.ledgerflow.data.db.entity.SubcategoryEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface SubcategoryDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSubcategory(subcategory: SubcategoryEntity)

    @Query("DELETE FROM subcategories WHERE id = :id")
    suspend fun deleteSubcategory(id: Long)

    @Query("SELECT * FROM subcategories ORDER BY name ASC")
    fun getSubcategoriesFlow(): Flow<List<SubcategoryEntity>>

    @Query("SELECT * FROM subcategories WHERE id = :id")
    suspend fun getSubcategoryById(id: Long): SubcategoryEntity?

    @Query("UPDATE subcategories SET is_archived = 1 WHERE id = :id")
    suspend fun archiveSubcategory(id: Long)
}
