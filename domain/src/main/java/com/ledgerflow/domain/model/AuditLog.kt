package com.ledgerflow.domain.model

data class AuditLog(
    val id: Long = 0,
    val operation: String,
    val timestamp: Long = System.currentTimeMillis(),
    val details: String
)
