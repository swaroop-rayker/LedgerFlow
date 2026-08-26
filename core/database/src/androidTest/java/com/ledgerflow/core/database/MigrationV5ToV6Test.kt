package com.ledgerflow.core.database

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteConstraintException
import androidx.room.testing.MigrationTestHelper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.truth.Truth.assertThat
import com.ledgerflow.core.database.migration.MIGRATION_5_6
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * v5 -> v6, the BUG8 gate for the ingest tables.
 *
 * This migration is additive — six new tables, nothing existing rebuilt — so
 * the assertions split in two, and both matter for different reasons.
 *
 * **The v5 data has to still be there.** An additive migration is exactly the
 * one people assume cannot lose anything, which is why it gets the same
 * seed-and-verify treatment as the rebuilds before it. A stray `DROP` or a
 * table name that collided with an existing one would show up here and nowhere
 * else until a user's ledger came back empty.
 *
 * **The new tables have to match what Room expects.** `runMigrationsAndValidate`
 * does most of that for us — it compares the migrated database against
 * `schemas/6.json` and throws on any difference in column type, nullability,
 * default or index. What it does not check is behaviour, so the constraints
 * this schema leans on (the unique body hash that makes double delivery a
 * database-level no-op) are exercised directly.
 */
@RunWith(AndroidJUnit4::class)
class MigrationV5ToV6Test {

