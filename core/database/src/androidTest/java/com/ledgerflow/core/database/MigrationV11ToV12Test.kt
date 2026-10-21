package com.ledgerflow.core.database

import androidx.room.testing.MigrationTestHelper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.truth.Truth.assertThat
import com.ledgerflow.core.database.migration.MIGRATION_11_12
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * v11 -> v12, the BUG8 gate for `pending_transaction.non_transaction_kind`
 * (BUG58, V2-PLAN D6).
 *
 * The same table `MigrationV7ToV8Test` rebuilt, rebuilt again for one more
 * column, so the same three rows are seeded for the same reasons: a plain
 * `PENDING` candidate, a **suppressed** one whose `suppressed_by_id` is all that
 * makes it visible, and an **approved** one whose `approved_entry_id` is the
 * only link from a committed entry back to its message. A fourth carries
 * `review_draft_json`, v8's column, which is the newest thing a positional
 * projection could drop.
 *
 * Room's validation compares shape and has no opinion about whether the data
 * arrived, which is why the values are asserted one by one.
 */
@RunWith(AndroidJUnit4::class)
class MigrationV11ToV12Test {

    private companion object {
        const val TEST_DB = "migration-v11-v12-test.db"
        const val V11 = 11
        const val V12 = 12

        val PASSPHRASE = ByteArray(32) { (it + 67).toByte() }

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

    private fun insert(
        id: String,
        suppressedBy: String?,
        status: String,
        reviewedAt: String,
        approvedEntryId: String?,
        draft: String?,
    ) = "INSERT INTO pending_transaction (id, source, dedupe_key, suppressed_by_id, " +
        "raw_ref_id, extracted_json, confidence, status, needs_manual_fill, " +
        "created_at, reviewed_at, approved_entry_id, review_draft_json) VALUES " +
        "('$id', 'SMS', '6900|DEBIT', ${suppressedBy.sql()}, 'raw-$id', " +
        "'{\"version\":1,\"amountMinor\":6900}', 0.9, '$status', 0, 1000, " +
        "$reviewedAt, ${approvedEntryId.sql()}, ${draft.sql()})"

    private fun String?.sql() = this?.let { "'$it'" } ?: "NULL"

    private fun seedV11() {
        helper.createDatabase(TEST_DB, V11).use { db ->
            db.execSQL(insert("p-pending", null, "PENDING", "NULL", null, null))
            db.execSQL(insert("p-suppressed", "p-pending", "PENDING", "NULL", null, null))
            db.execSQL(insert("p-approved", null, "APPROVED", "2000", "entry-abc", null))
            db.execSQL(insert("p-drafted", null, "PENDING", "NULL", null, "{\"v\":1}"))
        }
    }

    /**
     * **The assertion.** Every row survives with its columns intact, and the
     * new one is null on each: nothing was ever classified before v12, and a
     * back-fill here would be moving rows out of the user's queue on upgrade.
     */
    @Test
    fun migrate_preservesEveryCandidateAndLeavesNothingLabelled() {
        seedV11()

        val db = helper.runMigrationsAndValidate(TEST_DB, V12, true, MIGRATION_11_12)

        db.query(
            "SELECT id, suppressed_by_id, status, reviewed_at, approved_entry_id, " +
                "review_draft_json, non_transaction_kind, confidence " +
                "FROM pending_transaction ORDER BY id",
        ).use { cursor ->
            assertThat(cursor.count).isEqualTo(4)

            assertThat(cursor.moveToNext()).isTrue()
            assertThat(cursor.getString(0)).isEqualTo("p-approved")
            assertThat(cursor.getString(2)).isEqualTo("APPROVED")
            assertThat(cursor.getLong(3)).isEqualTo(2000L)
            assertThat(cursor.getString(4)).isEqualTo("entry-abc")
            assertThat(cursor.isNull(6)).isTrue()

            assertThat(cursor.moveToNext()).isTrue()
            assertThat(cursor.getString(0)).isEqualTo("p-drafted")
            assertThat(cursor.getString(5)).isEqualTo("{\"v\":1}")
            assertThat(cursor.isNull(6)).isTrue()

            assertThat(cursor.moveToNext()).isTrue()
            assertThat(cursor.getString(0)).isEqualTo("p-pending")
            assertThat(cursor.getString(2)).isEqualTo("PENDING")
            assertThat(cursor.isNull(6)).isTrue()
            assertThat(cursor.getDouble(7)).isEqualTo(0.9)

            assertThat(cursor.moveToNext()).isTrue()
            assertThat(cursor.getString(0)).isEqualTo("p-suppressed")
            assertThat(cursor.getString(1)).isEqualTo("p-pending")
            assertThat(cursor.isNull(6)).isTrue()
        }
    }

    /** `DROP TABLE` takes the indexes with it; the rebuild must put all three back. */
    @Test
    fun migrate_recreatesEveryIndexTheRebuildDropped() {
        seedV11()

        val db = helper.runMigrationsAndValidate(TEST_DB, V12, true, MIGRATION_11_12)

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

    /** CLAUDE.md §7: `PRAGMA foreign_key_check` after every migration. */
    @Test
    fun migrate_leavesNoForeignKeyViolation() {
        seedV11()

        val db = helper.runMigrationsAndValidate(TEST_DB, V12, true, MIGRATION_11_12)

        db.query("PRAGMA foreign_key_check").use { cursor ->
            assertThat(cursor.count).isEqualTo(0)
        }
    }

    /** The install that never received a message; a mistyped column list still throws here. */
    @Test
    fun migrate_onAnEmptyQueue_succeeds() {
        helper.createDatabase(TEST_DB, V11).close()

        val db = helper.runMigrationsAndValidate(TEST_DB, V12, true, MIGRATION_11_12)

        db.query("SELECT count(*) FROM pending_transaction").use { cursor ->
            assertThat(cursor.moveToNext()).isTrue()
            assertThat(cursor.getInt(0)).isEqualTo(0)
        }
    }
}
