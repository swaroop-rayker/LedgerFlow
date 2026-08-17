package com.ledgerflow.core.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.RewriteQueriesToDropUnusedColumns
import androidx.room.Transaction
import androidx.room.Update
import androidx.room.Upsert
import com.ledgerflow.core.database.entity.AppMetaEntity
import com.ledgerflow.core.database.entity.CategoryEntity
import com.ledgerflow.core.database.entity.CreditEntryView
import com.ledgerflow.core.database.entity.DebitEntryView
import com.ledgerflow.core.database.entity.LedgerEntryEntity
import com.ledgerflow.core.database.entity.LineItemEntity
import com.ledgerflow.core.database.entity.MerchantEntity
import com.ledgerflow.core.database.entity.PaymentMethodEntity
import com.ledgerflow.core.model.LedgerType
import kotlinx.coroutines.flow.Flow

@Dao
public interface AppMetaDao {

    @Query("SELECT value FROM app_meta WHERE `key` = :key")
    public suspend fun value(key: String): String?

    @Query("SELECT * FROM app_meta ORDER BY `key`")
    public fun observeAll(): Flow<List<AppMetaEntity>>

    @Query("SELECT * FROM app_meta ORDER BY `key`")
    public suspend fun all(): List<AppMetaEntity>

    @Upsert
    public suspend fun put(entry: AppMetaEntity)

    @Upsert
    public suspend fun putAll(entries: List<AppMetaEntity>)
}

@Dao
public interface CategoryDao {

    @Query(
        "SELECT * FROM category WHERE ledger_scope = :ledger AND deleted_at = 0 ORDER BY sort_order",
    )
    public fun observeLive(ledger: LedgerType): Flow<List<CategoryEntity>>

    @Query("SELECT * FROM category ORDER BY id")
    public suspend fun all(): List<CategoryEntity>

    @Query("SELECT * FROM category WHERE id = :id")
    public suspend fun byId(id: String): CategoryEntity?

    @Query("SELECT COUNT(*) FROM category")
    public suspend fun count(): Int

    /**
     * Collision check for the uniqueness index (§6.1.1).
     *
     * Matches on `parent_key` and `deleted_at = 0` exactly as the index does, so
     * this and the constraint can never disagree about what a duplicate is.
     * `COLLATE NOCASE` comes from the column, so "Food" finds "food".
     */
    @Query(
        "SELECT id FROM category WHERE parent_key = :parentKey AND name = :name " +
            "AND ledger_scope = :ledger AND deleted_at = 0 LIMIT 1",
    )
    public suspend fun findLiveByName(parentKey: String, name: String, ledger: LedgerType): String?

    @Query("SELECT COUNT(*) FROM category WHERE parent_id = :id AND deleted_at = 0")
    public suspend fun liveChildCount(id: String): Int

    @Insert(onConflict = OnConflictStrategy.ABORT)
    public suspend fun insert(category: CategoryEntity)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    public suspend fun insertAll(categories: List<CategoryEntity>)

    @Update
    public suspend fun update(category: CategoryEntity)

    @Query("UPDATE category SET deleted_at = :deletedAt WHERE id = :id")
    public suspend fun softDelete(id: String, deletedAt: Long)

    /** Moves subcategories of [id] under [newParentId], keeping `parent_key` honest. */
    @Query(
        "UPDATE category SET parent_id = :newParentId, parent_key = :newParentKey " +
            "WHERE parent_id = :id AND deleted_at = 0",
    )
    public suspend fun reparentChildren(id: String, newParentId: String?, newParentKey: String)
}

@Dao
public interface MerchantDao {

    @Query("SELECT * FROM merchant WHERE deleted_at = 0 ORDER BY canonical_name")
    public fun observeLive(): Flow<List<MerchantEntity>>

    @Query("SELECT * FROM merchant ORDER BY id")
    public suspend fun all(): List<MerchantEntity>

