package com.ledgerflow.domain.model

import kotlinx.serialization.Serializable

@Serializable
data class Merchant(
    val id: Long = 0,
    val displayName: String,
    val normalizedName: String,
    val isArchived: Boolean = false,
    val defaultCategoryId: Long? = null,
    val notes: String? = null
)
