package com.ledgerflow.domain.model

import kotlinx.serialization.Serializable

@Serializable
data class Transaction(
    val id: Long = 0,
    val timestamp: Long,
    val totalAmount: Long, // Stored in cents/paise
    val type: TransactionType,
    val merchantId: Long? = null,
    val paymentMethodId: Long? = null,
    val notes: String? = null,
    val isRecurring: Boolean = false,
    val recurringRuleId: Long? = null
)

enum class TransactionType {
    INCOME,
    EXPENSE,
    TRANSFER,
    REFUND
}
