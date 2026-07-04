package com.ledgerflow.data.di

import android.content.Context
import androidx.room.Room
import com.ledgerflow.data.datastore.SecurityPrefsManager
import com.ledgerflow.data.db.LedgerFlowDatabase
import com.ledgerflow.data.db.dao.*
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.runBlocking
import net.sqlcipher.database.SupportFactory
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {

    @Provides
    @Singleton
    fun provideDatabase(
        @ApplicationContext context: Context,
        securityPrefsManager: SecurityPrefsManager
    ): LedgerFlowDatabase {
        // Fetch database passphrase synchronously on startup
        val passphrase = runBlocking {
            securityPrefsManager.getOrCreateDatabasePassphrase()
        }

        // Configure SQLCipher support factory
        val factory = SupportFactory(passphrase)

        return Room.databaseBuilder(
            context,
            LedgerFlowDatabase::class.java,
            "ledgerflow_secure.db"
        )
            .openHelperFactory(factory)
            .addCallback(object : androidx.room.RoomDatabase.Callback() {
                override fun onCreate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                    super.onCreate(db)
                    // Insert default categories
                    db.execSQL("INSERT INTO categories (id, name, parent_id, is_archived, color, icon) VALUES (1, 'Food', NULL, 0, '#FF9800', '🍔')")
                    db.execSQL("INSERT INTO categories (id, name, parent_id, is_archived, color, icon) VALUES (2, 'Transportation', NULL, 0, '#2196F3', '🚗')")
                    db.execSQL("INSERT INTO categories (id, name, parent_id, is_archived, color, icon) VALUES (3, 'Utilities', NULL, 0, '#4CAF50', '💡')")
                    db.execSQL("INSERT INTO categories (id, name, parent_id, is_archived, color, icon) VALUES (4, 'Salary', NULL, 0, '#E91E63', '💵')")
                    
                    // Insert default payment methods
                    db.execSQL("INSERT INTO payment_methods (id, name, type) VALUES (1, 'Cash', 'CASH')")
                    db.execSQL("INSERT INTO payment_methods (id, name, type) VALUES (2, 'UPI / Bank', 'BANK')")
                    db.execSQL("INSERT INTO payment_methods (id, name, type) VALUES (3, 'Credit Card', 'CARD')")
                    
                    // Insert default merchants
                    db.execSQL("INSERT INTO merchants (id, display_name, normalized_name, is_archived, default_category_id, notes) VALUES (1, 'General Merchant', 'general merchant', 0, NULL, 'Default merchant')")
                }
            })
            .build()
    }

    @Provides
    @Singleton
    fun provideTransactionDao(db: LedgerFlowDatabase): TransactionDao = db.transactionDao()

    @Provides
    @Singleton
    fun provideCategoryDao(db: LedgerFlowDatabase): CategoryDao = db.categoryDao()

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
    fun provideTagDao(db: LedgerFlowDatabase): TagDao = db.tagDao()

    @Provides
    @Singleton
    fun providePendingDatabase(
        @ApplicationContext context: Context,
        securityPrefsManager: SecurityPrefsManager
    ): com.ledgerflow.data.db.PendingDatabase {
        val passphrase = runBlocking {
            securityPrefsManager.getOrCreateQueueDatabasePassphrase()
        }
        val factory = SupportFactory(passphrase)
        return Room.databaseBuilder(
            context,
            com.ledgerflow.data.db.PendingDatabase::class.java,
            "ledgerflow_pending_queue.db"
        )
            .openHelperFactory(factory)
            .build()
    }

    @Provides
    @Singleton
    fun providePendingTransactionDao(db: com.ledgerflow.data.db.PendingDatabase): com.ledgerflow.data.db.dao.PendingTransactionDao = db.pendingTransactionDao()
}
