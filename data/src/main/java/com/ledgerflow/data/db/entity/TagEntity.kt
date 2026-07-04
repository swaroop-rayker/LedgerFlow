package com.ledgerflow.data.db.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.ledgerflow.domain.model.Tag

@Entity(
    tableName = "tags",
    indices = [
        Index(value = ["name"], unique = true, name = "idx_tags_name")
    ]
)
data class TagEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String
) {
    fun toDomain() = Tag(id = id, name = name)

    companion object {
        fun fromDomain(domain: Tag) = TagEntity(
            id = domain.id,
            name = domain.name
        )
    }
}
