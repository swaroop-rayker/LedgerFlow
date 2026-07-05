package com.ledgerflow.domain.model

import kotlinx.serialization.Serializable

@Serializable
data class Transaction(
    val id: Long = 0,
    val amount: Long,
    val merchant: String,
    val category: String,
    val subcategory: String? = null,
    val paymentMethod: String? = null,
    val reference: String? = null,
    val timestamp: Long,
    val notes: String? = null
)
