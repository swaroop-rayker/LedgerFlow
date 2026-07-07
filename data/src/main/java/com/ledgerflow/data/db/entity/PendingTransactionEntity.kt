package com.ledgerflow.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey
import com.ledgerflow.domain.model.PendingTransaction

@Entity(tableName = "pending_transactions")
data class PendingTransactionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val amount: Long,
    val merchant: String,
    val category: String,
    val subcategory: String?,
    @ColumnInfo(name = "payment_method") val paymentMethod: String?,
    val reference: String?,
    val timestamp: Long,
    val notes: String?,
    val confidence: Int,
    val status: String,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
    @ColumnInfo(name = "raw_merchant") val rawMerchant: String? = null
) {
    fun toDomain() = PendingTransaction(
        id = id,
        amount = amount,
        merchant = merchant,
        category = category,
        subcategory = subcategory,
        paymentMethod = if (paymentMethod == "Credit") "Credit Card" else paymentMethod,
        reference = reference,
        timestamp = timestamp,
        notes = notes,
        confidence = confidence,
        status = status,
        createdAt = createdAt,
        updatedAt = updatedAt,
        rawMerchant = rawMerchant
    )

    companion object {
        fun fromDomain(domain: PendingTransaction) = PendingTransactionEntity(
            id = domain.id,
            amount = domain.amount,
            merchant = domain.merchant,
            category = domain.category,
            subcategory = domain.subcategory,
            paymentMethod = domain.paymentMethod,
            reference = domain.reference,
            timestamp = domain.timestamp,
            notes = domain.notes,
            confidence = domain.confidence,
            status = domain.status,
            createdAt = domain.createdAt,
            updatedAt = domain.updatedAt,
            rawMerchant = domain.rawMerchant
        )
    }
}