    @Query("SELECT * FROM merchant WHERE id = :id")
    public suspend fun byId(id: String): MerchantEntity?

    @Query("SELECT * FROM merchant WHERE normalized_key = :key AND deleted_at = 0 LIMIT 1")
    public suspend fun byNormalizedKey(key: String): MerchantEntity?

    @Insert(onConflict = OnConflictStrategy.ABORT)
    public suspend fun insert(merchant: MerchantEntity)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    public suspend fun insertAll(merchants: List<MerchantEntity>)

    @Update
    public suspend fun update(merchant: MerchantEntity)

    @Query("UPDATE merchant SET deleted_at = :deletedAt WHERE id = :id")
    public suspend fun softDelete(id: String, deletedAt: Long)
}

@Dao
public interface PaymentMethodDao {

    @Query("SELECT * FROM payment_method WHERE deleted_at = 0 ORDER BY label")
    public fun observeLive(): Flow<List<PaymentMethodEntity>>

    @Query("SELECT * FROM payment_method ORDER BY id")
    public suspend fun all(): List<PaymentMethodEntity>

    @Query("SELECT * FROM payment_method WHERE id = :id")
    public suspend fun byId(id: String): PaymentMethodEntity?

    @Query("SELECT COUNT(*) FROM payment_method")
    public suspend fun count(): Int

    @Query("SELECT id FROM payment_method WHERE label = :label AND deleted_at = 0 LIMIT 1")
    public suspend fun findLiveByLabel(label: String): String?

    @Insert(onConflict = OnConflictStrategy.ABORT)
    public suspend fun insert(method: PaymentMethodEntity)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    public suspend fun insertAll(methods: List<PaymentMethodEntity>)

    @Update
    public suspend fun update(method: PaymentMethodEntity)

    @Query("UPDATE payment_method SET is_default = 0 WHERE is_default = 1")
    public suspend fun clearDefault()

    @Query("UPDATE payment_method SET is_default = 1 WHERE id = :id")
    public suspend fun markDefault(id: String)

    /**
     * Exactly one default, in one transaction.
     *
     * Two rows with `is_default = 1` would make "which card did that go on?"
     * depend on row order, and the schema has no constraint that prevents it.
     */
    @Transaction
    public suspend fun setDefault(id: String) {
        clearDefault()
        markDefault(id)
    }

    @Query("UPDATE payment_method SET deleted_at = :deletedAt WHERE id = :id")
    public suspend fun softDelete(id: String, deletedAt: Long)
}

/**
 * Ledger reads and writes.
 *
 * **Reads go through `debit_entries` / `credit_entries`, never `ledger_entry`**
 * (ADR-0002). The few queries that do name the base table take an explicit
 * `ledger` parameter; `LedgerIsolationTest` fails the build on any query that
 * names it without one.
 *
 * There is deliberately no query returning both ledgers, and no aggregate
 * spanning them (Law 2).
 */
@Dao
public interface LedgerEntryDao {

    // The views are full projections of ledger_entry; these list screens need
    // only a subset. RewriteQueriesToDropUnusedColumns lets Room narrow the
    // SELECT rather than reading every column off disk for each row -- and
    // keeps the views general for callers that do want the rest.
    @RewriteQueriesToDropUnusedColumns
    @Query("SELECT * FROM debit_entries ORDER BY local_date DESC, occurred_at DESC")
    public fun observeDebits(): Flow<List<DebitEntryView>>

    @RewriteQueriesToDropUnusedColumns
    @Query("SELECT * FROM credit_entries ORDER BY local_date DESC, occurred_at DESC")
    public fun observeCredits(): Flow<List<CreditEntryView>>

    @Query("SELECT SUM(amount_minor) FROM debit_entries WHERE local_date BETWEEN :from AND :to")
    public suspend fun debitTotal(from: Int, to: Int): Long?

