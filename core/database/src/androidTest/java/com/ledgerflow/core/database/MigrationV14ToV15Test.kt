package com.ledgerflow.core.database

import androidx.room.testing.MigrationTestHelper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.truth.Truth.assertThat
import com.ledgerflow.core.database.migration.MIGRATION_14_15
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * v14 → v15: savings goals (V2-PLAN §4.1).
 *
 * Purely additive, so the assertions are that every v14 row is untouched, the
 * two new tables start empty and take rows, and a goal's contributions go
 * with it.
 */
@RunWith(AndroidJUnit4::class)
class MigrationV14ToV15Test {

    private companion object {
        const val TEST_DB = "migration-v14-v15-test.db"
        const val V14 = 14
        const val V15 = 15

        val PASSPHRASE = ByteArray(32) { (it + 89).toByte() }

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

    private fun seedV14() {
        helper.createDatabase(TEST_DB, V14).use { db ->
            db.execSQL(
                "INSERT INTO category (id, parent_id, parent_key, ledger_scope, name, icon, color_argb, " +
                    "sort_order, is_system, deleted_at) VALUES " +
                    "('cat-savings', NULL, '', 'DEBIT', 'Savings', 'piggy', 255, 0, 0, 0)",
            )
            db.execSQL(
                "INSERT INTO ledger_entry (id, ledger, amount_minor, currency, occurred_at, local_date, " +
                    "category_id, source, created_at, updated_at) VALUES " +
                    "('e-1', 'DEBIT', 500000, 'INR', 1000, 20000, 'cat-savings', 'MANUAL', 1000, 1000)",
            )
        }
    }

    @Test
    fun migrate_keepsEveryV14Row_andAddsEmptyGoalTables() {
        seedV14()

        val db = helper.runMigrationsAndValidate(TEST_DB, V15, true, MIGRATION_14_15)

        db.query("SELECT id, amount_minor, category_id FROM ledger_entry").use { cursor ->
            assertThat(cursor.count).isEqualTo(1)
            cursor.moveToFirst()
            assertThat(cursor.getString(0)).isEqualTo("e-1")
            assertThat(cursor.getLong(1)).isEqualTo(500_000L)
            assertThat(cursor.getString(2)).isEqualTo("cat-savings")
        }
        db.query("SELECT COUNT(*) FROM category").use { cursor ->
            cursor.moveToFirst()
            assertThat(cursor.getInt(0)).isEqualTo(1)
        }
        listOf("savings_goal", "goal_contribution").forEach { table ->
            db.query("SELECT COUNT(*) FROM $table").use { cursor ->
                cursor.moveToFirst()
                assertThat(cursor.getInt(0)).isEqualTo(0)
            }
        }
        db.query("PRAGMA foreign_key_check").use { cursor ->
            assertThat(cursor.count).isEqualTo(0)
        }
    }

    /** A goal's hand-added amounts cascade from it; nothing else is touched. */
    @Test
    fun migrate_givesContributionsACascadeFromTheirGoal() {
        seedV14()

        val db = helper.runMigrationsAndValidate(TEST_DB, V15, true, MIGRATION_14_15)
        db.execSQL("PRAGMA foreign_keys = ON")
        db.execSQL(
            "INSERT INTO savings_goal (id, name, target_minor, target_date, linked_category_id, count_from, " +
                "sort_order, created_at, updated_at) VALUES " +
                "('g-1', 'Emergency fund', 10000000, NULL, 'cat-savings', 20000, 0, 1000, 1000)",
        )
        db.execSQL(
            "INSERT INTO goal_contribution (id, goal_id, amount_minor, local_date, note, created_at, updated_at) " +
                "VALUES ('c-1', 'g-1', 1500000, 20001, 'Bonus', 1000, 1000), " +
                "('c-2', 'g-1', -200000, 20002, NULL, 1000, 1000)",
        )

        db.execSQL("DELETE FROM savings_goal WHERE id = 'g-1'")

        db.query("SELECT COUNT(*) FROM goal_contribution").use { cursor ->
            cursor.moveToFirst()
            assertThat(cursor.getInt(0)).isEqualTo(0)
        }
        db.query("SELECT COUNT(*) FROM ledger_entry").use { cursor ->
            cursor.moveToFirst()
            assertThat(cursor.getInt(0)).isEqualTo(1)
        }
    }
}
