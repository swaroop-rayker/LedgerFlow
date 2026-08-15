package com.ledgerflow.core.database

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import com.ledgerflow.core.database.dao.AppMetaDao
import com.ledgerflow.core.database.dao.CategoryDao
import com.ledgerflow.core.database.dao.LedgerEntryDao
import com.ledgerflow.core.database.dao.MerchantDao
import com.ledgerflow.core.database.dao.PaymentMethodDao
import com.ledgerflow.core.database.entity.AppMetaEntity
import com.ledgerflow.core.database.entity.CategoryEntity
import com.ledgerflow.core.database.entity.CreditEntryView
import com.ledgerflow.core.database.entity.DebitEntryView
import com.ledgerflow.core.database.entity.LedgerEntryEntity
import com.ledgerflow.core.database.entity.LineItemEntity
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

    public companion object {
        public const val VERSION: Int = 1

        /** Lives in `databases/`, never `cacheDir` or external storage (Law 5). */
        public const val DATABASE_NAME: String = "ledgerflow.db"
    }
}
