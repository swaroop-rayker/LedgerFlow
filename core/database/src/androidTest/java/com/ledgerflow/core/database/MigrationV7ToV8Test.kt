package com.ledgerflow.core.database

import androidx.room.testing.MigrationTestHelper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.truth.Truth.assertThat
import com.ledgerflow.core.database.migration.MIGRATION_7_8
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * v7 -> v8, the BUG8 gate for `pending_transaction.review_draft_json`.
 *
 * A one-column change to a table that holds **the approval queue** — rows the
 * user has not decided on yet, and rows carrying the audit trail from a
 * `ledger_entry` back to the message that produced it. That is the most
 * expensive table in the ingest half to get a rebuild wrong on, so the
 * assertions are about what came through rather than about the column that was
 * added.
 *
 * Three rows are seeded on purpose, because they fail differently:
 *
 * - a plain `PENDING` candidate, which is what the Inbox is full of;
 * - a **suppressed** one, whose `suppressed_by_id` is the only thing making it
 *   visible under §3.1's "Suppressed" filter rather than indistinguishable from
 *   a message that was dropped;
 * - an **approved** one, whose `approved_entry_id` is the idempotency guard's
 *   other half and the only link from a committed entry back to its message.
 *   Blank that and a second approval writes a duplicate ledger row.
 *
 * A positional `INSERT ... SELECT` into a table with one more column, or a
 * column dropped from the projection, silently shifts or nulls exactly these.
 *
 * **And Room cannot see that happen**, which is the whole reason these
 * assertions are written out rather than left to `runMigrationsAndValidate`.
 * Measured, not assumed: blanking `approved_entry_id` in the projection and
 * changing nothing else fails **only** [migrate_preservesEveryCandidateAndDefaultsTheDraftToNull]
 * — the other three tests pass, because the resulting schema is byte-identical
 * to the one Room expects. Validation compares shape; it has no opinion about
 * whether the data arrived.
 */
@RunWith(AndroidJUnit4::class)
class MigrationV7ToV8Test {

