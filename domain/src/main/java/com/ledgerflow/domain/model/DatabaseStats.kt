package com.ledgerflow.domain.model

data class DatabaseStats(
    val path: String,
    val sizeBytes: Long,
    val schemaVersion: Int,
    val roomVersion: String,
    val appVersion: String,
    val backupFormatVersion: Int,
    val sqliteVersion: String,
    val encryptionStatus: String,
    val integrityStatus: String,
    val foreignKeyStatus: String,
    val walMode: Boolean,
    val pageCount: Long,
    val lastVacuum: Long,
    val lastBackup: Long,
    val lastRestore: Long,
    val expenseCount: Int,
    val pendingTransactionCount: Int,
    val categoryCount: Int,
    val subcategoryCount: Int,
    val merchantPreferenceCount: Int,
    val merchantCount: Int,
    val budgetCount: Int,
    val auditLogCount: Int,
    val createdTimestamp: Long,
    val lastOpenedTimestamp: Long
)
