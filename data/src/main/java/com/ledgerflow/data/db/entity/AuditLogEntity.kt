package com.ledgerflow.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "audit_logs")
data class AuditLogEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val operation: String,
    val timestamp: Long = System.currentTimeMillis(),
    val details: String
)
