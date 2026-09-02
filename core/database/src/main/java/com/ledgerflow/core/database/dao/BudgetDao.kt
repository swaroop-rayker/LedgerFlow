package com.ledgerflow.core.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.ledgerflow.core.database.entity.BudgetEntity
import kotlinx.coroutines.flow.Flow

/**
 * Budgets (SPEC.md §5.7).
 *
 * v9 ships only what the backup path needs, because that is what schema v9
 * forces: `ExportCoversEveryTableTest` reads the committed schema and requires
 * a file per table, so a table cannot land without a way to export and restore
 * it. Budget CRUD, progress and alerts arrive with the feature.
 */
@Dao
public interface BudgetDao {

    /** Every row, soft-deleted included — the backup carries the bin too (ADR-0017). */
    @Query("SELECT * FROM budget ORDER BY id")
    public suspend fun all(): List<BudgetEntity>

    @Query("SELECT * FROM budget WHERE deleted_at IS NULL ORDER BY start_date DESC")
    public fun observeLive(): Flow<List<BudgetEntity>>

    @Insert(onConflict = OnConflictStrategy.ABORT)
    public suspend fun insertAll(budgets: List<BudgetEntity>)
}
