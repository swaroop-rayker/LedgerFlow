package com.ledgerflow.core.domain.goals

import com.ledgerflow.core.model.LedgerType
import com.ledgerflow.core.model.Money
import kotlinx.coroutines.flow.Flow

/**
 * A savings goal (V2-PLAN §4.1). A target and a plan, **not a third ledger**:
 * nothing here is money that moved, and no book's total includes it (Law 2).
 *
 * [targetDate] and [link]'s `countFrom` are days since epoch.
 */
public data class SavingsGoal(
    val id: String,
    val name: String,
    val target: Money,
    val targetDate: Int?,
    val link: GoalLink?,
    val sortOrder: Int,
    val createdAt: Long,
)

/**
 * Approved entries in [categoryId] from [countFrom] on add to the goal. A
 * category counts its subcategories; a subcategory counts only itself. The
 * book is the category's own, so a goal never needs to say which.
 */
public data class GoalLink(val categoryId: String, val countFrom: Int)

/** Money added by hand, or taken out ([amount] negative). Never zero. */
public data class GoalContribution(
    val id: String,
    val goalId: String,
    val amount: Money,
    val localDate: Int,
    val note: String?,
)

/** What the form saves, for a new goal and an edit alike. */
public data class GoalDetails(
    val name: String,
    val target: Money,
    val targetDate: Int?,
    val link: GoalLink?,
)

/** Why a write was refused. Returned, never thrown (`CLAUDE.md` §5). */
public sealed interface GoalError {
    public data object NameBlank : GoalError

    /** A goal of nothing is not a goal. */
    public data object TargetNotPositive : GoalError

    /** Adding or taking out nothing is not a change. */
    public data object AmountZero : GoalError

    /** The linked category vanished, or was hidden, between opening the form and saving. */
    public data class CategoryNotFound(val id: String) : GoalError

    public data class NotFound(val id: String) : GoalError
}

public sealed interface GoalResult<out T> {
    public data class Success<T>(val value: T) : GoalResult<T>
    public data class Failure(val error: GoalError) : GoalResult<Nothing>
}

/**
 * Savings goals (V2-PLAN §4.1, schema v15).
 *
 * The reads are cold flows, so a contribution, an approval into a linked
 * category, or a re-filed entry shows up by itself.
 */
public interface GoalRepository {

    /** Every goal, in the user's order. */
    public fun observeGoals(): Flow<List<SavingsGoal>>

    /** Every hand-added amount, newest day first. */
    public fun observeContributions(): Flow<List<GoalContribution>>

    /**
     * What each goal linked to one of [ledger]'s categories holds there, from
     * its own `countFrom` (or [from], if later) to [to], by goal id. A goal
     * whose category has nothing in the window is absent. **One book per
     * call** (Law 2).
     */
    public fun observeLinkedTotals(ledger: LedgerType, from: Int, to: Int): Flow<Map<String, Money>>

    public suspend fun create(details: GoalDetails): GoalResult<SavingsGoal>

    public suspend fun update(id: String, details: GoalDetails): GoalResult<Unit>

    /**
     * Removes the goal and its hand-added amounts, for good (owner,
     * 2026-11-09). Entries in a linked category are untouched.
     */
    public suspend fun delete(id: String): GoalResult<Unit>

    public suspend fun addContribution(
        goalId: String,
        amount: Money,
        localDate: Int,
        note: String?,
    ): GoalResult<GoalContribution>

    public suspend fun updateContribution(
        id: String,
        amount: Money,
        localDate: Int,
        note: String?,
    ): GoalResult<Unit>

    public suspend fun deleteContribution(id: String): GoalResult<Unit>
}
