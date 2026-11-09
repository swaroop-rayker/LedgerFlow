package com.ledgerflow.core.database.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import com.ledgerflow.core.model.Money

/**
 * A savings goal (V2-PLAN §4.1, schema v15).
 *
 * **Not a third ledger.** Nothing here is money that moved: it is a target and
 * a plan, so no ledger query reads it and no book's total includes it (Law 2).
 * Progress is the goal's hand-added [GoalContributionEntity] rows plus, when
 * [linkedCategoryId] is set, what that category's book holds from [countFrom]
 * on, read from `daily_rollup` with the category's own book bound.
 *
 * **No foreign key on [linkedCategoryId]**, as `budget.category_id` has none
 * (ADR-0016): the taxonomy's delete runs a reassign-or-block rule in code, and
 * a goal follows a reassignment there (owner, 2026-11-09). A `SET NULL` would
 * quietly unlink a goal the code meant to move.
 *
 * **Hard-deleted**, unlike `budget` (owner, 2026-11-09): a goal is not ledger
 * data and nothing points at it but its own contributions, which go with it.
 */
@Entity(
    tableName = "savings_goal",
    indices = [
        // A category delete re-points the goals linked to it.
        Index(value = ["linked_category_id"]),
    ],
)
public data class SavingsGoalEntity(
    @PrimaryKey
    @ColumnInfo(name = "id")
    val id: String,

    @ColumnInfo(name = "name")
    val name: String,

    /** Base currency, always (SPEC.md §5.8). Positive; the repository refuses zero. */
    @ColumnInfo(name = "target_minor")
    val targetMinor: Money,

    /** Days since epoch, or null for a goal with no date. */
    @ColumnInfo(name = "target_date")
    val targetDate: Int?,

    /**
     * A category or a subcategory. A category counts its subcategories too.
     * Its `ledger_scope` is the book the goal reads.
     */
    @ColumnInfo(name = "linked_category_id")
    val linkedCategoryId: String?,

    /** Days since epoch. Set exactly when [linkedCategoryId] is. */
    @ColumnInfo(name = "count_from")
    val countFrom: Int?,

    @ColumnInfo(name = "sort_order")
    val sortOrder: Int,

    @ColumnInfo(name = "created_at")
    val createdAt: Long,

    @ColumnInfo(name = "updated_at")
    val updatedAt: Long,
)

/**
 * Money the user added to a goal by hand, or took out of it (V2-PLAN §4.1).
 *
 * **Signed:** a negative amount is "Take out". Never zero; the repository
 * refuses one. Like the goal, this is a note against a plan and never a
 * ledger entry.
 */
@Entity(
    tableName = "goal_contribution",
    foreignKeys = [
        ForeignKey(
            entity = SavingsGoalEntity::class,
            parentColumns = ["id"],
            childColumns = ["goal_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index(value = ["goal_id", "local_date"]),
    ],
)
public data class GoalContributionEntity(
    @PrimaryKey
    @ColumnInfo(name = "id")
    val id: String,

    @ColumnInfo(name = "goal_id")
    val goalId: String,

    /** Base currency, signed. Law 3: minor units in an INTEGER. */
    @ColumnInfo(name = "amount_minor")
    val amountMinor: Money,

    /** Days since epoch: the day the user says the money went in or out. */
    @ColumnInfo(name = "local_date")
    val localDate: Int,

    @ColumnInfo(name = "note")
    val note: String?,

    @ColumnInfo(name = "created_at")
    val createdAt: Long,

    @ColumnInfo(name = "updated_at")
    val updatedAt: Long,
)
