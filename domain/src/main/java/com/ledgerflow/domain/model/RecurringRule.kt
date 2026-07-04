package com.ledgerflow.domain.model

import kotlinx.serialization.Serializable

@Serializable
data class RecurringRule(
    val id: Long = 0,
    val frequency: Frequency,
    val interval: Int = 1,
    val endDate: Long? = null
)

enum class Frequency {
    DAILY,
    WEEKLY,
    MONTHLY,
    YEARLY
}
