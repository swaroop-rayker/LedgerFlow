package com.ledgerflow.core.database

import androidx.room.testing.MigrationTestHelper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.truth.Truth.assertThat
import com.ledgerflow.core.database.migration.MIGRATION_8_9
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * v8 -> v9, the BUG8 gate for `budget` and `daily_rollup`.
 *
 * **This migration adds tables and touches nothing**, which changes what the
 * test has to be suspicious of. `MigrationV7ToV8Test` guards a rebuild, so its
 * assertions are about what came through an `INSERT ... SELECT`. There is no
 * projection here to mistype. The risk is the opposite one: that a migration
 * described as additive turns out not to be, and the way to catch that is to
 * seed a *populated* v8 ledger and assert that every row is exactly where it
 * was afterwards.
 *
 * So the ledger is seeded with the shapes P3 will actually aggregate over:
 *
 * - a **plain debit** with an entry-level category — the ordinary row;
 * - an **itemised debit** with no entry-level category at all and two line
 *   items filed to different categories (ADR-0018) — the row that makes
 *   `txn_count` ambiguous and the reason §5.6's rule had to be decided before
 *   this schema was committed;
 * - a **credit**, so Law 2 has something on the other side of the partition to
 *   be wrong about;
 * - a **soft-deleted debit**, because ADR-0006 builds rollups from live rows
 *   only and this is the row that must not appear in them.
 *
 * None of that is aggregated here — no rollup writer exists yet at v9, by
 * design (`MIGRATION_8_9` deliberately runs no backfill). The seeds exist so
 * that the migration is proven not to disturb them, and so the fixture is
 * already in place for the rollup tests that come next.
 */
@RunWith(AndroidJUnit4::class)
class MigrationV8ToV9Test {