    private companion object {
        const val TEST_DB = "migration-v7-v8-test.db"
        const val V7 = 7
        const val V8 = 8

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

    private fun seedV7() {
        helper.createDatabase(TEST_DB, V7).use { db ->
            // The ordinary case: waiting for the user.
            db.execSQL(
                "INSERT INTO pending_transaction (id, source, dedupe_key, suppressed_by_id, " +
                    "raw_ref_id, extracted_json, confidence, status, needs_manual_fill, " +
                    "created_at, reviewed_at, approved_entry_id) VALUES " +
                    "('p-pending', 'SMS', '6900|DEBIT', NULL, 'raw-1', " +
                    "'{\"version\":1,\"amountMinor\":6900}', 0.9, 'PENDING', 0, 1000, " +
                    "NULL, NULL)",
            )
            // §3.1's retained duplicate. suppressed_by_id is what makes it visible.
            db.execSQL(
                "INSERT INTO pending_transaction (id, source, dedupe_key, suppressed_by_id, " +
                    "raw_ref_id, extracted_json, confidence, status, needs_manual_fill, " +
                    "created_at, reviewed_at, approved_entry_id) VALUES " +
                    "('p-suppressed', 'NOTIFICATION', '6900|DEBIT', 'p-pending', 'raw-2', " +
                    "'{\"version\":1,\"amountMinor\":6900}', 0.45, 'PENDING', 0, 1001, " +
                    "NULL, NULL)",
            )
            // The audit trail. approved_entry_id is the idempotency guard's other half.
            db.execSQL(
                "INSERT INTO pending_transaction (id, source, dedupe_key, suppressed_by_id, " +
                    "raw_ref_id, extracted_json, confidence, status, needs_manual_fill, " +
                    "created_at, reviewed_at, approved_entry_id) VALUES " +
                    "('p-approved', 'SMS', '50000|CREDIT', NULL, 'raw-3', " +
                    "'{\"version\":1,\"amountMinor\":50000}', 0.9, 'APPROVED', 0, 1002, " +
                    "2000, 'entry-abc')",
            )
        }
    }

    /**
     * **The assertion.** All three rows survive with every column intact, and
     * the new one is null on each.
     *
     * Null is the honest value for an upgrade: nobody has typed into a review
     * screen that could not save it. A back-fill of any kind here would be
     * inventing user input.
     */
    @Test
    fun migrate_preservesEveryCandidateAndDefaultsTheDraftToNull() {
        seedV7()

        val db = helper.runMigrationsAndValidate(TEST_DB, V8, true, MIGRATION_7_8)

        db.query(
            "SELECT id, source, dedupe_key, suppressed_by_id, raw_ref_id, extracted_json, " +
                "confidence, status, needs_manual_fill, created_at, reviewed_at, " +
                "approved_entry_id, review_draft_json FROM pending_transaction ORDER BY id",
        ).use { cursor ->
            assertThat(cursor.count).isEqualTo(3)

            assertThat(cursor.moveToNext()).isTrue()
            assertThat(cursor.getString(0)).isEqualTo("p-approved")
            assertThat(cursor.getString(1)).isEqualTo("SMS")
            assertThat(cursor.getString(2)).isEqualTo("50000|CREDIT")
            assertThat(cursor.isNull(3)).isTrue()
            assertThat(cursor.getString(4)).isEqualTo("raw-3")
            assertThat(cursor.getDouble(6)).isEqualTo(0.9)
            assertThat(cursor.getString(7)).isEqualTo("APPROVED")
            assertThat(cursor.getLong(10)).isEqualTo(2000L)
            // The link back to the ledger entry. Losing this is losing the only
            // record of how that entry got there.
            assertThat(cursor.getString(11)).isEqualTo("entry-abc")
            assertThat(cursor.isNull(12)).isTrue()

            assertThat(cursor.moveToNext()).isTrue()
            assertThat(cursor.getString(0)).isEqualTo("p-pending")
            assertThat(cursor.getString(7)).isEqualTo("PENDING")
            assertThat(cursor.isNull(11)).isTrue()
            assertThat(cursor.isNull(12)).isTrue()

            assertThat(cursor.moveToNext()).isTrue()
            assertThat(cursor.getString(0)).isEqualTo("p-suppressed")
            assertThat(cursor.getString(1)).isEqualTo("NOTIFICATION")
            // Without this the row is indistinguishable from a dropped message.
            assertThat(cursor.getString(3)).isEqualTo("p-pending")
            assertThat(cursor.getDouble(6)).isEqualTo(0.45)
            assertThat(cursor.isNull(12)).isTrue()
        }
    }

    /**
     * The three indexes come back.
     *
     * `DROP TABLE` takes its indexes with it. Room's validation **does** catch a
     * missing one — verified by deleting the dedupe index from the migration,
     * which fails every test in this class with
     * `"Migration didn't properly handle: pending_transaction"`. So this test is
     * not the only thing standing between us and a table scan.
     *
     * It is kept because that message names a table and nothing else. This names
     * the index, which is the difference between "something about
     * `pending_transaction` is wrong" and "you forgot the dedupe index on the
     * rebuild" — and §3.1's ±3-minute window is the query that would quietly
     * become a full scan.
     */
    @Test
    fun migrate_recreatesEveryIndexTheRebuildDropped() {
        seedV7()

        val db = helper.runMigrationsAndValidate(TEST_DB, V8, true, MIGRATION_7_8)

        val names = mutableSetOf<String>()
        db.query(
            "SELECT name FROM sqlite_master WHERE type = 'index' " +
                "AND tbl_name = 'pending_transaction'",
        ).use { cursor ->
            while (cursor.moveToNext()) names += cursor.getString(0)
        }

        assertThat(names).containsAtLeast(
            "index_pending_transaction_status_created_at",
            "index_pending_transaction_dedupe_key_created_at",
            "index_pending_transaction_suppressed_by_id",
        )
    }

    /**
     * The rebuild leaves no foreign-key violation behind (CLAUDE.md §7).
     *
     * `pending_transaction` declares none of its own — `suppressed_by_id` and
     * `approved_entry_id` are deliberately unconstrained so a purged winner or
     * an erased entry cannot cascade away the evidence — but the check is over
     * the whole database, and a rebuild that renamed a table out from under
     * someone else's reference would show up here and nowhere else.
     */
    @Test
    fun migrate_leavesNoForeignKeyViolation() {
        seedV7()

        val db = helper.runMigrationsAndValidate(TEST_DB, V8, true, MIGRATION_7_8)

        db.query("PRAGMA foreign_key_check").use { cursor ->
            assertThat(cursor.count).isEqualTo(0)
        }
    }

    /**
     * An empty v7 database migrates too.
     *
     * The install that has never received a message is the common one, and an
     * `INSERT ... SELECT` over zero rows is exactly where a mistyped column list
     * still throws.
     */
    @Test
    fun migrate_onAnEmptyQueue_succeeds() {
        helper.createDatabase(TEST_DB, V7).close()

        val db = helper.runMigrationsAndValidate(TEST_DB, V8, true, MIGRATION_7_8)

        db.query("SELECT count(*) FROM pending_transaction").use { cursor ->
            assertThat(cursor.moveToNext()).isTrue()
            assertThat(cursor.getInt(0)).isEqualTo(0)
        }
    }
}
