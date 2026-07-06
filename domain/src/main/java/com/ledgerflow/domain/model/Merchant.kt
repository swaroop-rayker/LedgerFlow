package com.ledgerflow.domain.model

import kotlinx.serialization.Serializable

@Serializable
data class Merchant(
    val id: Long = 0,
    val displayName: String,
    val normalizedName: String,
    val isArchived: Boolean = false,
    val defaultCategoryId: Long? = null,
    val notes: String? = null,
    
    // Phase 2 canonical merchant management fields
    val canonicalName: String = "",
    val aliases: List<String> = emptyList(),
    val regexPatterns: List<String> = emptyList(),
    val logo: String? = null,
    val icon: String? = null,
    val preferredCategory: String? = null,
    val preferredSubcategory: String? = null,
    val confidenceRules: String? = null,
    val enabled: Boolean = true,
    val createdBy: String = "SYSTEM",
    val system: Boolean = true,
    val user: Boolean = false,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
    val usageCount: Int = 0,
    val lastUsed: Long = 0L
)
