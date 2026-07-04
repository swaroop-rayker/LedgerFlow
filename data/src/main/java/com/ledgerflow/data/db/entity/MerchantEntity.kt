package com.ledgerflow.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import com.ledgerflow.domain.model.Merchant

@Entity(
    tableName = "merchants",
    foreignKeys = [
        ForeignKey(
            entity = CategoryEntity::class,
            parentColumns = ["id"],
            childColumns = ["default_category_id"],
            onDelete = ForeignKey.SET_NULL
        )
    ],
    indices = [
        Index(value = ["normalized_name"], unique = true, name = "idx_merchants_normalized"),
        Index(value = ["default_category_id"])
    ]
)
data class MerchantEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "display_name") val displayName: String,
    @ColumnInfo(name = "normalized_name") val normalizedName: String,
    @ColumnInfo(name = "is_archived") val isArchived: Boolean,
    @ColumnInfo(name = "default_category_id") val defaultCategoryId: Long?,
    val notes: String?
) {
    fun toDomain() = Merchant(
        id = id,
        displayName = displayName,
        normalizedName = normalizedName,
        isArchived = isArchived,
        defaultCategoryId = defaultCategoryId,
        notes = notes
    )

    companion object {
        fun fromDomain(domain: Merchant) = MerchantEntity(
            id = domain.id,
            displayName = domain.displayName,
            normalizedName = domain.normalizedName,
            isArchived = domain.isArchived,
            defaultCategoryId = domain.defaultCategoryId,
            notes = domain.notes
        )
    }
}
