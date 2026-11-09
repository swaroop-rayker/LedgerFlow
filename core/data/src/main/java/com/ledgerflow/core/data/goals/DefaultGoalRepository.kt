package com.ledgerflow.core.data.goals

import androidx.room.withTransaction
import com.ledgerflow.core.common.di.IoDispatcher
import com.ledgerflow.core.common.id.Uuid7Generator
import com.ledgerflow.core.common.time.Clock
import com.ledgerflow.core.data.vault.VaultSession
import com.ledgerflow.core.database.LedgerFlowDatabase
import com.ledgerflow.core.database.entity.GoalContributionEntity
import com.ledgerflow.core.database.entity.SavingsGoalEntity
import com.ledgerflow.core.domain.goals.GoalContribution
import com.ledgerflow.core.domain.goals.GoalDetails
import com.ledgerflow.core.domain.goals.GoalError
import com.ledgerflow.core.domain.goals.GoalLink
import com.ledgerflow.core.domain.goals.GoalRepository
import com.ledgerflow.core.domain.goals.GoalResult
import com.ledgerflow.core.domain.goals.SavingsGoal
import com.ledgerflow.core.model.LedgerType
import com.ledgerflow.core.model.Money
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

/**
 * Savings goals over Room (V2-PLAN §4.1, schema v15).
 *
 * **The linked category is checked inside the transaction** that writes the
 * goal, as `DefaultBudgetRepository` checks its category: a hide landing
 * between a check and the write would otherwise leave a goal pointing at a
 * category no picker offers. An edit that keeps the goal's current link is not
 * refused for it, so a goal whose category was hidden can still be renamed.
 */
