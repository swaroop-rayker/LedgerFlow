package com.ledgerflow.domain.model

import kotlinx.serialization.Serializable

@Serializable
data class Budget(
    val id: Long = 0,
    val categoryId: Long,
    val amount: Long, // Stored in cents/paise
    val period: BudgetPeriod,
    val startDate: Long,
    val endDate: Long
)

enum class BudgetPeriod {
    MONTHLY,
    YEARLY
}
