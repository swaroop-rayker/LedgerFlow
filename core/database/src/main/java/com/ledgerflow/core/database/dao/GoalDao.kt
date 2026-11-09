package com.ledgerflow.core.database.dao

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.ledgerflow.core.database.entity.GoalContributionEntity
import com.ledgerflow.core.database.entity.SavingsGoalEntity
import com.ledgerflow.core.model.LedgerType
import com.ledgerflow.core.model.Money
import kotlinx.coroutines.flow.Flow

/**
 * Savings goals and their hand-added amounts (V2-PLAN §4.1, schema v15).
 *
 * **No `SUM` over `goal_contribution.amount_minor` here.**
 * `LedgerIsolationTest` holds every `SUM(amount_minor)` to a book's view, so
 * that no statement can net a ledger's money; a goal's few rows are summed in
 * Kotlin instead, which keeps that guard whole.
 */
@Dao
public interface GoalDao {

    @Query("SELECT * FROM savings_goal ORDER BY sort_order, created_at")
    public fun observeGoals(): Flow<List<SavingsGoalEntity>>

    @Query("SELECT * FROM savings_goal WHERE id = :id")
    public suspend fun goal(id: String): SavingsGoalEntity?

    @Query("SELECT COALESCE(MAX(sort_order), -1) FROM savings_goal")
    public suspend fun maxSortOrder(): Int

    @Insert(onConflict = OnConflictStrategy.ABORT)
    public suspend fun insertGoal(goal: SavingsGoalEntity)

    /** The repository reads the row, changes what the form owns, and writes it back. */
    @Update
    public suspend fun updateGoal(goal: SavingsGoalEntity): Int

    /** Its contributions go with it (`ON DELETE CASCADE`). */
    @Query("DELETE FROM savings_goal WHERE id = :id")
    public suspend fun deleteGoal(id: String): Int

    /**
     * A category's entries moved to another on delete: the goals linked to it
     * follow, in the same transaction (owner, 2026-11-09).
     */
    @Query(
        "UPDATE savings_goal SET linked_category_id = :to, updated_at = :now " +
            "WHERE linked_category_id = :from",
    )
    public suspend fun repointLinkedCategory(from: String, to: String, now: Long): Int

    @Query("SELECT * FROM goal_contribution ORDER BY local_date DESC, created_at DESC")
    public fun observeContributions(): Flow<List<GoalContributionEntity>>

    @Query("SELECT * FROM goal_contribution WHERE id = :id")
    public suspend fun contribution(id: String): GoalContributionEntity?

    @Insert(onConflict = OnConflictStrategy.ABORT)
    public suspend fun insertContribution(contribution: GoalContributionEntity)

    @Query(
        "UPDATE goal_contribution SET amount_minor = :amountMinor, local_date = :localDate, " +
            "note = :note, updated_at = :now WHERE id = :id",
    )
    public suspend fun updateContribution(
        id: String,
        amountMinor: Money,
        localDate: Int,
        note: String?,
        now: Long,
    ): Int

    @Query("DELETE FROM goal_contribution WHERE id = :id")
    public suspend fun deleteContribution(id: String): Int

    /**
     * What each goal linked to one of [ledger]'s categories has collected
     * there, from its own `count_from` (or [from], if later) to [to].
     *
     * **One book per statement** (Law 2): the category's `ledger_scope` and
     * the rollup's `ledger` are both bound to [ledger], so a goal's figure can
     * never mix the books, and the repository asks once per book. A category
     * counts its subcategories, because the rollup's `category_id` is always
     * the parent; a subcategory counts only itself. Line grain comes from the
     * rollup (ADR-0018), as a budget's does.
     */
    @Query(
        "SELECT g.id AS goal_id, COALESCE(SUM(r.sum_minor), 0) AS total_minor " +
            "FROM savings_goal g " +
            "JOIN category c ON c.id = g.linked_category_id AND c.ledger_scope = :ledger " +
            "JOIN daily_rollup r ON r.ledger = :ledger " +
            "AND ((c.parent_id IS NULL AND r.category_id = c.id) " +
            "OR (c.parent_id IS NOT NULL AND r.subcategory_id = c.id)) " +
            "AND r.local_date >= MAX(g.count_from, :from) AND r.local_date <= :to " +
            "GROUP BY g.id",
    )
    public fun observeLinkedTotals(ledger: LedgerType, from: Int, to: Int): Flow<List<GoalLinkedTotalRow>>

    // Backup (`DatabaseBackupManager`) only.

    @Query("SELECT * FROM savings_goal ORDER BY id")
    public suspend fun allGoals(): List<SavingsGoalEntity>

    @Query("SELECT * FROM goal_contribution ORDER BY id")
    public suspend fun allContributions(): List<GoalContributionEntity>

    @Insert(onConflict = OnConflictStrategy.ABORT)
    public suspend fun insertAllGoals(goals: List<SavingsGoalEntity>)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    public suspend fun insertAllContributions(contributions: List<GoalContributionEntity>)
}

/** One goal's total from its linked category, over one window. */
public data class GoalLinkedTotalRow(
    @ColumnInfo(name = "goal_id") val goalId: String,
    @ColumnInfo(name = "total_minor") val totalMinor: Money,
)
