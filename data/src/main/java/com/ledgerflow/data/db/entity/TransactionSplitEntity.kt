package com.ledgerflow.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import com.ledgerflow.domain.model.TransactionSplit

@Entity(
    tableName = "transaction_splits",
    foreignKeys = [
        ForeignKey(
            entity = TransactionEntity::class,
            parentColumns = ["id"],
            childColumns = ["transaction_id"],
            onDelete = ForeignKey.CASCADE
        ),
        ForeignKey(
            entity = CategoryEntity::class,
            parentColumns = ["id"],
            childColumns = ["category_id"],
            onDelete = ForeignKey.RESTRICT
        )
    ],
    indices = [
        Index(value = ["transaction_id"], name = "idx_splits_transaction"),
        Index(value = ["category_id"], name = "idx_splits_category")
    ]
)
data class TransactionSplitEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "transaction_id") val transactionId: Long,
    @ColumnInfo(name = "category_id") val categoryId: Long,
    val amount: Long,
    val notes: String?
) {
    fun toDomain() = TransactionSplit(
        id = id,
        transactionId = transactionId,
        categoryId = categoryId,
        amount = amount,
        notes = notes
    )

    companion object {
        fun fromDomain(domain: TransactionSplit) = TransactionSplitEntity(
            id = domain.id,
            transactionId = domain.transactionId,
            categoryId = domain.categoryId,
            amount = domain.amount,
            notes = domain.notes
        )
    }
}
