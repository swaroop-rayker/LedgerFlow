package com.ledgerflow.core.domain.usecase

import com.ledgerflow.core.common.time.Clock
import com.ledgerflow.core.common.time.LocalDates
import com.ledgerflow.core.domain.goals.GoalContribution
import com.ledgerflow.core.domain.goals.GoalDetails
import com.ledgerflow.core.domain.goals.GoalProgress
import com.ledgerflow.core.domain.goals.GoalProjection
import com.ledgerflow.core.domain.goals.GoalRepository
import com.ledgerflow.core.domain.goals.GoalResult
import com.ledgerflow.core.domain.goals.SavingsGoal
import com.ledgerflow.core.model.LedgerType
import com.ledgerflow.core.model.Money
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import javax.inject.Inject

/** Every goal with its progress, and the hand-added amounts behind them. */
public data class GoalsOverview(
    /** Days since epoch, the day the figures are for. */
    val today: Int,
    val goals: List<GoalProgress>,
    val contributions: List<GoalContribution>,
)

/**
 * Savings goals and where each is heading (V2-PLAN §4.1).
 *
 * **Each book is asked separately** (Law 2): a goal's linked category belongs
 * to one book, so the two answers are keyed by goal and never added to each
 * other. "Today" is read once per subscription; a screen re-subscribes when it
 * opens.
 */
public class ObserveGoalsUseCase @Inject constructor(
    private val goals: GoalRepository,
    private val clock: Clock,
) {
    public operator fun invoke(): Flow<GoalsOverview> {
        val today = LocalDates.of(clock.nowMillis())
        val windowFrom = today - GoalProjection.WINDOW_DAYS + 1
        return combine(
            goals.observeGoals(),
            goals.observeContributions(),
            linkedTotals(from = Int.MIN_VALUE, to = today),
            linkedTotals(from = windowFrom, to = today),
        ) { list, contributions, allTime, inWindow ->
            val byGoal = contributions.groupBy { it.goalId }
            GoalsOverview(
                today = today,
                goals = list.map { goal ->
                    progress(goal, byGoal[goal.id].orEmpty(), allTime, inWindow, today)
                },
                contributions = contributions,
            )
        }
    }

    /** Keyed by goal: each goal is in one book, so no key is in both maps. */
    private fun linkedTotals(from: Int, to: Int): Flow<Map<String, Money>> = combine(
        goals.observeLinkedTotals(LedgerType.DEBIT, from, to),
        goals.observeLinkedTotals(LedgerType.CREDIT, from, to),
    ) { debit, credit -> debit + credit }

    private fun progress(
        goal: SavingsGoal,
        contributions: List<GoalContribution>,
        allTime: Map<String, Money>,
        inWindow: Map<String, Money>,
        today: Int,
    ): GoalProgress {
        val historyStart = listOfNotNull(
            LocalDates.of(goal.createdAt),
            goal.link?.countFrom,
            contributions.minOfOrNull { it.localDate },
        ).min()
        val windowStart = today - GoalProjection.windowDays(historyStart, today) + 1
        val handInWindow = Money.sum(contributions.filter { it.localDate in windowStart..today }.map { it.amount })
        val linkedInWindow = if (goal.link != null) inWindow[goal.id] ?: Money.ZERO else Money.ZERO
        return GoalProjection.progress(
            goal = goal,
            fromHand = Money.sum(contributions.map { it.amount }),
            fromCategory = if (goal.link != null) allTime[goal.id] ?: Money.ZERO else Money.ZERO,
            historyStart = historyStart,
            addedInWindow = handInWindow + linkedInWindow,
            today = today,
        )
    }
}

/** How many goals there are, for Profile's Savings goals row. No figures, so no rollup read. */
public class ObserveGoalCountUseCase @Inject constructor(
    private val goals: GoalRepository,
) {
    public operator fun invoke(): Flow<Int> = goals.observeGoals().map { it.size }
}

public class CreateGoalUseCase @Inject constructor(
    private val goals: GoalRepository,
) {
    public suspend operator fun invoke(details: GoalDetails): GoalResult<SavingsGoal> = goals.create(details)
}

public class UpdateGoalUseCase @Inject constructor(
    private val goals: GoalRepository,
) {
    public suspend operator fun invoke(id: String, details: GoalDetails): GoalResult<Unit> =
        goals.update(id, details)
}

/** For good, with its hand-added amounts; a linked category's entries are untouched. */
public class DeleteGoalUseCase @Inject constructor(
    private val goals: GoalRepository,
) {
    public suspend operator fun invoke(id: String): GoalResult<Unit> = goals.delete(id)
}

/** "Add money" with a positive [amount], "Take out" with a negative one. */
public class AddGoalContributionUseCase @Inject constructor(
    private val goals: GoalRepository,
) {
    public suspend operator fun invoke(
        goalId: String,
        amount: Money,
        localDate: Int,
        note: String?,
    ): GoalResult<GoalContribution> = goals.addContribution(goalId, amount, localDate, note)
}

public class UpdateGoalContributionUseCase @Inject constructor(
    private val goals: GoalRepository,
) {
    public suspend operator fun invoke(
        id: String,
        amount: Money,
        localDate: Int,
        note: String?,
    ): GoalResult<Unit> = goals.updateContribution(id, amount, localDate, note)
}

public class DeleteGoalContributionUseCase @Inject constructor(
    private val goals: GoalRepository,
) {
    public suspend operator fun invoke(id: String): GoalResult<Unit> = goals.deleteContribution(id)
}
