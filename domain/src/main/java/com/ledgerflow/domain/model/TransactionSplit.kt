package com.ledgerflow.domain.model

import kotlinx.serialization.Serializable

@Serializable
data class TransactionSplit(
    val id: Long = 0,
    val transactionId: Long,
    val categoryId: Long,
    val amount: Long, // Stored in cents/paise
    val notes: String? = null
)