@Singleton
public class DefaultGoalRepository @Inject constructor(
    private val session: VaultSession,
    private val ids: Uuid7Generator,
    private val clock: Clock,
    @param:IoDispatcher private val io: CoroutineDispatcher,
) : GoalRepository {

    @OptIn(ExperimentalCoroutinesApi::class)
    override fun observeGoals(): Flow<List<SavingsGoal>> =
        session.whenUnlocked().flatMapLatest { database ->
            database?.goalDao()?.observeGoals()?.map { rows -> rows.map { it.toDomain() } }
                ?: flowOf(emptyList())
        }

    @OptIn(ExperimentalCoroutinesApi::class)
    override fun observeContributions(): Flow<List<GoalContribution>> =
        session.whenUnlocked().flatMapLatest { database ->
            database?.goalDao()?.observeContributions()?.map { rows -> rows.map { it.toDomain() } }
                ?: flowOf(emptyList())
        }

    @OptIn(ExperimentalCoroutinesApi::class)
    override fun observeLinkedTotals(ledger: LedgerType, from: Int, to: Int): Flow<Map<String, Money>> =
        session.whenUnlocked().flatMapLatest { database ->
            database?.goalDao()?.observeLinkedTotals(ledger, from, to)
                ?.map { rows -> rows.associate { it.goalId to it.totalMinor } }
                ?: flowOf(emptyMap())
        }

    override suspend fun create(details: GoalDetails): GoalResult<SavingsGoal> = withContext(io) {
        refusal(details)?.let { return@withContext GoalResult.Failure(it) }
        val database = session.requireDatabase()
        database.withTransaction {
            linkRefusal(database, details.link, keeping = null)?.let {
                return@withTransaction GoalResult.Failure(it)
            }
            val now = clock.nowMillis()
            val entity = SavingsGoalEntity(
                id = ids.generate(),
                name = details.name.trim(),
                targetMinor = details.target,
                targetDate = details.targetDate,
                linkedCategoryId = details.link?.categoryId,
                countFrom = details.link?.countFrom,
                sortOrder = database.goalDao().maxSortOrder() + 1,
                createdAt = now,
                updatedAt = now,
            )
            database.goalDao().insertGoal(entity)
            GoalResult.Success(entity.toDomain())
        }
    }

    override suspend fun update(id: String, details: GoalDetails): GoalResult<Unit> = withContext(io) {
        refusal(details)?.let { return@withContext GoalResult.Failure(it) }
        val database = session.requireDatabase()
        database.withTransaction {
            val current = database.goalDao().goal(id)
                ?: return@withTransaction GoalResult.Failure(GoalError.NotFound(id))
            linkRefusal(database, details.link, keeping = current.linkedCategoryId)?.let {
                return@withTransaction GoalResult.Failure(it)
            }
            database.goalDao().updateGoal(
                current.copy(
                    name = details.name.trim(),
                    targetMinor = details.target,
                    targetDate = details.targetDate,
                    linkedCategoryId = details.link?.categoryId,
                    countFrom = details.link?.countFrom,
                    updatedAt = clock.nowMillis(),
                ),
            )
            GoalResult.Success(Unit)
        }
    }

    override suspend fun delete(id: String): GoalResult<Unit> = withContext(io) {
        val affected = session.requireDatabase().goalDao().deleteGoal(id)
        if (affected == 0) GoalResult.Failure(GoalError.NotFound(id)) else GoalResult.Success(Unit)
    }

    override suspend fun addContribution(
        goalId: String,
        amount: Money,
        localDate: Int,
        note: String?,
    ): GoalResult<GoalContribution> = withContext(io) {
        if (amount.isZero) return@withContext GoalResult.Failure(GoalError.AmountZero)
        val database = session.requireDatabase()
        database.withTransaction {
            database.goalDao().goal(goalId)
                ?: return@withTransaction GoalResult.Failure(GoalError.NotFound(goalId))
            val now = clock.nowMillis()
            val entity = GoalContributionEntity(
                id = ids.generate(),
                goalId = goalId,
                amountMinor = amount,
                localDate = localDate,
                note = note?.trim()?.takeIf { it.isNotEmpty() },
                createdAt = now,
                updatedAt = now,
            )
            database.goalDao().insertContribution(entity)
            GoalResult.Success(entity.toDomain())
        }
    }

    override suspend fun updateContribution(
        id: String,
        amount: Money,
        localDate: Int,
        note: String?,
    ): GoalResult<Unit> = withContext(io) {
        if (amount.isZero) return@withContext GoalResult.Failure(GoalError.AmountZero)
        val affected = session.requireDatabase().goalDao().updateContribution(
            id = id,
            amountMinor = amount,
            localDate = localDate,
            note = note?.trim()?.takeIf { it.isNotEmpty() },
            now = clock.nowMillis(),
        )
        if (affected == 0) GoalResult.Failure(GoalError.NotFound(id)) else GoalResult.Success(Unit)
    }

    override suspend fun deleteContribution(id: String): GoalResult<Unit> = withContext(io) {
        val affected = session.requireDatabase().goalDao().deleteContribution(id)
        if (affected == 0) GoalResult.Failure(GoalError.NotFound(id)) else GoalResult.Success(Unit)
    }

    /** The form's own rules, before anything is read. */
    private fun refusal(details: GoalDetails): GoalError? = when {
        details.name.isBlank() -> GoalError.NameBlank
        !details.target.isPositive -> GoalError.TargetNotPositive
        else -> null
    }

    /**
     * A new link must name a live category. [keeping] is the goal's current
     * link, which an edit may keep even after its category was hidden.
     */
    private suspend fun linkRefusal(database: LedgerFlowDatabase, link: GoalLink?, keeping: String?): GoalError? {
        val categoryId = link?.categoryId ?: return null
        if (categoryId == keeping) return null
        val category = database.categoryDao().byId(categoryId)
        // `deleted_at` is NOT NULL DEFAULT 0 on `category` (§6.1.1).
        return if (category == null || category.deletedAt != 0L) GoalError.CategoryNotFound(categoryId) else null
    }
}

private fun SavingsGoalEntity.toDomain() = SavingsGoal(
    id = id,
    name = name,
    target = targetMinor,
    targetDate = targetDate,
    link = linkedCategoryId?.let { categoryId -> GoalLink(categoryId, countFrom ?: 0) },
    sortOrder = sortOrder,
    createdAt = createdAt,
)

private fun GoalContributionEntity.toDomain() = GoalContribution(
    id = id,
    goalId = goalId,
    amount = amountMinor,
    localDate = localDate,
    note = note,
)
