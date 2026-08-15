package com.ledgerflow.core.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.RewriteQueriesToDropUnusedColumns
import androidx.room.Transaction
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

    @Insert(onConflict = OnConflictStrategy.ABORT)
    public suspend fun insertAll(categories: List<CategoryEntity>)
}

@Dao
public interface MerchantDao {

    @Query("SELECT * FROM merchant WHERE deleted_at = 0 ORDER BY canonical_name")
    public fun observeLive(): Flow<List<MerchantEntity>>

    @Query("SELECT * FROM merchant ORDER BY id")
    public suspend fun all(): List<MerchantEntity>

    @Insert(onConflict = OnConflictStrategy.ABORT)
    public suspend fun insertAll(merchants: List<MerchantEntity>)
}

@Dao
public interface PaymentMethodDao {

    @Query("SELECT * FROM payment_method WHERE deleted_at = 0 ORDER BY label")
    public fun observeLive(): Flow<List<PaymentMethodEntity>>

    @Query("SELECT * FROM payment_method ORDER BY id")
    public suspend fun all(): List<PaymentMethodEntity>

    @Insert(onConflict = OnConflictStrategy.ABORT)
    public suspend fun insertAll(methods: List<PaymentMethodEntity>)
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
}
