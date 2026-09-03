package com.ledgerflow.core.database

import androidx.room.testing.MigrationTestHelper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.truth.Truth.assertThat
import com.ledgerflow.core.database.migration.MIGRATION_9_10
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * v9 -> v10, the BUG8 gate for `budget`'s alert memory.
 *
 * **This one rebuilds a table**, unlike `MIGRATION_8_9` which only created new
 * ones — so the assertions are back to the shape `MigrationV7ToV8Test` uses:
 * about what came through the `INSERT ... SELECT`, not about the columns added.
 *
 * `budget` is the one P3 table that is **user intent nothing can reconstruct**
 * (ADR-0006). A rollup can be rebuilt from the ledger in a second; a budget the
 * user set is gone. That makes a mistyped projection here more expensive than
 * anywhere else in this schema, and Room cannot see it happen —
 * `runMigrationsAndValidate` compares *shape*, and a rebuild that blanked
 * `amount_minor` produces a schema byte-identical to the one Room expects.
 *
 * Three rows, because they fail differently: a plain monthly budget; one with
 * every optional column at a non-default value (subcategory, rollover on,
 * custom thresholds), which is where a positional projection shifts columns;
 * and a soft-deleted one, because ADR-0017 puts the bin in the backup and a
 * rebuild that dropped `deleted_at` would silently resurrect it.
 */
@RunWith(AndroidJUnit4::class)
class MigrationV9ToV10Test {

    private companion object {
        const val TEST_DB = "migration-v9-v10-test.db"
        const val V9 = 9
        const val V10 = 10

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

    private fun seedV9() {
        helper.createDatabase(TEST_DB, V9).use { db ->
            db.execSQL(
                "INSERT INTO budget (id, category_id, subcategory_id, period, " +
                    "amount_minor, start_date, rollover_enabled, alert_thresholds, " +
                    "deleted_at) VALUES " +
                    "('b-plain', 'cat-grocery', NULL, 'MONTHLY', 1200000, 20000, 0, " +
                    "'80,100', NULL)",
            )
            // Every optional column off its default -- the row a positional
            // INSERT...SELECT shifts or blanks without any error.
            db.execSQL(
                "INSERT INTO budget (id, category_id, subcategory_id, period, " +
                    "amount_minor, start_date, rollover_enabled, alert_thresholds, " +
                    "deleted_at) VALUES " +
                    "('b-detailed', 'cat-food', 'cat-coffee', 'WEEKLY', 50000, 20010, " +
                    "1, '50,90,100', NULL)",
            )
            // Binned. ADR-0017 carries the bin into the backup.
            db.execSQL(
                "INSERT INTO budget (id, category_id, subcategory_id, period, " +
                    "amount_minor, start_date, rollover_enabled, alert_thresholds, " +
                    "deleted_at) VALUES " +
                    "('b-binned', 'cat-home', NULL, 'YEARLY', 9000000, 19000, 0, " +
                    "'80,100', 1700000000500)",
            )
        }
    }

    /**
     * **The assertion.** Every budget survives with every column intact, and
     * the two new ones are 0 on each.
     *
     * Zero is the honest value for an upgrade: no budget that predates alerting
     * has had anything announced. A back-fill of any kind would be inventing a
     * notification history.
     */
    @Test
    fun migrate_preservesEveryBudgetAndDefaultsTheAlertStateToZero() {
        seedV9()

        val db = helper.runMigrationsAndValidate(TEST_DB, V10, true, MIGRATION_9_10)

        db.query(
            "SELECT id, category_id, subcategory_id, period, amount_minor, start_date, " +
                "rollover_enabled, alert_thresholds, deleted_at, last_alerted_threshold, " +
                "alert_period_start FROM budget ORDER BY id",
        ).use { cursor ->
            assertThat(cursor.count).isEqualTo(3)

            assertThat(cursor.moveToNext()).isTrue()
            assertThat(cursor.getString(0)).isEqualTo("b-binned")
            assertThat(cursor.getLong(8)).isEqualTo(1_700_000_000_500L)
            assertThat(cursor.getInt(9)).isEqualTo(0)
            assertThat(cursor.getInt(10)).isEqualTo(0)

            assertThat(cursor.moveToNext()).isTrue()
            assertThat(cursor.getString(0)).isEqualTo("b-detailed")
            assertThat(cursor.getString(1)).isEqualTo("cat-food")
            // The optional column most easily lost to a shifted projection.
            assertThat(cursor.getString(2)).isEqualTo("cat-coffee")
            assertThat(cursor.getString(3)).isEqualTo("WEEKLY")
            assertThat(cursor.getLong(4)).isEqualTo(50_000L)
            assertThat(cursor.getInt(5)).isEqualTo(20_010)
            assertThat(cursor.getInt(6)).isEqualTo(1)
            assertThat(cursor.getString(7)).isEqualTo("50,90,100")
            assertThat(cursor.isNull(8)).isTrue()

            assertThat(cursor.moveToNext()).isTrue()
            assertThat(cursor.getString(0)).isEqualTo("b-plain")
            assertThat(cursor.getLong(4)).isEqualTo(1_200_000L)
            assertThat(cursor.isNull(2)).isTrue()
            assertThat(cursor.isNull(8)).isTrue()
        }
    }

    /**
     * The index survives the rebuild.
     *
     * `DROP TABLE` takes its indices with it, and a migration that forgot would
     * leave a schema Room validates happily and a per-category budget lookup
     * that has quietly become a table scan.
     */
    @Test
    fun migrate_recreatesTheCategoryIndex() {
        seedV9()

        val db = helper.runMigrationsAndValidate(TEST_DB, V10, true, MIGRATION_9_10)

        db.query(
            "SELECT name FROM sqlite_master WHERE type = 'index' AND tbl_name = 'budget'",
        ).use { cursor ->
            val names = buildList { while (cursor.moveToNext()) add(cursor.getString(0)) }
            assertThat(names).contains("index_budget_category_id")
        }
    }

    @Test
    fun migrate_leavesNoForeignKeyViolation() {
        seedV9()

        val db = helper.runMigrationsAndValidate(TEST_DB, V10, true, MIGRATION_9_10)

        db.query("PRAGMA foreign_key_check").use { cursor ->
            assertThat(cursor.count).isEqualTo(0)
        }
    }

    /**
     * An empty budget table migrates too — the install that never set one.
     *
     * An `INSERT ... SELECT` over zero rows is exactly where a mistyped column
     * list still throws.
     */
    @Test
    fun migrate_withNoBudgets_succeeds() {
        helper.createDatabase(TEST_DB, V9).close()

        val db = helper.runMigrationsAndValidate(TEST_DB, V10, true, MIGRATION_9_10)

        db.query("SELECT count(*) FROM budget").use { cursor ->
            assertThat(cursor.moveToNext()).isTrue()
            assertThat(cursor.getInt(0)).isEqualTo(0)
        }
    }
}
