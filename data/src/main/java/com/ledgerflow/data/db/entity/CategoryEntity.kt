package com.ledgerflow.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.ledgerflow.domain.model.Category

@Entity(
    tableName = "categories",
    indices = [
        Index(value = ["name"], unique = true)
    ]
)
data class CategoryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    @ColumnInfo(name = "is_archived") val isArchived: Boolean = false,
    val color: String? = null,
    val icon: String? = null,
    @ColumnInfo(name = "display_order") val displayOrder: Int = 0,
    @ColumnInfo(name = "is_pinned", defaultValue = "0") val isPinned: Boolean = false
) {
    fun toDomain() = Category(
        id = id,
        name = name,
        parentId = null,
        isArchived = isArchived,
        color = color,
        icon = icon,
        isPinned = isPinned
    )

    companion object {
        fun fromDomain(domain: Category) = CategoryEntity(
            id = domain.id,
            name = domain.name,
            isArchived = domain.isArchived,
            color = domain.color,
            icon = domain.icon,
            displayOrder = 0,
            isPinned = domain.isPinned
        )
    }
}