    private companion object {
        const val TEST_DB = "migration-v8-v9-test.db"
        const val V8 = 8
        const val V9 = 9

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

    private fun seedV8() {
        helper.createDatabase(TEST_DB, V8).use { db ->
            db.execSQL(
                "INSERT INTO category (id, parent_id, parent_key, name, icon, color_argb, " +
                    "ledger_scope, sort_order, is_system, deleted_at) VALUES " +
                    "('cat-grocery', NULL, '', 'Groceries', 'basket', 1, 'DEBIT', 0, 0, 0)",
            )
            db.execSQL(
                "INSERT INTO category (id, parent_id, parent_key, name, icon, color_argb, " +
                    "ledger_scope, sort_order, is_system, deleted_at) VALUES " +
                    "('cat-home', NULL, '', 'Home', 'house', 2, 'DEBIT', 1, 0, 0)",
            )

            // The ordinary debit: one category, no lines.
            insertEntry(db, "e-plain", "DEBIT", 45000, 20000, "cat-grocery", null)
            // ADR-0018's itemised entry: NO entry-level category, two lines.
            insertEntry(db, "e-itemised", "DEBIT", 100000, 20000, null, null)
            db.execSQL(
                "INSERT INTO line_item (id, entry_id, position, name, normalized_name, " +
                    "quantity_milli, unit_price_minor, total_minor, kind, category_id, " +
                    "subcategory_id) VALUES " +
                    "('li-1', 'e-itemised', 0, 'Rice', 'rice', 1000, 60000, 60000, " +
                    "'ITEM', 'cat-grocery', NULL)",
            )
            db.execSQL(
                "INSERT INTO line_item (id, entry_id, position, name, normalized_name, " +
                    "quantity_milli, unit_price_minor, total_minor, kind, category_id, " +
                    "subcategory_id) VALUES " +
                    "('li-2', 'e-itemised', 1, 'Kettle', 'kettle', 1000, 40000, 40000, " +
                    "'ITEM', 'cat-home', NULL)",
            )
            // The other book.
            insertEntry(db, "e-credit", "CREDIT", 500000, 20000, null, null)
            // Binned: live in the table, absent from the views, and therefore
            // absent from any rollup ADR-0006 builds.
            insertEntry(db, "e-binned", "DEBIT", 9900, 20001, "cat-home", 1_700_000_000_000L)
        }
    }

    @Suppress("LongParameterList")
    private fun insertEntry(
        db: androidx.sqlite.db.SupportSQLiteDatabase,
        id: String,
        ledger: String,
        amountMinor: Long,
        localDate: Int,
        categoryId: String?,
        deletedAt: Long?,
    ) {
        val category = categoryId?.let { "'$it'" } ?: "NULL"
        val deleted = deletedAt?.toString() ?: "NULL"
        db.execSQL(
            "INSERT INTO ledger_entry (id, ledger, amount_minor, currency, " +
                "original_amount_minor, original_currency, fx_rate_micro, occurred_at, " +
                "local_date, merchant_id, category_id, subcategory_id, payment_method_id, " +
                "note, source, source_ref_id, is_recurring, created_at, updated_at, " +
                "deleted_at) VALUES " +
                "('$id', '$ledger', $amountMinor, 'INR', NULL, NULL, NULL, 1000, " +
                "$localDate, NULL, $category, NULL, NULL, NULL, 'MANUAL', NULL, 0, " +
                "1000, 1000, $deleted)",
        )
    }

    /**
     * **The assertion this migration is actually about:** additive means
     * additive.
     *
     * Every seeded row is still present, in its own book, with its amount and
     * its filing unchanged — including the itemised entry's *absent* category,
     * which is a value ADR-0018 chose deliberately and which a well-meaning
     * migration could "fix" into something non-null.
     */
    @Test
    fun migrate_leavesEveryExistingRowUntouched() {
        seedV8()

        val db = helper.runMigrationsAndValidate(TEST_DB, V9, true, MIGRATION_8_9)

        db.query(
            "SELECT id, ledger, amount_minor, local_date, category_id, deleted_at " +
                "FROM ledger_entry ORDER BY id",
        ).use { cursor ->
            assertThat(cursor.count).isEqualTo(4)

            assertThat(cursor.moveToNext()).isTrue()
            assertThat(cursor.getString(0)).isEqualTo("e-binned")
            assertThat(cursor.getLong(5)).isEqualTo(1_700_000_000_000L)

            assertThat(cursor.moveToNext()).isTrue()
            assertThat(cursor.getString(0)).isEqualTo("e-credit")
            assertThat(cursor.getString(1)).isEqualTo("CREDIT")
            assertThat(cursor.getLong(2)).isEqualTo(500000L)

            assertThat(cursor.moveToNext()).isTrue()
            assertThat(cursor.getString(0)).isEqualTo("e-itemised")
            assertThat(cursor.getLong(2)).isEqualTo(100000L)
            // ADR-0018: an itemised entry has no entry-level category, and that
            // null is the filing decision, not a gap to be filled.
            assertThat(cursor.isNull(4)).isTrue()

            assertThat(cursor.moveToNext()).isTrue()
            assertThat(cursor.getString(0)).isEqualTo("e-plain")
            assertThat(cursor.getString(1)).isEqualTo("DEBIT")
            assertThat(cursor.getLong(2)).isEqualTo(45000L)
            assertThat(cursor.getInt(3)).isEqualTo(20000)
            assertThat(cursor.getString(4)).isEqualTo("cat-grocery")
        }

        db.query(
            "SELECT id, entry_id, total_minor, category_id FROM line_item ORDER BY id",
        ).use { cursor ->
            assertThat(cursor.count).isEqualTo(2)
            assertThat(cursor.moveToNext()).isTrue()
            assertThat(cursor.getString(0)).isEqualTo("li-1")
            assertThat(cursor.getLong(2)).isEqualTo(60000L)
            assertThat(cursor.getString(3)).isEqualTo("cat-grocery")
            assertThat(cursor.moveToNext()).isTrue()
            assertThat(cursor.getString(0)).isEqualTo("li-2")
            assertThat(cursor.getLong(2)).isEqualTo(40000L)
            assertThat(cursor.getString(3)).isEqualTo("cat-home")
        }
    }

    /**
     * Both tables arrive, and they arrive **empty**.
     *
     * No backfill of `daily_rollup` runs here on purpose (`MIGRATION_8_9`): an
     * empty rollup table is a cold cache, not a wrong one, and ADR-0006's
     * reconciliation pass is the single routine that fills it. A backfill would
     * be a second implementation of the one thing that ADR exists to keep
     * singular.
     */
    @Test
    fun migrate_createsBothTablesEmpty() {
        seedV8()

        val db = helper.runMigrationsAndValidate(TEST_DB, V9, true, MIGRATION_8_9)

        db.query("SELECT count(*) FROM budget").use { cursor ->
            assertThat(cursor.moveToNext()).isTrue()
            assertThat(cursor.getInt(0)).isEqualTo(0)
        }
        db.query("SELECT count(*) FROM daily_rollup").use { cursor ->
            assertThat(cursor.moveToNext()).isTrue()
            assertThat(cursor.getInt(0)).isEqualTo(0)
        }
    }

    /**
     * §6.1's defaults are real defaults, not documentation.
     *
     * A budget written with only its required columns comes back with rollover
     * off and §5.7's `80,100` thresholds. If these lived only in the Kotlin
     * data class, a row inserted by a migration or by a restore that predates
     * the field would come back with an empty threshold string and alert at
     * nothing.
     */
    @Test
    fun migrate_budgetCarriesTheSpecifiedDefaults() {
        seedV8()

        val db = helper.runMigrationsAndValidate(TEST_DB, V9, true, MIGRATION_8_9)

        db.execSQL(
            "INSERT INTO budget (id, category_id, subcategory_id, period, amount_minor, " +
                "start_date) VALUES ('b-1', 'cat-grocery', NULL, 'MONTHLY', 1500000, 20000)",
        )

        db.query(
            "SELECT rollover_enabled, alert_thresholds, deleted_at FROM budget WHERE id = 'b-1'",
        ).use { cursor ->
            assertThat(cursor.moveToNext()).isTrue()
            assertThat(cursor.getInt(0)).isEqualTo(0)
            assertThat(cursor.getString(1)).isEqualTo("80,100")
            assertThat(cursor.isNull(2)).isTrue()
        }
    }

    /**
     * **The `''` sentinel does what §6.1.1 says, and `NULL` would not.**
     *
     * This is the assertion worth having, because the failure it describes is
     * silent. Two rollup rows for the same day and book that differ only in a
     * dimension neither of them has must be **one** row. With `''` they collide
     * on the primary key and the second write replaces the first; with `NULL`
     * SQLite treats the keys as distinct, both rows survive, and every total
     * built from them is double.
     *
     * Written as an `INSERT OR REPLACE` of the same logical bucket twice: one
     * row, holding the second write's figures.
     */
    @Test
    fun migrate_rollupSentinelMergesABucketRatherThanFanningItOut() {
        seedV8()

        val db = helper.runMigrationsAndValidate(TEST_DB, V9, true, MIGRATION_8_9)

        val insert = "INSERT OR REPLACE INTO daily_rollup (local_date, ledger, category_id, " +
            "subcategory_id, merchant_id, payment_method_id, sum_minor, txn_count) VALUES "
        db.execSQL("$insert (20000, 'DEBIT', 'cat-grocery', '', '', '', 45000, 1)")
        db.execSQL("$insert (20000, 'DEBIT', 'cat-grocery', '', '', '', 105000, 2)")
        // A different category on the same day is a different bucket, and the
        // other book is a different bucket again (Law 2).
        db.execSQL("$insert (20000, 'DEBIT', 'cat-home', '', '', '', 40000, 1)")
        db.execSQL("$insert (20000, 'CREDIT', '', '', '', '', 500000, 1)")

        db.query(
            "SELECT ledger, category_id, sum_minor, txn_count FROM daily_rollup " +
                "ORDER BY ledger, category_id",
        ).use { cursor ->
            assertThat(cursor.count).isEqualTo(3)

            assertThat(cursor.moveToNext()).isTrue()
            assertThat(cursor.getString(0)).isEqualTo("CREDIT")
            assertThat(cursor.getString(1)).isEqualTo("")
            assertThat(cursor.getLong(2)).isEqualTo(500000L)

            assertThat(cursor.moveToNext()).isTrue()
            assertThat(cursor.getString(0)).isEqualTo("DEBIT")
            assertThat(cursor.getString(1)).isEqualTo("cat-grocery")
            // The second write replaced the first rather than joining it.
            assertThat(cursor.getLong(2)).isEqualTo(105000L)
            assertThat(cursor.getInt(3)).isEqualTo(2)

            assertThat(cursor.moveToNext()).isTrue()
            assertThat(cursor.getString(0)).isEqualTo("DEBIT")
            assertThat(cursor.getString(1)).isEqualTo("cat-home")
            assertThat(cursor.getLong(2)).isEqualTo(40000L)
        }
    }

    /**
     * The ledger-leading index exists on `daily_rollup`.
     *
     * `runMigrationsAndValidate` compares indices too, so this is belt and
     * braces — but it is the index §11's 5Y < 300 ms budget depends on and
     * ADR-0002 requires of any index over a partitioned read, and naming it in
     * a test means a future migration that rebuilds this table and forgets to
     * recreate it fails here with the reason attached rather than as a schema
     * mismatch at launch.
     */
    @Test
    fun migrate_rollupIsIndexedByLedgerFirst() {
        seedV8()

        val db = helper.runMigrationsAndValidate(TEST_DB, V9, true, MIGRATION_8_9)

        db.query(
            "SELECT name FROM sqlite_master WHERE type = 'index' AND tbl_name = 'daily_rollup'",
        ).use { cursor ->
            val names = buildList {
                while (cursor.moveToNext()) add(cursor.getString(0))
            }
            assertThat(names).contains("index_daily_rollup_ledger_local_date")
        }
    }

    /**
     * No foreign-key violation after the migration (CLAUDE.md §7).
     *
     * Neither new table declares a foreign key — `budget.category_id` is
     * unkeyed for the reason `ledger_entry.category_id` is (ADR-0016), so that
     * taxonomy deletion stays governed by the reassign-or-block rule in code
     * rather than by a `SET NULL` that would silently pre-empt it. The check
     * is over the whole database regardless, which is the point: it is the
     * assertion that this migration did not disturb anyone else's references.
     */
    @Test
    fun migrate_leavesNoForeignKeyViolation() {
        seedV8()

        val db = helper.runMigrationsAndValidate(TEST_DB, V9, true, MIGRATION_8_9)

        db.query("PRAGMA foreign_key_check").use { cursor ->
            assertThat(cursor.count).isEqualTo(0)
        }
    }

    /**
     * An empty v8 database migrates too — the fresh install that has never
     * committed an entry.
     */
    @Test
    fun migrate_onAnEmptyLedger_succeeds() {
        helper.createDatabase(TEST_DB, V8).close()

        val db = helper.runMigrationsAndValidate(TEST_DB, V9, true, MIGRATION_8_9)

        db.query("SELECT count(*) FROM daily_rollup").use { cursor ->
            assertThat(cursor.moveToNext()).isTrue()
            assertThat(cursor.getInt(0)).isEqualTo(0)
        }
    }
}
