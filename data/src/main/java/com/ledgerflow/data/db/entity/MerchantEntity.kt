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
    val notes: String?,

    @ColumnInfo(name = "canonical_name", defaultValue = "''") val canonicalName: String = "",
    @ColumnInfo(name = "logo") val logo: String? = null,
    @ColumnInfo(name = "icon") val icon: String? = null,
    @ColumnInfo(name = "preferred_category") val preferredCategory: String? = null,
    @ColumnInfo(name = "preferred_subcategory") val preferredSubcategory: String? = null,
    @ColumnInfo(name = "confidence_rules") val confidenceRules: String? = null,
    @ColumnInfo(name = "enabled", defaultValue = "1") val enabled: Boolean = true,
    @ColumnInfo(name = "created_by", defaultValue = "'SYSTEM'") val createdBy: String = "SYSTEM",
    @ColumnInfo(name = "is_system", defaultValue = "1") val system: Boolean = true,
    @ColumnInfo(name = "is_user", defaultValue = "0") val user: Boolean = false,
    @ColumnInfo(name = "created_at", defaultValue = "0") val createdAt: Long = 0L,
    @ColumnInfo(name = "updated_at", defaultValue = "0") val updatedAt: Long = 0L,
    @ColumnInfo(name = "usage_count", defaultValue = "0") val usageCount: Int = 0,
    @ColumnInfo(name = "last_used", defaultValue = "0") val lastUsed: Long = 0L
) {
    fun toDomain(aliases: List<String> = emptyList(), regexPatterns: List<String> = emptyList()) = Merchant(
        id = id,
        displayName = displayName,
        normalizedName = normalizedName,
        isArchived = isArchived,
        defaultCategoryId = defaultCategoryId,
        notes = notes,
        canonicalName = canonicalName,
        aliases = aliases,
        regexPatterns = regexPatterns,
        logo = logo,
        icon = icon,
        preferredCategory = preferredCategory,
        preferredSubcategory = preferredSubcategory,
        confidenceRules = confidenceRules,
        enabled = enabled,
        createdBy = createdBy,
        system = system,
        user = user,
        createdAt = createdAt,
        updatedAt = updatedAt,
        usageCount = usageCount,
        lastUsed = lastUsed
    )

    companion object {
        fun fromDomain(domain: Merchant) = MerchantEntity(
            id = domain.id,
            displayName = domain.displayName,
            normalizedName = domain.normalizedName,
            isArchived = domain.isArchived,
            defaultCategoryId = domain.defaultCategoryId,
            notes = domain.notes,
            canonicalName = domain.canonicalName,
            logo = domain.logo,
            icon = domain.icon,
            preferredCategory = domain.preferredCategory,
            preferredSubcategory = domain.preferredSubcategory,
            confidenceRules = domain.confidenceRules,
            enabled = domain.enabled,
            createdBy = domain.createdBy,
            system = domain.system,
            user = domain.user,
            createdAt = domain.createdAt,
            updatedAt = domain.updatedAt,
            usageCount = domain.usageCount,
            lastUsed = domain.lastUsed
        )
    }
}
