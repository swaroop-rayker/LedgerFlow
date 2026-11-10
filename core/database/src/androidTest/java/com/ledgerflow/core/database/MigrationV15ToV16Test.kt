package com.ledgerflow.core.database

import androidx.room.testing.MigrationTestHelper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.truth.Truth.assertThat
import com.ledgerflow.core.database.migration.MIGRATION_15_16
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * v15 → v16: recurring items (V2-PLAN §4.2).
 *
 * Purely additive, so the assertions are that every v15 row is untouched, the
 * three new tables start empty and take rows, one due date is one row, and an
 * item's log goes with it.
 */
@RunWith(AndroidJUnit4::class)
class MigrationV15ToV16Test {

    private companion object {
        const val TEST_DB = "migration-v15-v16-test.db"
        const val V15 = 15
        const val V16 = 16

        val PASSPHRASE = ByteArray(32) { (it + 97).toByte() }

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

    private fun seedV15() {
        helper.createDatabase(TEST_DB, V15).use { db ->
            db.execSQL(
                "INSERT INTO ledger_entry (id, ledger, amount_minor, currency, occurred_at, local_date, " +
                    "source, created_at, updated_at) VALUES " +
                    "('e-1', 'DEBIT', 2500000, 'INR', 1000, 20000, 'SMS', 1000, 1000)",
            )
            db.execSQL(
                "INSERT INTO savings_goal (id, name, target_minor, target_date, linked_category_id, count_from, " +
                    "sort_order, created_at, updated_at) VALUES ('g-1', 'Fund', 10000000, NULL, NULL, NULL, 0, 1, 1)",
            )
        }
    }

    private fun item(id: String) =
        "INSERT INTO recurring_item (id, name, ledger, amount_minor, merchant_id, category_id, subcategory_id, " +
            "cadence, every_days, anchor_date, next_due, created_at, updated_at) VALUES " +
            "('$id', 'Rent', 'DEBIT', 2500000, NULL, NULL, NULL, 'MONTHLY', NULL, 20000, 20030, 1, 1)"

    private fun occurrence(id: String, itemId: String, due: Int) =
        "INSERT INTO recurring_occurrence (id, item_id, due_date, outcome, pending_id, entry_id, reminded_at, " +
            "created_at, updated_at) VALUES ('$id', '$itemId', $due, 'MATCHED', NULL, 'e-1', NULL, 1, 1)"

    @Test
    fun migrate_keepsEveryV15Row_andAddsEmptyRecurringTables() {
        seedV15()

        val db = helper.runMigrationsAndValidate(TEST_DB, V16, true, MIGRATION_15_16)

        listOf("ledger_entry" to 1, "savings_goal" to 1, "recurring_item" to 0,
            "recurring_occurrence" to 0, "recurring_dismissal" to 0).forEach { (table, rows) ->
            db.query("SELECT COUNT(*) FROM $table").use { cursor ->
                cursor.moveToFirst()
                assertThat(cursor.getInt(0)).isEqualTo(rows)
            }
        }
        db.query("PRAGMA foreign_key_check").use { cursor -> assertThat(cursor.count).isEqualTo(0) }
    }

    /** The defaults are what the form's choices mean: remind the day before, add to the Inbox, running. */
    @Test
    fun migrate_givesAnItemItsDefaults() {
        seedV15()
        val db = helper.runMigrationsAndValidate(TEST_DB, V16, true, MIGRATION_15_16)

        db.execSQL(item("r-1"))

        db.query("SELECT amount_varies, remind_days_before, to_inbox, paused FROM recurring_item").use { cursor ->
            cursor.moveToFirst()
            assertThat(cursor.getInt(0)).isEqualTo(0)
            assertThat(cursor.getInt(1)).isEqualTo(1)
            assertThat(cursor.getInt(2)).isEqualTo(1)
            assertThat(cursor.getInt(3)).isEqualTo(0)
        }
    }

    /** One due date is one row; an item's log goes with it, and no entry does. */
    @Test
    fun migrate_oneRowPerDueDate_andTheLogCascades() {
        seedV15()
        val db = helper.runMigrationsAndValidate(TEST_DB, V16, true, MIGRATION_15_16)
        db.execSQL("PRAGMA foreign_keys = ON")
        db.execSQL(item("r-1"))
        db.execSQL(occurrence("o-1", "r-1", 20000))

        assertThrows(Exception::class.java) { db.execSQL(occurrence("o-2", "r-1", 20000)) }

        db.execSQL("DELETE FROM recurring_item WHERE id = 'r-1'")
        db.query("SELECT COUNT(*) FROM recurring_occurrence").use { cursor ->
            cursor.moveToFirst()
            assertThat(cursor.getInt(0)).isEqualTo(0)
        }
        db.query("SELECT COUNT(*) FROM ledger_entry").use { cursor ->
            cursor.moveToFirst()
            assertThat(cursor.getInt(0)).isEqualTo(1)
        }
    }
}
