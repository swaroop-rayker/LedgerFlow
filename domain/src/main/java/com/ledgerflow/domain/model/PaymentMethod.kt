package com.ledgerflow.domain.model

import kotlinx.serialization.Serializable

@Serializable
data class PaymentMethod(
    val id: Long = 0,
    val name: String,
    val type: PaymentMethodType
)

enum class PaymentMethodType {
    CASH,
    BANK_ACCOUNT,
    CREDIT_CARD,
    OTHER
}
