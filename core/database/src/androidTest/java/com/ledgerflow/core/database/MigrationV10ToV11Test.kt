package com.ledgerflow.core.database

import androidx.room.testing.MigrationTestHelper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.truth.Truth.assertThat
import com.ledgerflow.core.database.migration.MIGRATION_10_11
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * v10 -> v11, the BUG8 gate for P4's two tables.
 *
 * **Additive, like `MIGRATION_8_9` and unlike `MIGRATION_9_10`** — no existing
 * table is read or rewritten, so the assertions are not about what survived an
 * `INSERT ... SELECT`. They are about three things a `runMigrationsAndValidate`
 * on its own does not check.
 *
 * Room's validation compares the live schema against the expected *shape*, and
 * it is genuinely strong here: a mistyped column or a missing index fails it.
 * What it does not check is **behaviour**, and this migration adds two pieces
 * of behaviour that a shape comparison passes either way:
 *
 * 1. **The foreign key actually cascades.** `attachment.entry_id` is the column
 *    `LedgerEntryEntity`'s KDoc names as half the reason the ledger is one
 *    partitioned table rather than two. A declared-but-unenforced FK looks
 *    identical in the schema JSON, and SQLite enforces foreign keys only when
 *    `PRAGMA foreign_keys` is on — which is exactly the pragma migrations turn
 *    *off*. So it is exercised rather than assumed.
 *
 * 2. **A NULL `entry_id` is allowed.** An attachment exists before its entry
 *    does — OCR writes the image, approval creates the entry — so the FK must
 *    not reject an unattached row. A `NOT NULL` slipped onto that column would
 *    also pass a casual reading of the schema.
 *
 * 3. **`item_category_memory`'s composite key merges on the `''` sentinel.**
 *    The whole reason `merchant_id` is `''` rather than NULL is that SQLite
 *    treats NULLs as distinct inside a key, so a nullable column would let the
 *    same item accumulate a row per observation and never a second hit. That is
 *    a property of the data, not of the DDL, and it is the one this table would
 *    be useless without.
 *
 * Existing data is seeded anyway, because "additive" is a claim and the cheap
 * way to keep it honest is to put rows on the other side of the migration and
 * count them afterwards.
 */
@RunWith(AndroidJUnit4::class)
class MigrationV10ToV11Test {

