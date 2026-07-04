package com.ledgerflow.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import com.ledgerflow.data.db.entity.TagEntity

@Dao
interface TagDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertTag(tag: TagEntity): Long

    @Query("SELECT * FROM tags WHERE name = :name LIMIT 1")
    suspend fun getTagByName(name: String): TagEntity?

    @Query("SELECT tags.* FROM tags INNER JOIN transaction_tags ON tags.id = transaction_tags.tag_id WHERE transaction_tags.transaction_id = :transactionId")
    suspend fun getTagsForTransaction(transactionId: Long): List<TagEntity>

    @Transaction
    suspend fun getOrCreateTags(tags: List<TagEntity>): List<Long> {
        return tags.map { tag ->
            val id = insertTag(tag)
            if (id == -1L) {
                getTagByName(tag.name)?.id ?: throw IllegalStateException("Tag not found after insert ignore")
            } else {
                id
            }
        }
    }
}
