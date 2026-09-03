package com.ledgerflow.core.data.analytics

import com.ledgerflow.core.database.entity.BudgetEntity
import com.ledgerflow.core.domain.analytics.Budget

/**
 * `budget` row to domain, shared by the read and write repositories.
 *
 * One mapper rather than one each, because the two would drift on exactly the
 * field most likely to change — `alert_thresholds`, which is a parsed string
 * and therefore the one with a decision in it.
 *
 * **A malformed threshold list degrades to the default rather than throwing.**
 * A budget that cannot render its thresholds should still render its progress;
 * losing the bar because a comma is wrong would be a worse answer than alerting
 * at 80/100.
 */
internal fun BudgetEntity.toDomainBudget(): Budget = Budget(
    id = id,
    categoryId = categoryId,
    subcategoryId = subcategoryId,
    period = period,
    amount = amountMinor,
    startDate = startDate,
    rolloverEnabled = rolloverEnabled,
    alertThresholds = alertThresholds.split(",")
        .mapNotNull { it.trim().toIntOrNull() }
        .ifEmpty { DEFAULT_THRESHOLDS },
)

/** §5.7's `80,100`. */
internal val DEFAULT_THRESHOLDS: List<Int> = listOf(80, 100)
