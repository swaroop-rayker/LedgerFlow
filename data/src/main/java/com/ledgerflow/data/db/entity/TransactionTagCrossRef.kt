package com.ledgerflow.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index

@Entity(
    tableName = "transaction_tags",
    primaryKeys = ["transaction_id", "tag_id"],
    foreignKeys = [
        ForeignKey(
            entity = TransactionEntity::class,
            parentColumns = ["id"],
            childColumns = ["transaction_id"],
            onDelete = ForeignKey.CASCADE
        ),
        ForeignKey(
            entity = TagEntity::class,
            parentColumns = ["id"],
            childColumns = ["tag_id"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [
        Index(value = ["transaction_id"], name = "idx_transaction_tags_txn"),
        Index(value = ["tag_id"], name = "idx_transaction_tags_tag")
    ]
)
data class TransactionTagCrossRef(
    @ColumnInfo(name = "transaction_id") val transactionId: Long,
    @ColumnInfo(name = "tag_id") val tagId: Long
)
