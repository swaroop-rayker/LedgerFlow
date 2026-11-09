package com.ledgerflow.feature.tools.goals

import com.ledgerflow.core.designsystem.format.DisplayDates
import com.ledgerflow.core.domain.goals.GoalContribution
import com.ledgerflow.core.domain.goals.GoalProgress
import java.time.LocalDate

internal fun GoalProgress.toRow(today: Int, currency: String): GoalRowUi {
    val line = GoalWords.caption(this, today, currency)
    return GoalRowUi(
        id = goal.id,
        name = goal.name,
        saved = GoalWords.money(saved, currency),
        fraction = fraction(),
        caption = line.text,
        warning = line.warning,
    )
}

/** For the bar: a drawing ratio, never money (Law 3). */
internal fun GoalProgress.fraction(): Float = percent / PERCENT

internal fun GoalContribution.toRow(currency: String): ContributionRowUi = ContributionRowUi(
    id = id,
    date = DisplayDates.dayMonth(LocalDate.ofEpochDay(localDate.toLong())),
    note = note,
    amount = GoalWords.money(amount, currency),
    takenOut = amount.isNegative,
)

private const val PERCENT = 100f
