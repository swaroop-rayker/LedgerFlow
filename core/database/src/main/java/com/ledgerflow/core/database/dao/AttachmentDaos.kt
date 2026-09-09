package com.ledgerflow.core.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.ledgerflow.core.database.entity.AttachmentEntity
import com.ledgerflow.core.database.entity.ItemCategoryMemoryEntity
import kotlinx.coroutines.flow.Flow

/**
 * Receipt-image metadata (SPEC.md §5.3, §6.1; ADR-0023). Schema v11.
 *
 * Reads return `Flow`, writes are `suspend` (`CLAUDE.md` §5).
 *
 * **Nothing here deletes a file.** [deleteById] and the `ON DELETE CASCADE`
 * from `ledger_entry` remove the *row*; the bytes under `filesDir/attachments/`
 * outlive it unless a caller unlinks them. That asymmetry is why
 * [pathsForEntry] exists — a caller about to destroy entries collects the paths
 * first, then deletes, then unlinks. `PurgeDeletedEntriesUseCase` is the one
 * place in the app that hard-deletes a `ledger_entry` (`CLAUDE.md` §7) and is
 * therefore the one place that must do this.
 */
@Dao
public interface AttachmentDao {

    @Query("SELECT * FROM attachment WHERE entry_id = :entryId ORDER BY created_at")
    public fun forEntry(entryId: String): Flow<List<AttachmentEntity>>

    @Query("SELECT * FROM attachment WHERE id = :id")
    public suspend fun byId(id: String): AttachmentEntity?

    /**
     * The dedupe lookup: has this exact image already been stored?
     *
     * `sha256` is over the plaintext, so the same receipt attached twice
     * matches here regardless of the nonce each copy was sealed with.
     */
    @Query("SELECT * FROM attachment WHERE sha256 = :sha256 LIMIT 1")
    public suspend fun bySha256(sha256: String): AttachmentEntity?

    /**
     * Relative paths for the files a set of entries owns.
     *
     * Read **before** the rows are destroyed. See the interface KDoc: the
     * cascade takes the rows and leaves the bytes, and nothing else enumerates
     * that directory, so a file missed here is a file leaked permanently.
     */
    @Query("SELECT file_path FROM attachment WHERE entry_id IN (:entryIds)")
    public suspend fun pathsForEntry(entryIds: List<String>): List<String>

    /** Every row, for the backup payload. */
    @Query("SELECT * FROM attachment ORDER BY id")
    public suspend fun all(): List<AttachmentEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    public suspend fun insert(attachment: AttachmentEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    public suspend fun insertAll(attachments: List<AttachmentEntity>)

    /** Attaches a pending capture to the entry approval just created. */
    @Query("UPDATE attachment SET entry_id = :entryId WHERE id = :id")
    public suspend fun linkToEntry(id: String, entryId: String)

    @Query("DELETE FROM attachment WHERE id = :id")
    public suspend fun deleteById(id: String)
}

/**
 * Learned `(merchant, item) -> category` suggestions (SPEC.md §5.3). Schema v11.
 *
 * A suggestion store, not a filing mechanism: nothing here writes to
 * `ledger_entry`, and Law 1's single writer is untouched.
 *
 * `merchant_id` is `''` when the observation had no merchant — the sentinel
 * rule `daily_rollup` already uses, because SQLite treats NULLs as distinct
 * inside a composite key and the bucket would never accumulate a second hit.
 * **Callers map a null merchant to `''`;** passing null here silently creates a
 * row nothing will ever match again.
 */
@Dao
public interface ItemCategoryMemoryDao {

    /**
     * The suggestion for one item at one merchant.
     *
     * Falls back to no-merchant (`''`) at the call site rather than here: an
     * `OR merchant_id = ''` inside this query would silently prefer whichever
     * row SQLite returned first, and which of the two wins is a product
     * decision rather than a storage one.
     */
    @Query(
        "SELECT * FROM item_category_memory " +
            "WHERE merchant_id = :merchantId AND normalized_item = :normalizedItem",
    )
    public suspend fun suggestion(
        merchantId: String,
        normalizedItem: String,
    ): ItemCategoryMemoryEntity?

    /** Every row, for the backup payload. */
    @Query("SELECT * FROM item_category_memory ORDER BY merchant_id, normalized_item")
    public suspend fun all(): List<ItemCategoryMemoryEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    public suspend fun insertAll(rows: List<ItemCategoryMemoryEntity>)

    /**
     * Records one confirmed filing, incrementing the count when it repeats.
     *
     * `ON CONFLICT DO UPDATE` rather than read-then-write: the read-then-write
     * form loses a hit whenever two lines of the same bill carry the same item,
     * which is ordinary on a receipt (two of the same thing at different sizes)
     * and would need a transaction to fix a problem the upsert does not have.
     *
     * A changed category **replaces** and does not reset the count: the user
     * correcting a filing is the strongest signal available that the new one is
     * right, so it inherits the accumulated confidence rather than starting
     * over behind the answer it just replaced.
     */
    @Query(
        "INSERT INTO item_category_memory " +
            "(merchant_id, normalized_item, category_id, subcategory_id, hit_count) " +
            "VALUES (:merchantId, :normalizedItem, :categoryId, :subcategoryId, 1) " +
            "ON CONFLICT(merchant_id, normalized_item) DO UPDATE SET " +
            "category_id = :categoryId, subcategory_id = :subcategoryId, " +
            "hit_count = hit_count + 1",
    )
    public suspend fun record(
        merchantId: String,
        normalizedItem: String,
        categoryId: String,
        subcategoryId: String?,
    )

    /** Used when a category is hard-deleted (ADR-0016). */
    @Query("DELETE FROM item_category_memory WHERE category_id = :categoryId")
    public suspend fun deleteForCategory(categoryId: String)
}
