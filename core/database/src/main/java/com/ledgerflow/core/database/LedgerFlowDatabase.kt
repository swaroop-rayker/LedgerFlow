package com.ledgerflow.core.database

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import com.ledgerflow.core.database.dao.AppMetaDao
import com.ledgerflow.core.database.dao.CategoryDao
import com.ledgerflow.core.database.dao.CategoryGroupDao
import com.ledgerflow.core.database.dao.DraftEntryDao
import com.ledgerflow.core.database.dao.LedgerEntryDao
import com.ledgerflow.core.database.dao.MerchantAliasDao
import com.ledgerflow.core.database.dao.MerchantDao
import com.ledgerflow.core.database.dao.PaymentMethodDao
import com.ledgerflow.core.database.entity.AppMetaEntity
import com.ledgerflow.core.database.entity.CategoryEntity
import com.ledgerflow.core.database.entity.CategoryGroupEntity
import com.ledgerflow.core.database.entity.CategoryGroupMemberEntity
import com.ledgerflow.core.database.entity.CreditEntryView
import com.ledgerflow.core.database.entity.DebitEntryView
import com.ledgerflow.core.database.entity.DraftEntryEntity
import com.ledgerflow.core.database.entity.LedgerEntryEntity
import com.ledgerflow.core.database.entity.LineItemEntity
import com.ledgerflow.core.database.entity.MerchantAliasEntity
import com.ledgerflow.core.database.entity.MerchantEntity
import com.ledgerflow.core.database.entity.PaymentMethodEntity

/**
 * The encrypted ledger database.
 *
 * Schema v1 carries only what Phase 0 needs (SPEC.md §6.1). `pending_transaction`,
 * `sms_raw`, `notification_raw` and `daily_rollup` arrive with the features that
 * use them -- an unused table is a migration liability, and every table here has
 * to be carried through every future migration whether it holds data or not.
 *
 * `exportSchema = true` is mandatory: the JSONs in `schemas/` are committed and
 * `scripts/guard-schema.sh` treats them as append-only. That guard is the BUG8
 * countermeasure and it can only work if schemas are actually written out.
 */
@Database(
    entities = [
        AppMetaEntity::class,
        CategoryEntity::class,
        MerchantEntity::class,
        PaymentMethodEntity::class,
        LedgerEntryEntity::class,
        LineItemEntity::class,
        // v2 (SPEC.md §6.1.2, D-06).
        DraftEntryEntity::class,
        MerchantAliasEntity::class,
        CategoryGroupEntity::class,
        CategoryGroupMemberEntity::class,
    ],
    views = [
        DebitEntryView::class,
        CreditEntryView::class,
    ],
    version = LedgerFlowDatabase.VERSION,
    exportSchema = true,
)
@TypeConverters(LedgerFlowConverters::class)
public abstract class LedgerFlowDatabase : RoomDatabase() {

    public abstract fun appMetaDao(): AppMetaDao
    public abstract fun categoryDao(): CategoryDao
    public abstract fun merchantDao(): MerchantDao
    public abstract fun paymentMethodDao(): PaymentMethodDao
    public abstract fun ledgerEntryDao(): LedgerEntryDao
    public abstract fun draftEntryDao(): DraftEntryDao
    public abstract fun merchantAliasDao(): MerchantAliasDao
    public abstract fun categoryGroupDao(): CategoryGroupDao

    public companion object {
        /**
         * v2 adds `draft_entry` (BUG6), `merchant_alias`, and the two
         * category-group tables. Purely additive — see `MIGRATION_1_2`.
         *
         * v3 drops `draft_entry`'s unique slot index so a book can hold many
         * in-flight entries at once (ADR-0013, superseding D-06) — see
         * `MIGRATION_2_3`.
         */
        public const val VERSION: Int = 3

        /** Lives in `databases/`, never `cacheDir` or external storage (Law 5). */
        public const val DATABASE_NAME: String = "ledgerflow.db"
    }
}
