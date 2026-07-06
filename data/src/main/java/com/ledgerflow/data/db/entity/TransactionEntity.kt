package com.ledgerflow.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey
import com.ledgerflow.domain.model.Transaction

@Entity(tableName = "transactions")
data class TransactionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val amount: Long,
    val merchant: String,
    val category: String,
    val subcategory: String?,
    @ColumnInfo(name = "payment_method") val paymentMethod: String?,
    val reference: String?,
    val timestamp: Long,
    val notes: String?,
    @ColumnInfo(name = "raw_merchant") val rawMerchant: String? = null
) {
    fun toDomain() = Transaction(
        id = id,
        amount = amount,
        merchant = merchant,
        category = category,
        subcategory = subcategory,
        paymentMethod = paymentMethod,
        reference = reference,
        timestamp = timestamp,
        notes = notes,
        rawMerchant = rawMerchant
    )

    companion object {
        fun fromDomain(domain: Transaction) = TransactionEntity(
            id = domain.id,
            amount = domain.amount,
            merchant = domain.merchant,
            category = domain.category,
            subcategory = domain.subcategory,
            paymentMethod = domain.paymentMethod,
            reference = domain.reference,
            timestamp = domain.timestamp,
            notes = domain.notes,
            rawMerchant = domain.rawMerchant
        )
    }
}
