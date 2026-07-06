package com.ledgerflow.data.db

import androidx.room.Database
import androidx.room.RoomDatabase
import com.ledgerflow.data.db.dao.*
import com.ledgerflow.data.db.entity.*

@Database(
    entities = [
        CategoryEntity::class,
        SubcategoryEntity::class,
        MerchantEntity::class,
        PaymentMethodEntity::class,
        TransactionEntity::class,
        AttachmentEntity::class,
        BudgetEntity::class,
        RecurringRuleEntity::class,
        PendingTransactionEntity::class,
        MerchantPreferenceEntity::class,
        MerchantAliasEntity::class,
        MerchantRegexEntity::class,
        AuditLogEntity::class,
        TagEntity::class,
        TransactionTagCrossRef::class
    ],
    version = 4,
    exportSchema = false
)
abstract class LedgerFlowDatabase : RoomDatabase() {
    abstract fun transactionDao(): TransactionDao
    abstract fun categoryDao(): CategoryDao
    abstract fun subcategoryDao(): SubcategoryDao
    abstract fun merchantDao(): MerchantDao
    abstract fun budgetDao(): BudgetDao
    abstract fun paymentMethodDao(): PaymentMethodDao
    abstract fun attachmentDao(): AttachmentDao
    abstract fun pendingTransactionDao(): PendingTransactionDao
    abstract fun merchantPreferenceDao(): MerchantPreferenceDao
    abstract fun merchantAliasDao(): com.ledgerflow.data.db.dao.MerchantAliasDao
    abstract fun merchantRegexDao(): com.ledgerflow.data.db.dao.MerchantRegexDao
    abstract fun auditLogDao(): com.ledgerflow.data.db.dao.AuditLogDao
    abstract fun tagDao(): TagDao
}
