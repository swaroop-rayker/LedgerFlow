package com.ledgerflow.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "merchant_aliases",
    foreignKeys = [
        ForeignKey(
            entity = MerchantEntity::class,
            parentColumns = ["id"],
            childColumns = ["merchant_id"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [
        Index(value = ["merchant_id"]),
        Index(value = ["alias"], unique = true)
    ]
)
data class MerchantAliasEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "merchant_id") val merchantId: Long,
    val alias: String
)
