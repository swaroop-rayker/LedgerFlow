package com.ledgerflow.core.database

import androidx.room.testing.MigrationTestHelper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.truth.Truth.assertThat
import com.ledgerflow.core.database.migration.MIGRATION_12_13
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * v12 -> v13, the BUG8 gate for merchant categories (V2-PLAN §5, V2-6).
 *
 * **The first rebuild of a table other tables point at.** `ledger_entry` and
 * `merchant_alias` both reference `merchant`; had foreign keys been on while it
 * was dropped, every entry would have lost its merchant and every alias would
 * have gone. So the seed is two merchants (one hidden), an alias on each, and
 * entries in both books, and the assertions are that every one of them is
 * still there and still pointing where it did. Room's validation compares the
 * shape only.
 */
@RunWith(AndroidJUnit4::class)
class MigrationV12ToV13Test {

    private companion object {
        const val TEST_DB = "migration-v12-v13-test.db"
        const val V12 = 12
        const val V13 = 13

        val PASSPHRASE = ByteArray(32) { (it + 71).toByte() }

        init {
            System.loadLibrary("sqlcipher")
        }
    }

    @get:Rule
    val helper: MigrationTestHelper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        LedgerFlowDatabase::class.java,
        emptyList(),
        SupportOpenHelperFactory(PASSPHRASE),
    )

    private fun merchant(id: String, name: String, defaultCategory: String?, deletedAt: Long) =
        "INSERT INTO merchant (id, canonical_name, normalized_key, default_category_id, logo_ref, deleted_at) " +
            "VALUES ('$id', '$name', '${name.lowercase()}', ${defaultCategory?.let { "'$it'" } ?: "NULL"}, " +
            "NULL, $deletedAt)"

    private fun alias(id: String, merchantId: String, alias: String) =
        "INSERT INTO merchant_alias (id, merchant_id, alias, normalized_alias) " +
            "VALUES ('$id', '$merchantId', '$alias', '${alias.lowercase()}')"

    private fun entry(id: String, ledger: String, merchantId: String, amount: Long) =
        "INSERT INTO ledger_entry (id, ledger, amount_minor, currency, occurred_at, local_date, " +
            "merchant_id, source, created_at, updated_at) VALUES ('$id', '$ledger', $amount, 'INR', " +
            "1000, 20000, '$merchantId', 'MANUAL', 1000, 1000)"

    private fun seedV12() {
        helper.createDatabase(TEST_DB, V12).use { db ->
            db.execSQL(merchant("m-grocer", "Corner Grocer", "cat-groceries", 0L))
            db.execSQL(merchant("m-old", "Old Shop", null, 5_000L))
            db.execSQL(alias("a-1", "m-grocer", "CORNER GROCER*4821"))
            db.execSQL(alias("a-2", "m-old", "OLDSHOP"))
            db.execSQL(entry("e-debit", "DEBIT", "m-grocer", 1_240_50L))
            db.execSQL(entry("e-credit", "CREDIT", "m-old", 85_000_00L))
        }
    }

    /** **The assertion.** Every merchant survives, columns intact, all Uncategorised. */
    @Test
    fun migrate_keepsEveryMerchant_andFilesNoneOfThem() {
        seedV12()

        val db = helper.runMigrationsAndValidate(TEST_DB, V13, true, MIGRATION_12_13)

        db.query(
            "SELECT id, canonical_name, normalized_key, default_category_id, deleted_at, " +
                "merchant_category_id FROM merchant ORDER BY id",
        ).use { cursor ->
            assertThat(cursor.count).isEqualTo(2)

            assertThat(cursor.moveToNext()).isTrue()
            assertThat(cursor.getString(0)).isEqualTo("m-grocer")
            assertThat(cursor.getString(1)).isEqualTo("Corner Grocer")
            assertThat(cursor.getString(2)).isEqualTo("corner grocer")
            assertThat(cursor.getString(3)).isEqualTo("cat-groceries")
            assertThat(cursor.getLong(4)).isEqualTo(0L)
            assertThat(cursor.isNull(5)).isTrue()

            assertThat(cursor.moveToNext()).isTrue()
            assertThat(cursor.getString(0)).isEqualTo("m-old")
            assertThat(cursor.getLong(4)).isEqualTo(5_000L)
            assertThat(cursor.isNull(5)).isTrue()
        }
        db.query("SELECT COUNT(*) FROM merchant_category").use { cursor ->
            cursor.moveToFirst()
            assertThat(cursor.getInt(0)).isEqualTo(0)
        }
    }

    /** The data loss this rebuild could cause, checked directly: aliases kept, entries still pointing. */
    @Test
    fun migrate_keepsEveryAlias_andEveryEntrysMerchant() {
        seedV12()

        val db = helper.runMigrationsAndValidate(TEST_DB, V13, true, MIGRATION_12_13)

        db.query("SELECT id, merchant_id FROM merchant_alias ORDER BY id").use { cursor ->
            assertThat(cursor.count).isEqualTo(2)
            cursor.moveToNext()
            assertThat(cursor.getString(1)).isEqualTo("m-grocer")
            cursor.moveToNext()
            assertThat(cursor.getString(1)).isEqualTo("m-old")
        }
        db.query("SELECT id, merchant_id, amount_minor FROM ledger_entry ORDER BY id").use { cursor ->
            assertThat(cursor.count).isEqualTo(2)
            cursor.moveToNext()
            assertThat(cursor.getString(0)).isEqualTo("e-credit")
            assertThat(cursor.getString(1)).isEqualTo("m-old")
            assertThat(cursor.getLong(2)).isEqualTo(85_000_00L)
            cursor.moveToNext()
            assertThat(cursor.getString(0)).isEqualTo("e-debit")
            assertThat(cursor.getString(1)).isEqualTo("m-grocer")
        }
    }

    /** `DROP TABLE` takes the indexes with it; the rebuild must put both back. */
    @Test
    fun migrate_recreatesTheMerchantIndexes_andAddsTheNewTablesIndex() {
        seedV12()

        val db = helper.runMigrationsAndValidate(TEST_DB, V13, true, MIGRATION_12_13)

        val names = mutableSetOf<String>()
        db.query("SELECT name FROM sqlite_master WHERE type = 'index'").use { cursor ->
            while (cursor.moveToNext()) names += cursor.getString(0)
        }
        assertThat(names).containsAtLeast(
            "index_merchant_normalized_key",
            "index_merchant_merchant_category_id",
            "index_merchant_category_unique_live_name",
        )
    }

    /** CLAUDE.md §7: `PRAGMA foreign_key_check` after every migration. */
    @Test
    fun migrate_leavesNoForeignKeyViolation() {
        seedV12()

        val db = helper.runMigrationsAndValidate(TEST_DB, V13, true, MIGRATION_12_13)

        db.query("PRAGMA foreign_key_check").use { cursor ->
            assertThat(cursor.count).isEqualTo(0)
        }
    }

    /**
     * With foreign keys on, `DROP TABLE merchant` would cascade, so the
     * migration refuses rather than run: a loud failure the pre-migration
     * snapshot recovers from, never quiet loss.
     */
    @Test
    fun migrate_withForeignKeysOn_refuses() {
        seedV12()
        // Reopened at v12 with no migration: the database as the app would find it.
        val db = helper.runMigrationsAndValidate(TEST_DB, V12, false)
        db.execSQL("PRAGMA foreign_keys = ON")

        assertThrows(IllegalStateException::class.java) { MIGRATION_12_13.migrate(db) }

        db.query("SELECT COUNT(*) FROM merchant_alias").use { cursor ->
            cursor.moveToFirst()
            assertThat(cursor.getInt(0)).isEqualTo(2)
        }
    }

    /** The install with no merchants yet; a mistyped column list still throws here. */
    @Test
    fun migrate_withNoMerchants_succeeds() {
        helper.createDatabase(TEST_DB, V12).close()

        val db = helper.runMigrationsAndValidate(TEST_DB, V13, true, MIGRATION_12_13)

        db.query("SELECT COUNT(*) FROM merchant").use { cursor ->
            cursor.moveToFirst()
            assertThat(cursor.getInt(0)).isEqualTo(0)
        }
    }
}
