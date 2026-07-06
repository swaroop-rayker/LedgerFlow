package com.ledgerflow.data.db.migration

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.ledgerflow.data.datastore.SecurityPrefsManager
import com.ledgerflow.data.db.LedgerFlowDatabase
import com.ledgerflow.data.repository.DatabaseToolsRepositoryImpl
import com.ledgerflow.domain.model.Result
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class DatabaseMigrationTest {

    private lateinit var context: Context
    private lateinit var database: LedgerFlowDatabase
    private lateinit var securityPrefsManager: SecurityPrefsManager
    private lateinit var repository: DatabaseToolsRepositoryImpl

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        val keyStoreManager = org.mockito.Mockito.mock(com.ledgerflow.core.security.crypto.KeyStoreManager::class.java)
        securityPrefsManager = SecurityPrefsManager(context, keyStoreManager)
        
        database = Room.inMemoryDatabaseBuilder(
            context,
            LedgerFlowDatabase::class.java
        )
            .allowMainThreadQueries()
            .build()
            
        repository = DatabaseToolsRepositoryImpl(
            context = context,
            database = database,
            securityPrefsManager = securityPrefsManager,
            ioDispatcher = Dispatchers.Unconfined
        )
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun testDatabaseCreationAndSeeding() = runBlocking {
        // Manually seed database to simulate onCreate/onOpen callback behaviour
        val db = database.openHelper.writableDatabase
        com.ledgerflow.data.db.DatabaseSeedingHelper.seedDatabase(db)

        val statsResult = repository.getDatabaseStats()
        assertTrue(statsResult is Result.Success)
        val stats = (statsResult as Result.Success).data
        
        // Assert initial counts
        assertEquals(12, stats.categoryCount)
        assertEquals(19, stats.subcategoryCount)
        assertEquals(0, stats.expenseCount)
    }

    @Test
    fun testFactoryResetReSeedsDefaults() = runBlocking {
        val db = database.openHelper.writableDatabase
        com.ledgerflow.data.db.DatabaseSeedingHelper.seedDatabase(db)

        // Reset
        val resetResult = repository.factoryReset()
        assertTrue(resetResult is Result.Success)

        val statsResult = repository.getDatabaseStats()
        assertTrue(statsResult is Result.Success)
        val stats = (statsResult as Result.Success).data
        
        assertEquals(12, stats.categoryCount)
        assertEquals(19, stats.subcategoryCount)
        assertEquals(0, stats.expenseCount)
    }

    @Test
    fun testSelectiveResetDeletesRequestedTables() = runBlocking {
        val db = database.openHelper.writableDatabase
        com.ledgerflow.data.db.DatabaseSeedingHelper.seedDatabase(db)

        // Clear only categories
        val resetResult = repository.selectiveReset(listOf("categories", "subcategories"))
        assertTrue(resetResult is Result.Success)

        val statsResult = repository.getDatabaseStats()
        assertTrue(statsResult is Result.Success)
        val stats = (statsResult as Result.Success).data
        
        assertEquals(0, stats.categoryCount)
        assertEquals(0, stats.subcategoryCount)
    }

    @Test
    fun testMigration1To2() = runBlocking {
        val helper = androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory().create(
            androidx.sqlite.db.SupportSQLiteOpenHelper.Configuration.builder(context)
                .name(null)
                .callback(object : androidx.sqlite.db.SupportSQLiteOpenHelper.Callback(1) {
                    override fun onCreate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                        db.execSQL("""
                            CREATE TABLE `transactions` (
                                `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                                `amount` INTEGER NOT NULL,
                                `merchant` TEXT NOT NULL,
                                `category` TEXT NOT NULL,
                                `subcategory` TEXT,
                                `payment_method` TEXT,
                                `reference` TEXT,
                                `timestamp` INTEGER NOT NULL,
                                `notes` TEXT
                            )
                        """.trimIndent())
                        db.execSQL("""
                            CREATE TABLE `pending_transactions` (
                                `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                                `amount` INTEGER NOT NULL,
                                `merchant` TEXT NOT NULL,
                                `category` TEXT NOT NULL,
                                `subcategory` TEXT,
                                `payment_method` TEXT,
                                `reference` TEXT,
                                `timestamp` INTEGER NOT NULL,
                                `notes` TEXT,
                                `confidence` INTEGER NOT NULL,
                                `status` TEXT NOT NULL,
                                `created_at` INTEGER NOT NULL,
                                `updated_at` INTEGER NOT NULL
                            )
                        """.trimIndent())
                    }
                    override fun onUpgrade(db: androidx.sqlite.db.SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) {}
                })
                .build()
        )
        val db = helper.writableDatabase
        
        val cv = android.content.ContentValues().apply {
            put("id", 1)
            put("amount", 100)
            put("merchant", "Amazon Pay")
            put("category", "Shopping")
            put("timestamp", System.currentTimeMillis())
        }
        db.insert("transactions", android.database.sqlite.SQLiteDatabase.CONFLICT_REPLACE, cv)

        com.ledgerflow.data.di.DatabaseModule.MIGRATION_1_2.migrate(db)

        db.query("SELECT * FROM transactions WHERE id = 1").use { c ->
            assertTrue(c.moveToFirst())
            val rawMerchantIndex = c.getColumnIndex("raw_merchant")
            assertTrue(rawMerchantIndex != -1)
            assertTrue(c.isNull(rawMerchantIndex))
        }
        
        helper.close()
    }

    @Test
    fun testMigration2To3() = runBlocking {
        val helper = androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory().create(
            androidx.sqlite.db.SupportSQLiteOpenHelper.Configuration.builder(context)
                .name(null)
                .callback(object : androidx.sqlite.db.SupportSQLiteOpenHelper.Callback(2) {
                    override fun onCreate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                        db.execSQL("""
                            CREATE TABLE `merchants` (
                                `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                                `display_name` TEXT NOT NULL,
                                `normalized_name` TEXT NOT NULL,
                                `is_archived` INTEGER NOT NULL,
                                `default_category_id` INTEGER,
                                `notes` TEXT,
                                FOREIGN KEY(`default_category_id`) REFERENCES `categories`(`id`) ON UPDATE NO ACTION ON DELETE SET NULL
                            )
                        """.trimIndent())
                        db.execSQL("CREATE UNIQUE INDEX `idx_merchants_normalized` ON `merchants` (`normalized_name`)")
                    }
                    override fun onUpgrade(db: androidx.sqlite.db.SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) {}
                })
                .build()
        )
        val db = helper.writableDatabase
        
        val cv = android.content.ContentValues().apply {
            put("id", 1)
            put("display_name", "Test Merchant")
            put("normalized_name", "test merchant")
            put("is_archived", 0)
        }
        db.insert("merchants", android.database.sqlite.SQLiteDatabase.CONFLICT_REPLACE, cv)

        com.ledgerflow.data.di.DatabaseModule.MIGRATION_2_3.migrate(db)

        db.query("SELECT * FROM merchants WHERE id = 1").use { c ->
            assertTrue(c.moveToFirst())
            val canonicalNameIdx = c.getColumnIndex("canonical_name")
            val enabledIdx = c.getColumnIndex("enabled")
            assertTrue(canonicalNameIdx != -1)
            assertTrue(enabledIdx != -1)
            assertEquals("", c.getString(canonicalNameIdx))
            assertEquals(1, c.getInt(enabledIdx))
        }
        
        // Verify new tables are successfully created
        db.query("SELECT COUNT(*) FROM merchant_aliases").use { c ->
            assertTrue(c.moveToFirst())
            assertEquals(0, c.getInt(0))
        }
        db.query("SELECT COUNT(*) FROM merchant_regexes").use { c ->
            assertTrue(c.moveToFirst())
            assertEquals(0, c.getInt(0))
        }
        db.query("SELECT COUNT(*) FROM audit_logs").use { c ->
            assertTrue(c.moveToFirst())
            assertEquals(0, c.getInt(0))
        }
        
        helper.close()
    }

    @Test
    fun testMigration3To4() = runBlocking {
        val helper = androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory().create(
            androidx.sqlite.db.SupportSQLiteOpenHelper.Configuration.builder(context)
                .name(null)
                .callback(object : androidx.sqlite.db.SupportSQLiteOpenHelper.Callback(3) {
                    override fun onCreate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                        db.execSQL("""
                            CREATE TABLE `transactions` (
                                `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                                `amount` INTEGER NOT NULL,
                                `merchant` TEXT NOT NULL,
                                `category` TEXT NOT NULL,
                                `subcategory` TEXT,
                                `payment_method` TEXT,
                                `reference` TEXT,
                                `timestamp` INTEGER NOT NULL,
                                `notes` TEXT,
                                `raw_merchant` TEXT
                            )
                        """.trimIndent())
                    }
                    override fun onUpgrade(db: androidx.sqlite.db.SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) {}
                })
                .build()
        )
        val db = helper.writableDatabase

        // Execute migration 3 -> 4
        com.ledgerflow.data.di.DatabaseModule.MIGRATION_3_4.migrate(db)

        // Verify tags and transaction_tags tables are successfully created
        db.query("SELECT COUNT(*) FROM tags").use { c ->
            assertTrue(c.moveToFirst())
            assertEquals(0, c.getInt(0))
        }
        db.query("SELECT COUNT(*) FROM transaction_tags").use { c ->
            assertTrue(c.moveToFirst())
            assertEquals(0, c.getInt(0))
        }

        helper.close()
    }
}

