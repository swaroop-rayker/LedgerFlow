package com.ledgerflow.core.testing.goals

import com.ledgerflow.core.domain.goals.GoalContribution
import com.ledgerflow.core.domain.goals.GoalDetails
import com.ledgerflow.core.domain.goals.GoalError
import com.ledgerflow.core.domain.goals.GoalRepository
import com.ledgerflow.core.domain.goals.GoalResult
import com.ledgerflow.core.domain.goals.SavingsGoal
import com.ledgerflow.core.model.LedgerType
import com.ledgerflow.core.model.Money
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map

/**
 * Savings goals in memory (V2-7). Linked totals are whatever a test puts in
 * [linked], per book; the SQL that computes them is tested on the device.
 */
public class FakeGoalRepository : GoalRepository {

    public val goals: MutableStateFlow<List<SavingsGoal>> = MutableStateFlow(emptyList())
    public val contributions: MutableStateFlow<List<GoalContribution>> = MutableStateFlow(emptyList())
    public val linked: MutableStateFlow<Map<LedgerType, Map<String, Money>>> = MutableStateFlow(emptyMap())

    private var nextId = 0

    override fun observeGoals(): Flow<List<SavingsGoal>> = goals

    override fun observeContributions(): Flow<List<GoalContribution>> = contributions

    override fun observeLinkedTotals(ledger: LedgerType, from: Int, to: Int): Flow<Map<String, Money>> =
        linked.map { it[ledger].orEmpty() }

    override suspend fun create(details: GoalDetails): GoalResult<SavingsGoal> {
        refusal(details)?.let { return GoalResult.Failure(it) }
        val goal = SavingsGoal(
            id = "goal-${nextId++}",
            name = details.name.trim(),
            target = details.target,
            targetDate = details.targetDate,
            link = details.link,
            sortOrder = goals.value.size,
            createdAt = 0L,
        )
        goals.value = goals.value + goal
        return GoalResult.Success(goal)
    }

    override suspend fun update(id: String, details: GoalDetails): GoalResult<Unit> {
        refusal(details)?.let { return GoalResult.Failure(it) }
        if (goals.value.none { it.id == id }) return GoalResult.Failure(GoalError.NotFound(id))
        goals.value = goals.value.map {
            if (it.id == id) {
                it.copy(
                    name = details.name.trim(),
                    target = details.target,
                    targetDate = details.targetDate,
                    link = details.link,
                )
            } else {
                it
            }
        }
        return GoalResult.Success(Unit)
    }

    override suspend fun delete(id: String): GoalResult<Unit> {
        if (goals.value.none { it.id == id }) return GoalResult.Failure(GoalError.NotFound(id))
        goals.value = goals.value.filterNot { it.id == id }
        contributions.value = contributions.value.filterNot { it.goalId == id }
        return GoalResult.Success(Unit)
    }

    override suspend fun addContribution(
        goalId: String,
        amount: Money,
        localDate: Int,
        note: String?,
    ): GoalResult<GoalContribution> {
        if (amount.isZero) return GoalResult.Failure(GoalError.AmountZero)
        if (goals.value.none { it.id == goalId }) return GoalResult.Failure(GoalError.NotFound(goalId))
        val contribution = GoalContribution("contribution-${nextId++}", goalId, amount, localDate, note)
        contributions.value = listOf(contribution) + contributions.value
        return GoalResult.Success(contribution)
    }

    override suspend fun updateContribution(
        id: String,
        amount: Money,
        localDate: Int,
        note: String?,
    ): GoalResult<Unit> {
        if (amount.isZero) return GoalResult.Failure(GoalError.AmountZero)
        if (contributions.value.none { it.id == id }) return GoalResult.Failure(GoalError.NotFound(id))
        contributions.value = contributions.value.map {
            if (it.id == id) it.copy(amount = amount, localDate = localDate, note = note) else it
        }
        return GoalResult.Success(Unit)
    }

    override suspend fun deleteContribution(id: String): GoalResult<Unit> {
        if (contributions.value.none { it.id == id }) return GoalResult.Failure(GoalError.NotFound(id))
        contributions.value = contributions.value.filterNot { it.id == id }
        return GoalResult.Success(Unit)
    }

    private fun refusal(details: GoalDetails): GoalError? = when {
        details.name.isBlank() -> GoalError.NameBlank
        !details.target.isPositive -> GoalError.TargetNotPositive
        else -> null
    }
}
