package com.ledgerflow.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.ledgerflow.domain.model.MerchantPreference

@Entity(
    tableName = "merchant_preferences",
    indices = [Index(value = ["merchant"], unique = true)]
)
data class MerchantPreferenceEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val merchant: String,
    @ColumnInfo(name = "preferred_category") val preferredCategory: String,
    @ColumnInfo(name = "preferred_subcategory") val preferredSubcategory: String?,
    @ColumnInfo(name = "last_used") val lastUsed: Long,
    @ColumnInfo(name = "usage_count") val usageCount: Int
) {
    fun toDomain() = MerchantPreference(
        id = id,
        merchant = merchant,
        preferredCategory = preferredCategory,
        preferredSubcategory = preferredSubcategory,
        lastUsed = lastUsed,
        usageCount = usageCount
    )

    companion object {
        fun fromDomain(domain: MerchantPreference) = MerchantPreferenceEntity(
            id = domain.id,
            merchant = domain.merchant,
            preferredCategory = domain.preferredCategory,
            preferredSubcategory = domain.preferredSubcategory,
            lastUsed = domain.lastUsed,
            usageCount = domain.usageCount
        )
    }
}
