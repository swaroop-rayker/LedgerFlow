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

    /** Live budgets only — the bin is a storage concern, not a spending one. */
    @Query("SELECT * FROM budget WHERE deleted_at IS NULL ORDER BY start_date DESC")
    public suspend fun live(): List<BudgetEntity>

    @Insert(onConflict = OnConflictStrategy.ABORT)
    public suspend fun insert(budget: BudgetEntity)

    /**
     * Soft delete, matching every other user-authored row in this schema.
     *
     * A budget is user intent and nothing can reconstruct it (ADR-0006), so it
     * goes to the bin rather than being destroyed — and the backup carries the
     * bin (ADR-0017), which is only true if deleting sets a timestamp.
     */
    @Query("UPDATE budget SET deleted_at = :now WHERE id = :id AND deleted_at IS NULL")
    public suspend fun softDelete(id: String, now: Long): Int
}
