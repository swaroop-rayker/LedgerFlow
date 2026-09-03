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

    @Query("UPDATE budget SET amount_minor = :amountMinor WHERE id = :id AND deleted_at IS NULL")
    public suspend fun updateAmount(id: String, amountMinor: Long): Int

    /**
     * Is this category already budgeted?
     *
     * `IS` rather than `=` for the subcategory, because SQL's `=` is never true
     * against `NULL` — with `=`, a second whole-category budget would find no
     * existing row and the uniqueness rule §5.7 relies on would silently admit
     * duplicates. Exactly the class of defect §6.1.1 removed elsewhere by
     * replacing nulls with sentinels; this column kept its nullable form
     * (there is no unique index to satisfy), so the query carries the burden.
     */
    @Query(
        "SELECT COUNT(*) > 0 FROM budget WHERE deleted_at IS NULL " +
            "AND category_id = :categoryId AND subcategory_id IS :subcategoryId",
    )
    public suspend fun exists(categoryId: String, subcategoryId: String?): Boolean
}
