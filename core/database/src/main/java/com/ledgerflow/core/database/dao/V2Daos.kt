package com.ledgerflow.core.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Upsert
import com.ledgerflow.core.database.entity.CategoryGroupEntity
import com.ledgerflow.core.database.entity.CategoryGroupMemberEntity
import com.ledgerflow.core.database.entity.DraftEntryEntity
import com.ledgerflow.core.database.entity.MerchantAliasEntity
import com.ledgerflow.core.model.LedgerType
import kotlinx.coroutines.flow.Flow

/**
 * In-flight entry-form state (BUG6).
 *
 * Writes are `@Upsert` on a single row: the entry form persists on every field
 * change with a 300 ms debounce, so this is the hottest write path in the app
 * and it must stay one statement.
 */
@Dao
public interface DraftEntryDao {

    @Query("SELECT * FROM draft_entry ORDER BY updated_at DESC")
    public fun observeAll(): Flow<List<DraftEntryEntity>>

    @Query("SELECT * FROM draft_entry ORDER BY id")
    public suspend fun all(): List<DraftEntryEntity>

    @Query("SELECT * FROM draft_entry WHERE id = :id")
    public suspend fun byId(id: String): DraftEntryEntity?

    /**
     * One book's drafts, most recently touched first -- the stack the user
     * sees (ADR-0013).
     */
    @Query("SELECT * FROM draft_entry WHERE ledger = :ledger ORDER BY updated_at DESC")
    public fun observeForLedger(ledger: LedgerType): Flow<List<DraftEntryEntity>>

    /**
     * The in-flight edit of one entry.
     *
     * `editing_entry_key` rather than `editing_entry_id` so this stays a plain
     * equality: the key is `COALESCE(editing_entry_id, '')`, and matching a
     * nullable column would need an `IS NULL` branch the index cannot serve.
     */
    @Query(
        "SELECT * FROM draft_entry WHERE ledger = :ledger " +
            "AND editing_entry_key = :editingEntryKey LIMIT 1",
    )
    public suspend fun byEditingEntry(
        ledger: LedgerType,
        editingEntryKey: String,
    ): DraftEntryEntity?

    @Upsert
    public suspend fun upsert(draft: DraftEntryEntity)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    public suspend fun insertAll(drafts: List<DraftEntryEntity>)

    @Query("DELETE FROM draft_entry WHERE id = :id")
    public suspend fun delete(id: String)


    /**
     * Purges abandoned drafts (§6.1.2): the app was killed and the user never
     * came back. Thirty days, run on app open, one statement.
     */
    @Query("DELETE FROM draft_entry WHERE updated_at < :before")
    public suspend fun purgeOlderThan(before: Long): Int
}

@Dao
public interface MerchantAliasDao {

    @Query("SELECT * FROM merchant_alias WHERE merchant_id = :merchantId ORDER BY alias")
    public fun observeFor(merchantId: String): Flow<List<MerchantAliasEntity>>

    @Query("SELECT * FROM merchant_alias ORDER BY id")
    public suspend fun all(): List<MerchantAliasEntity>

    @Query("SELECT * FROM merchant_alias WHERE normalized_alias = :normalized LIMIT 1")
    public suspend fun byNormalized(normalized: String): MerchantAliasEntity?

    @Insert(onConflict = OnConflictStrategy.ABORT)
    public suspend fun insert(alias: MerchantAliasEntity)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    public suspend fun insertAll(aliases: List<MerchantAliasEntity>)

    @Query("DELETE FROM merchant_alias WHERE id = :id")
    public suspend fun delete(id: String)

    /** Follows a merge: the folded merchant's aliases point at the survivor. */
    @Query("UPDATE merchant_alias SET merchant_id = :target WHERE merchant_id = :source")
    public suspend fun reassign(source: String, target: String)
}

/**
 * Category groups (SPEC.md §5.5).
 *
 * Read and written only by backup and restore at P1 — the management UI and the
 * analytics rollups that consume it land at P3. The DAO exists now because the
 * `.lfbk` export enumerates tables explicitly, and a table missing from that
 * list is a table the P0 exit gate silently stops checking.
 */
@Dao
public interface CategoryGroupDao {

    @Query("SELECT * FROM category_group WHERE ledger_scope = :ledger ORDER BY name")
    public fun observeFor(ledger: LedgerType): Flow<List<CategoryGroupEntity>>

    @Query("SELECT * FROM category_group ORDER BY id")
    public suspend fun all(): List<CategoryGroupEntity>

    @Query("SELECT * FROM category_group_member ORDER BY group_id, category_id")
    public suspend fun allMembers(): List<CategoryGroupMemberEntity>

    @Insert(onConflict = OnConflictStrategy.ABORT)
    public suspend fun insertAll(groups: List<CategoryGroupEntity>)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    public suspend fun insertAllMembers(members: List<CategoryGroupMemberEntity>)
}