    private companion object {
        const val TEST_DB = "migration-v5-v6-test.db"
        const val V5 = 5
        const val V6 = 6

        val PASSPHRASE = ByteArray(32) { (it + 29).toByte() }

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

    private fun entry(id: String, ledger: String, amountMinor: Long) = ContentValues().apply {
        put("id", id)
        put("ledger", ledger)
        put("amount_minor", amountMinor)
        put("currency", "INR")
        put("occurred_at", 1_700_000_000_000L)
        put("local_date", 19_700L)
        put("source", "MANUAL")
        put("is_recurring", 0)
        put("created_at", 1_700_000_000_000L)
        put("updated_at", 1_700_000_000_000L)
    }

    private fun seedV5() {
        helper.createDatabase(TEST_DB, V5).use { db ->
            db.insert("ledger_entry", SQLiteDatabase.CONFLICT_ABORT, entry("e-1", "DEBIT", 24_050L))
            db.insert("ledger_entry", SQLiteDatabase.CONFLICT_ABORT, entry("e-2", "CREDIT", 85_000_00L))
        }
    }

    /**
     * The assertion an additive migration still has to earn: the ledger the
     * user already had is untouched.
     */
    @Test
    fun migrate_leavesExistingLedgerDataAlone() {
        seedV5()

        val db = helper.runMigrationsAndValidate(TEST_DB, V6, true, MIGRATION_5_6)

        db.query("SELECT id, ledger, amount_minor FROM ledger_entry ORDER BY id").use { cursor ->
            assertThat(cursor.count).isEqualTo(2)

            cursor.moveToFirst()
            assertThat(cursor.getString(0)).isEqualTo("e-1")
            assertThat(cursor.getString(1)).isEqualTo("DEBIT")
            assertThat(cursor.getLong(2)).isEqualTo(24_050L)

            cursor.moveToNext()
            assertThat(cursor.getString(0)).isEqualTo("e-2")
            assertThat(cursor.getString(1)).isEqualTo("CREDIT")
            assertThat(cursor.getLong(2)).isEqualTo(85_000_00L)
        }
    }

    /** All six tables exist and are empty. No migration seeds them (see MIGRATION_5_6). */
    @Test
    fun migrate_createsTheIngestTablesEmpty() {
        seedV5()

        val db = helper.runMigrationsAndValidate(TEST_DB, V6, true, MIGRATION_5_6)

        listOf(
            "sms_raw",
            "notification_raw",
            "package_allowlist",
            "sender_allowlist",
            "parser_rule",
            "pending_transaction",
        ).forEach { table ->
            db.query("SELECT COUNT(*) FROM `$table`").use { cursor ->
                cursor.moveToFirst()
                assertThat(cursor.getInt(0)).isEqualTo(0)
            }
        }
    }

    /**
     * `pending_line_item` is deliberately **not** in v6 (§16 Q7).
     *
     * Asserted rather than assumed, because the natural instinct on reading
     * §6.1 is to create every table it names. Nothing at P2 can produce an
     * itemised candidate; this lands at P4 with OCR, and this test is what
     * turns that decision into something a future migration has to notice.
     */
    @Test
    fun migrate_doesNotCreatePendingLineItem() {
        seedV5()

        val db = helper.runMigrationsAndValidate(TEST_DB, V6, true, MIGRATION_5_6)

        db.query(
            "SELECT name FROM sqlite_master WHERE type = 'table' AND name = 'pending_line_item'",
        ).use { cursor ->
            assertThat(cursor.count).isEqualTo(0)
        }
    }

    /**
     * Double delivery is refused by the database, not by a caller's memory.
     *
     * The network can deliver the same SMS twice, and §5.1 dedupes on a hash of
     * sender + normalized body + minute bucket. That hash is `UNIQUE`, so the
     * second insert cannot succeed even if a future worker forgets to check.
     */
    @Test
    fun migrate_bodyHashIsUniqueOnBothRawTables() {
        seedV5()

        val db = helper.runMigrationsAndValidate(TEST_DB, V6, true, MIGRATION_5_6)

        db.execSQL(
            "INSERT INTO sms_raw (id, sender, body, body_hash, received_at, sim_slot, " +
                "parse_status, matched_rule_id, retention_expires_at) VALUES " +
                "('s-1', 'VM-HDFCBK', 'body', 'hash-a', 1, NULL, 'CAPTURED', NULL, 2)",
        )
        db.execSQL(
            "INSERT INTO notification_raw (id, package_name, title, body, body_hash, " +
                "posted_at, parse_status, matched_rule_id, retention_expires_at) VALUES " +
                "('n-1', 'com.example.pay', 'Paid', 'body', 'hash-b', 1, 'CAPTURED', NULL, 2)",
        )

        runCatching {
            db.execSQL(
                "INSERT INTO sms_raw (id, sender, body, body_hash, received_at, sim_slot, " +
                    "parse_status, matched_rule_id, retention_expires_at) VALUES " +
                    "('s-2', 'VM-HDFCBK', 'body', 'hash-a', 1, NULL, 'CAPTURED', NULL, 2)",
            )
        }.let { assertThat(it.exceptionOrNull()).isInstanceOf(SQLiteConstraintException::class.java) }

        runCatching {
            db.execSQL(
                "INSERT INTO notification_raw (id, package_name, title, body, body_hash, " +
                    "posted_at, parse_status, matched_rule_id, retention_expires_at) VALUES " +
                    "('n-2', 'com.example.pay', 'Paid', 'body', 'hash-b', 1, 'CAPTURED', NULL, 2)",
            )
        }.let { assertThat(it.exceptionOrNull()).isInstanceOf(SQLiteConstraintException::class.java) }
    }

    /**
     * `pending_transaction` carries no foreign keys, and that is a decision
     * rather than an omission.
     *
     * `approved_entry_id` points at a `ledger_entry` that `PurgeDeletedEntries`
     * can destroy, and `suppressed_by_id` points at a row that can itself be
     * discarded and purged. A cascade from either would delete the audit trail
     * saying the user approved something, or the evidence that dedupe suppressed
     * a duplicate. A dangling id reads as "that row is gone", which is true.
     */
    @Test
    fun migrate_pendingTransactionHasNoForeignKeys() {
        seedV5()

        val db = helper.runMigrationsAndValidate(TEST_DB, V6, true, MIGRATION_5_6)

        db.query("PRAGMA foreign_key_list(`pending_transaction`)").use { cursor ->
            assertThat(cursor.count).isEqualTo(0)
        }
    }

    /**
     * The chain still ends clean.
     *
     * CLAUDE.md §7 requires `PRAGMA foreign_key_check` after every migration; a
     * violation aborts. Running it here means the additive step cannot leave
     * the existing graph inconsistent through some interaction nobody predicted.
     */
    @Test
    fun migrate_leavesNoForeignKeyViolations() {
        seedV5()

        val db = helper.runMigrationsAndValidate(TEST_DB, V6, true, MIGRATION_5_6)

        db.query("PRAGMA foreign_key_check").use { cursor ->
            assertThat(cursor.count).isEqualTo(0)
        }
    }
}