    @Query("SELECT SUM(amount_minor) FROM credit_entries WHERE local_date BETWEEN :from AND :to")
    public suspend fun creditTotal(from: Int, to: Int): Long?

    @Query("SELECT * FROM ledger_entry WHERE ledger = :ledger ORDER BY id")
    public suspend fun allForLedger(ledger: LedgerType): List<LedgerEntryEntity>

    @Query("SELECT COUNT(*) FROM ledger_entry WHERE ledger = :ledger")
    public suspend fun countForLedger(ledger: LedgerType): Int

    @Query("SELECT * FROM line_item ORDER BY id")
    public suspend fun allLineItems(): List<LineItemEntity>

    /**
     * Approval is a single transaction (CLAUDE.md §5): the entry and its line
     * items land together or not at all. A half-approved entry would be a
     * ledger row whose items are missing -- silently wrong totals.
     */
    @Transaction
    public suspend fun insertEntryWithLineItems(
        entry: LedgerEntryEntity,
        lineItems: List<LineItemEntity>,
    ) {
        insertEntry(entry)
        if (lineItems.isNotEmpty()) insertLineItems(lineItems)
    }

    @Insert(onConflict = OnConflictStrategy.ABORT)
    public suspend fun insertEntry(entry: LedgerEntryEntity)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    public suspend fun insertLineItems(lineItems: List<LineItemEntity>)

    // ── Taxonomy re-pointing ────────────────────────────────────────────────
    //
    // Every statement below binds `:ledger`, and callers that need both books
    // iterate over LedgerType rather than dropping the predicate. That is not
    // ceremony to appease LedgerIsolationTest: a category is ledger-scoped, so
    // its re-assignment genuinely only touches one book, and a merchant merge
    // that spans both should say so by doing it twice rather than by writing one
    // statement that quietly reaches across the partition (ADR-0002).

    @Query(
        "SELECT COUNT(*) FROM ledger_entry WHERE ledger = :ledger " +
            "AND category_id = :categoryId AND deleted_at IS NULL",
    )
    public suspend fun countForCategory(ledger: LedgerType, categoryId: String): Int

    /**
     * Moves entries off a category being deleted.
     *
     * `subcategory_id` is cleared in the same statement. §6.1.1's invariant is
     * that a row's subcategory's parent equals its `category_id`; leaving the
     * old subcategory behind under a new parent breaks exactly that, and it is
     * the kind of inconsistency that surfaces months later as an analytics
     * bucket that does not add up.
     */
    @Query(
        "UPDATE ledger_entry SET category_id = :target, subcategory_id = NULL, " +
            "updated_at = :now WHERE ledger = :ledger AND category_id = :source",
    )
    public suspend fun reassignCategory(
        ledger: LedgerType,
        source: String,
        target: String,
        now: Long,
    )

    @Query(
        "UPDATE ledger_entry SET subcategory_id = NULL, updated_at = :now " +
            "WHERE ledger = :ledger AND subcategory_id = :source",
    )
    public suspend fun clearSubcategory(ledger: LedgerType, source: String, now: Long)

    @Query(
        "SELECT COUNT(*) FROM ledger_entry WHERE ledger = :ledger " +
            "AND merchant_id = :merchantId AND deleted_at IS NULL",
    )
    public suspend fun countForMerchant(ledger: LedgerType, merchantId: String): Int

    @Query(
        "UPDATE ledger_entry SET merchant_id = :target, updated_at = :now " +
            "WHERE ledger = :ledger AND merchant_id = :source",
    )
    public suspend fun reassignMerchant(
        ledger: LedgerType,
        source: String,
        target: String,
        now: Long,
    )

    @Query(
        "UPDATE ledger_entry SET payment_method_id = NULL, updated_at = :now " +
            "WHERE ledger = :ledger AND payment_method_id = :source",
    )
    public suspend fun clearPaymentMethod(ledger: LedgerType, source: String, now: Long)
}
