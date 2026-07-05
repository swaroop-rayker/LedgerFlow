package com.ledgerflow.domain.model

import kotlinx.serialization.Serializable

@Serializable
data class MerchantPreference(
    val id: Long = 0,
    val merchant: String,
    val preferredCategory: String,
    val preferredSubcategory: String? = null,
    val lastUsed: Long = System.currentTimeMillis(),
    val usageCount: Int = 1
)