    private companion object {
        const val TEST_DB = "migration-v10-v11-test.db"
        const val V10 = 10
        const val V11 = 11

        val PASSPHRASE = ByteArray(32) { (it + 61).toByte() }

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

    /** One ledger entry, so the new foreign key has a real parent to point at. */
    private fun seedV10() {
        helper.createDatabase(TEST_DB, V10).use { db ->
            db.execSQL(
                "INSERT INTO ledger_entry (id, ledger, amount_minor, currency, " +
                    "occurred_at, local_date, source, is_recurring, created_at, updated_at) " +
                    "VALUES ('e-1', 'DEBIT', 47300, 'INR', 1787000000000, 20700, " +
                    "'OCR', 0, 1787000000000, 1787000000000)",
            )
            db.execSQL(
                "INSERT INTO budget (id, category_id, subcategory_id, period, " +
                    "amount_minor, start_date, rollover_enabled, alert_thresholds, " +
                    "deleted_at, last_alerted_threshold, alert_period_start) VALUES " +
                    "('b-1', 'cat-grocery', NULL, 'MONTHLY', 1200000, 20000, 0, " +
                    "'80,100', NULL, 80, 20670)",
            )
        }
    }

    /**
     * The migration runs, validates, and disturbs nothing that was there.
     *
     * `runMigrationsAndValidate` with `true` is the schema half. The row counts
     * are the "purely additive" claim, checked rather than asserted in a
     * comment — including `budget`'s v10 alert columns, which are the most
     * recently added and therefore the most plausible thing for a careless
     * migration to have rebuilt away.
     */
    @Test
    fun migrate_addsBothTablesAndDisturbsNothing() {
        seedV10()

        val db = helper.runMigrationsAndValidate(TEST_DB, V11, true, MIGRATION_10_11)

        db.query("SELECT COUNT(*) FROM attachment").use { cursor ->
            assertThat(cursor.moveToNext()).isTrue()
            assertThat(cursor.getInt(0)).isEqualTo(0)
        }
        db.query("SELECT COUNT(*) FROM item_category_memory").use { cursor ->
            assertThat(cursor.moveToNext()).isTrue()
            assertThat(cursor.getInt(0)).isEqualTo(0)
        }

        db.query(
            "SELECT id, amount_minor, last_alerted_threshold, alert_period_start FROM budget",
        ).use { cursor ->
            assertThat(cursor.count).isEqualTo(1)
            assertThat(cursor.moveToNext()).isTrue()
            assertThat(cursor.getString(0)).isEqualTo("b-1")
            assertThat(cursor.getLong(1)).isEqualTo(1_200_000L)
            assertThat(cursor.getInt(2)).isEqualTo(80)
            assertThat(cursor.getInt(3)).isEqualTo(20_670)
        }

        db.query("SELECT COUNT(*) FROM ledger_entry").use { cursor ->
            assertThat(cursor.moveToNext()).isTrue()
            assertThat(cursor.getInt(0)).isEqualTo(1)
        }
    }

    /**
     * An attachment may exist with no entry, and is destroyed with the entry it
     * has.
     *
     * Both halves in one test because they are the same column's two states and
     * the second is meaningless without the first. `PRAGMA foreign_keys` is
     * turned on explicitly: SQLite defaults it **off** per connection, so a
     * cascade asserted without it would pass against a table that has no
     * enforcement at all — the failure this test exists to rule out.
     */
    @Test
    fun migrate_attachmentAllowsNullEntryAndCascadesOnDelete() {
        seedV10()
        val db = helper.runMigrationsAndValidate(TEST_DB, V11, true, MIGRATION_10_11)

        db.execSQL("PRAGMA foreign_keys = ON")

        // Unattached: OCR has written the image, approval has not happened.
        db.execSQL(
            "INSERT INTO attachment (id, entry_id, file_path, mime, sha256, " +
                "bytes, created_at) VALUES " +
                "('a-pending', NULL, 'a-pending.jpg', 'image/jpeg', 'ab01', " +
                "250000, 1787000000000)",
        )
        // Attached to the seeded entry.
        db.execSQL(
            "INSERT INTO attachment (id, entry_id, file_path, mime, sha256, " +
                "bytes, created_at) VALUES " +
                "('a-linked', 'e-1', 'a-linked.jpg', 'image/jpeg', 'cd02', " +
                "260000, 1787000000000)",
        )

        db.query("SELECT COUNT(*) FROM attachment").use { cursor ->
            assertThat(cursor.moveToNext()).isTrue()
            assertThat(cursor.getInt(0)).isEqualTo(2)
        }

        db.execSQL("DELETE FROM ledger_entry WHERE id = 'e-1'")

        db.query("SELECT id FROM attachment").use { cursor ->
            assertThat(cursor.count).isEqualTo(1)
            assertThat(cursor.moveToNext()).isTrue()
            // The linked one went with its entry; the pending one did not.
            assertThat(cursor.getString(0)).isEqualTo("a-pending")
        }
    }

    /**
     * The `''` sentinel merges; a second observation is a second hit.
     *
     * This is the property the sentinel exists for. Were `merchant_id`
     * nullable, SQLite would treat each NULL as distinct inside the composite
     * key, the `ON CONFLICT` would never fire, and the table would accumulate a
     * row per observation while every `hit_count` stayed 1 — a memory that
     * remembers nothing, with no error anywhere.
     */
    @Test
    fun migrate_itemCategoryMemoryMergesOnTheNoMerchantSentinel() {
        seedV10()
        val db = helper.runMigrationsAndValidate(TEST_DB, V11, true, MIGRATION_10_11)

        val upsert =
            "INSERT INTO item_category_memory " +
                "(merchant_id, normalized_item, category_id, subcategory_id, hit_count) " +
                "VALUES ('', 'milk 500ml', 'cat-grocery', NULL, 1) " +
                "ON CONFLICT(merchant_id, normalized_item) DO UPDATE SET " +
                "hit_count = hit_count + 1"

        db.execSQL(upsert)
        db.execSQL(upsert)

        db.query(
            "SELECT merchant_id, normalized_item, category_id, hit_count " +
                "FROM item_category_memory",
        ).use { cursor ->
            assertThat(cursor.count).isEqualTo(1)
            assertThat(cursor.moveToNext()).isTrue()
            assertThat(cursor.getString(0)).isEqualTo("")
            assertThat(cursor.getString(1)).isEqualTo("milk 500ml")
            assertThat(cursor.getString(2)).isEqualTo("cat-grocery")
            assertThat(cursor.getInt(3)).isEqualTo(2)
        }
    }

    /**
     * `PRAGMA foreign_key_check` is clean after the migration.
     *
     * `CLAUDE.md` §7 requires this after every migration. It is close to
     * vacuous on an additive one — which is the point of running it here rather
     * than only where it is interesting: the first migration that alters a
     * table with this new foreign key pointing at it is the one that needs the
     * check already in place and already trusted.
     */
    @Test
    fun migrate_leavesNoForeignKeyViolations() {
        seedV10()
        val db = helper.runMigrationsAndValidate(TEST_DB, V11, true, MIGRATION_10_11)

        db.query("PRAGMA foreign_key_check").use { cursor ->
            assertThat(cursor.count).isEqualTo(0)
        }
    }
}
