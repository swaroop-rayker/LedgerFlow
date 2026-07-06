package com.ledgerflow.data.di

import android.content.Context
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.sqlite.db.SupportSQLiteDatabase
import com.ledgerflow.data.datastore.SecurityPrefsManager
import com.ledgerflow.data.db.DatabaseCompatibilityManager
import com.ledgerflow.data.db.DatabaseSeedManager
import com.ledgerflow.data.db.DatabaseSeedingHelper
import com.ledgerflow.data.db.LedgerFlowDatabase
import com.ledgerflow.data.db.dao.*
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.runBlocking
import net.sqlcipher.database.SupportFactory
import timber.log.Timber
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {

    val MIGRATION_1_2 = object : androidx.room.migration.Migration(1, 2) {
        override fun migrate(db: SupportSQLiteDatabase) {
            Timber.d("LedgerFlowDatabase: Starting migration 1 -> 2")
            db.execSQL("ALTER TABLE `transactions` ADD COLUMN `raw_merchant` TEXT")
            db.execSQL("ALTER TABLE `pending_transactions` ADD COLUMN `raw_merchant` TEXT")
            Timber.d("LedgerFlowDatabase: Migration 1 -> 2 completed")
        }
    }

    val MIGRATION_2_3 = object : androidx.room.migration.Migration(2, 3) {
        override fun migrate(db: SupportSQLiteDatabase) {
            Timber.d("LedgerFlowDatabase: Starting migration 2 -> 3")
            // Add new merchant columns
            db.execSQL("ALTER TABLE `merchants` ADD COLUMN `canonical_name` TEXT NOT NULL DEFAULT ''")
            db.execSQL("ALTER TABLE `merchants` ADD COLUMN `logo` TEXT DEFAULT NULL")
            db.execSQL("ALTER TABLE `merchants` ADD COLUMN `icon` TEXT DEFAULT NULL")
            db.execSQL("ALTER TABLE `merchants` ADD COLUMN `preferred_category` TEXT DEFAULT NULL")
            db.execSQL("ALTER TABLE `merchants` ADD COLUMN `preferred_subcategory` TEXT DEFAULT NULL")
            db.execSQL("ALTER TABLE `merchants` ADD COLUMN `confidence_rules` TEXT DEFAULT NULL")
            db.execSQL("ALTER TABLE `merchants` ADD COLUMN `enabled` INTEGER NOT NULL DEFAULT 1")
            db.execSQL("ALTER TABLE `merchants` ADD COLUMN `created_by` TEXT NOT NULL DEFAULT 'SYSTEM'")
            db.execSQL("ALTER TABLE `merchants` ADD COLUMN `is_system` INTEGER NOT NULL DEFAULT 1")
            db.execSQL("ALTER TABLE `merchants` ADD COLUMN `is_user` INTEGER NOT NULL DEFAULT 0")
            db.execSQL("ALTER TABLE `merchants` ADD COLUMN `created_at` INTEGER NOT NULL DEFAULT 0")
            db.execSQL("ALTER TABLE `merchants` ADD COLUMN `updated_at` INTEGER NOT NULL DEFAULT 0")
            db.execSQL("ALTER TABLE `merchants` ADD COLUMN `usage_count` INTEGER NOT NULL DEFAULT 0")
            db.execSQL("ALTER TABLE `merchants` ADD COLUMN `last_used` INTEGER NOT NULL DEFAULT 0")

            // Create normalized merchant_aliases table
            db.execSQL("""
                CREATE TABLE IF NOT EXISTS `merchant_aliases` (
                    `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                    `merchant_id` INTEGER NOT NULL,
                    `alias` TEXT NOT NULL,
                    FOREIGN KEY(`merchant_id`) REFERENCES `merchants`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE
                )
            """.trimIndent())
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_merchant_aliases_merchant_id` ON `merchant_aliases` (`merchant_id`)")
            db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_merchant_aliases_alias` ON `merchant_aliases` (`alias`)")

            // Create normalized merchant_regexes table
            db.execSQL("""
                CREATE TABLE IF NOT EXISTS `merchant_regexes` (
                    `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                    `merchant_id` INTEGER NOT NULL,
                    `pattern` TEXT NOT NULL,
                    FOREIGN KEY(`merchant_id`) REFERENCES `merchants`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE
                )
            """.trimIndent())
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_merchant_regexes_merchant_id` ON `merchant_regexes` (`merchant_id`)")

            // Create developer audit_logs table
            db.execSQL("""
                CREATE TABLE IF NOT EXISTS `audit_logs` (
                    `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                    `operation` TEXT NOT NULL,
                    `timestamp` INTEGER NOT NULL,
                    `details` TEXT NOT NULL
                )
            """.trimIndent())

            Timber.d("LedgerFlowDatabase: Migration 2 -> 3 completed")
        }
    }

    val MIGRATION_3_4 = object : androidx.room.migration.Migration(3, 4) {
        override fun migrate(db: SupportSQLiteDatabase) {
            Timber.d("LedgerFlowDatabase: Starting migration 3 -> 4")
            db.execSQL("""
                CREATE TABLE IF NOT EXISTS `tags` (
                    `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                    `name` TEXT NOT NULL
                )
            """.trimIndent())
            db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `idx_tags_name` ON `tags` (`name`)")
            db.execSQL("""
                CREATE TABLE IF NOT EXISTS `transaction_tags` (
                    `transaction_id` INTEGER NOT NULL,
                    `tag_id` INTEGER NOT NULL,
                    PRIMARY KEY(`transaction_id`, `tag_id`),
                    FOREIGN KEY(`transaction_id`) REFERENCES `transactions`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE,
                    FOREIGN KEY(`tag_id`) REFERENCES `tags`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE
                )
            """.trimIndent())
            db.execSQL("CREATE INDEX IF NOT EXISTS `idx_transaction_tags_txn` ON `transaction_tags` (`transaction_id`)")
            db.execSQL("CREATE INDEX IF NOT EXISTS `idx_transaction_tags_tag` ON `transaction_tags` (`tag_id`)")
            Timber.d("LedgerFlowDatabase: Migration 3 -> 4 completed")
        }
    }

    @Provides
    @Singleton
    fun provideDatabase(
        @ApplicationContext context: Context,
        securityPrefsManager: SecurityPrefsManager
    ): LedgerFlowDatabase {
        val isDebug = (context.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0
        val expectedVersion = 4

        // 1. Pre-open compatibility verification
        val compatibilityError = DatabaseCompatibilityManager.checkCompatibility(context, securityPrefsManager, expectedVersion)
        if (compatibilityError != null) {
            Timber.e("DatabaseCompatibilityManager: Incompatibility detected: $compatibilityError")
            if (isDebug) {
                // Ensure the Recovery UI is triggered so the user is informed
                com.ledgerflow.core.common.util.DatabaseRecoveryState.setIncompatible(
                    currentVersion = 0,
                    expectedVersion = expectedVersion,
                    reason = compatibilityError
                )
                // Fallback to in-memory database to allow recovery screen to run without crashing Hilt injection
                Timber.d("Forensic Log: Database fallback to in-memory triggered.")
                return Room.inMemoryDatabaseBuilder(context, LedgerFlowDatabase::class.java).build()
            }
        }

        val passphrase = try {
            runBlocking {
                securityPrefsManager.getOrCreateDatabasePassphrase()
            }
        } catch (e: Exception) {
            Timber.e(e, "CRITICAL: Database passphrase decryption failed due to key/keystore corruption!")
            if (isDebug) {
                com.ledgerflow.core.common.util.DatabaseRecoveryState.setIncompatible(
                    currentVersion = 0,
                    expectedVersion = expectedVersion,
                    reason = "Decryption failure: ${e.localizedMessage}"
                )
                Timber.d("Forensic Log: Database fallback to in-memory triggered due to decryption failure.")
                return Room.inMemoryDatabaseBuilder(context, LedgerFlowDatabase::class.java).build()
            }
            throw e
        }

        val factory = SupportFactory(passphrase)

        val db = Room.databaseBuilder(
            context,
            LedgerFlowDatabase::class.java,
            "ledgerflow_secure.db"
        )
            .openHelperFactory(factory)
            .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4)
            .addCallback(object : RoomDatabase.Callback() {
                override fun onCreate(db: SupportSQLiteDatabase) {
                    super.onCreate(db)
                    Timber.d("LedgerFlowDatabase: onCreate triggered.")
                    Timber.d("Forensic Log: Database recreation triggered (onCreate callback).")
                    
                    DatabaseSeedManager.seedIfNecessary(db, securityPrefsManager)
                    
                    runBlocking {
                        securityPrefsManager.saveDatabaseCreatedTimestamp(System.currentTimeMillis())
                    }
                }

                override fun onOpen(db: SupportSQLiteDatabase) {
                    super.onOpen(db)
                    Timber.d("LedgerFlowDatabase: onOpen triggered.")
                    Timber.d("LedgerFlowDatabase: Path = %s", db.path)
                    Timber.d("LedgerFlowDatabase: Version = %d", db.version)

                    runBlocking {
                        securityPrefsManager.saveDatabaseLastOpenedTimestamp(System.currentTimeMillis())
                    }
                }
            })
            .build()

        // 2. Post-open verification
        try {
            db.openHelper.writableDatabase
            Timber.d("LedgerFlowDatabase: Room migration and verification verified successfully.")
        } catch (e: Exception) {
            Timber.e(e, "CRITICAL: Database failed to open or migrate. Possible schema corruption!")
            if (isDebug) {
                com.ledgerflow.core.common.util.DatabaseRecoveryState.setIncompatible(
                    currentVersion = 0,
                    expectedVersion = expectedVersion,
                    reason = "Migration/Open error: ${e.localizedMessage}"
                )
                Timber.d("Forensic Log: Database fallback to in-memory triggered due to migration/open error.")
                return Room.inMemoryDatabaseBuilder(context, LedgerFlowDatabase::class.java).build()
            }
            throw e
        }

        return db
    }

    @Provides
    @Singleton
    fun provideTransactionDao(db: LedgerFlowDatabase): TransactionDao = db.transactionDao()

    @Provides
    @Singleton
    fun provideCategoryDao(db: LedgerFlowDatabase): CategoryDao = db.categoryDao()

    @Provides
    @Singleton
    fun provideSubcategoryDao(db: LedgerFlowDatabase): SubcategoryDao = db.subcategoryDao()

    @Provides
    @Singleton
    fun provideMerchantDao(db: LedgerFlowDatabase): MerchantDao = db.merchantDao()

    @Provides
    @Singleton
    fun provideBudgetDao(db: LedgerFlowDatabase): BudgetDao = db.budgetDao()

    @Provides
    @Singleton
    fun providePaymentMethodDao(db: LedgerFlowDatabase): PaymentMethodDao = db.paymentMethodDao()

    @Provides
    @Singleton
    fun provideAttachmentDao(db: LedgerFlowDatabase): AttachmentDao = db.attachmentDao()

    @Provides
    @Singleton
    fun providePendingTransactionDao(db: LedgerFlowDatabase): PendingTransactionDao = db.pendingTransactionDao()

    @Provides
    @Singleton
    fun provideMerchantPreferenceDao(db: LedgerFlowDatabase): MerchantPreferenceDao = db.merchantPreferenceDao()

    @Provides
    @Singleton
    fun provideMerchantAliasDao(db: LedgerFlowDatabase): com.ledgerflow.data.db.dao.MerchantAliasDao = db.merchantAliasDao()

    @Provides
    @Singleton
    fun provideMerchantRegexDao(db: LedgerFlowDatabase): com.ledgerflow.data.db.dao.MerchantRegexDao = db.merchantRegexDao()

    @Provides
    @Singleton
    fun provideAuditLogDao(db: LedgerFlowDatabase): com.ledgerflow.data.db.dao.AuditLogDao = db.auditLogDao()

    @Provides
    @Singleton
    fun provideTagDao(db: LedgerFlowDatabase): TagDao = db.tagDao()
}
