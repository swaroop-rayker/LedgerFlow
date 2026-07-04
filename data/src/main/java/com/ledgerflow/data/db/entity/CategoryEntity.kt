package com.ledgerflow.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import com.ledgerflow.domain.model.Category

@Entity(
    tableName = "categories",
    foreignKeys = [
        ForeignKey(
            entity = CategoryEntity::class,
            parentColumns = ["id"],
            childColumns = ["parent_id"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [
        Index(value = ["parent_id"], name = "idx_categories_parent")
    ]
)
data class CategoryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    @ColumnInfo(name = "parent_id") val parentId: Long?,
    @ColumnInfo(name = "is_archived") val isArchived: Boolean,
    val color: String?,
    val icon: String?
) {
    fun toDomain() = Category(
        id = id,
        name = name,
        parentId = parentId,
        isArchived = isArchived,
        color = color,
        icon = icon
    )

    companion object {
        fun fromDomain(domain: Category) = CategoryEntity(
            id = domain.id,
            name = domain.name,
            parentId = domain.parentId,
            isArchived = domain.isArchived,
            color = domain.color,
            icon = domain.icon
        )
    }
}
