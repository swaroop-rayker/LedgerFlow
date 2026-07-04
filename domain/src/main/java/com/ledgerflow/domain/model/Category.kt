package com.ledgerflow.domain.model

import kotlinx.serialization.Serializable

@Serializable
data class Category(
    val id: Long = 0,
    val name: String,
    val parentId: Long? = null,
    val isArchived: Boolean = false,
    val color: String? = null,
    val icon: String? = null
)
