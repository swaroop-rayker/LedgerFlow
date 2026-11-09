package com.ledgerflow.core.database

import androidx.room.testing.MigrationTestHelper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.truth.Truth.assertThat
import com.ledgerflow.core.database.migration.MIGRATION_13_14
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * v13 → v14: `attachment.removed_from_phone_at` (V2-5, ADR-0035).
 *
 * A rebuild of a table that holds the user's receipts, so the assertion is
 * that every row comes through with every column, still pointing at its entry,
 * and every one reads as on the phone.
 */
@RunWith(AndroidJUnit4::class)
class MigrationV13ToV14Test {

    private companion object {
        const val TEST_DB = "migration-v13-v14-test.db"
        const val V13 = 13
        const val V14 = 14

        val PASSPHRASE = ByteArray(32) { (it + 83).toByte() }

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

    private fun entry(id: String) =
        "INSERT INTO ledger_entry (id, ledger, amount_minor, currency, occurred_at, local_date, " +
            "source, created_at, updated_at) VALUES ('$id', 'DEBIT', 124050, 'INR', 1000, 20000, " +
            "'OCR', 1000, 1000)"

    private fun attachment(id: String, entryId: String?, bytes: Long, createdAt: Long) =
        "INSERT INTO attachment (id, entry_id, file_path, mime, sha256, bytes, created_at) VALUES " +
            "('$id', ${entryId?.let { "'$it'" } ?: "NULL"}, '$id.lfa', 'image/png', 'hash-$id', $bytes, $createdAt)"

    private fun seedV13() {
        helper.createDatabase(TEST_DB, V13).use { db ->
            db.execSQL(entry("e-1"))
            db.execSQL(attachment("a-filed", "e-1", 412_000L, 5_000L))
            // An image from a scan never approved: no entry.
            db.execSQL(attachment("a-loose", null, 268_000L, 6_000L))
        }
    }

    @Test
    fun migrate_keepsEveryReceipt_withEveryColumn_andAllOnThePhone() {
        seedV13()

        val db = helper.runMigrationsAndValidate(TEST_DB, V14, true, MIGRATION_13_14)

        db.query(
            "SELECT id, entry_id, file_path, mime, sha256, bytes, created_at, removed_from_phone_at " +
                "FROM attachment ORDER BY id",
        ).use { cursor ->
            assertThat(cursor.count).isEqualTo(2)

            assertThat(cursor.moveToNext()).isTrue()
            assertThat(cursor.getString(0)).isEqualTo("a-filed")
            assertThat(cursor.getString(1)).isEqualTo("e-1")
            assertThat(cursor.getString(2)).isEqualTo("a-filed.lfa")
            assertThat(cursor.getString(3)).isEqualTo("image/png")
            assertThat(cursor.getString(4)).isEqualTo("hash-a-filed")
            assertThat(cursor.getLong(5)).isEqualTo(412_000L)
            assertThat(cursor.getLong(6)).isEqualTo(5_000L)
            assertThat(cursor.isNull(7)).isTrue()

            assertThat(cursor.moveToNext()).isTrue()
            assertThat(cursor.getString(0)).isEqualTo("a-loose")
            assertThat(cursor.isNull(1)).isTrue()
            assertThat(cursor.isNull(7)).isTrue()
        }
    }

    /** CLAUDE.md §7: `PRAGMA foreign_key_check` after every migration. */
    @Test
    fun migrate_leavesNoForeignKeyViolation() {
        seedV13()

        val db = helper.runMigrationsAndValidate(TEST_DB, V14, true, MIGRATION_13_14)

        db.query("PRAGMA foreign_key_check").use { cursor ->
            assertThat(cursor.count).isEqualTo(0)
        }
    }

    /** The rebuilt table still cascades from its entry, which the purge relies on (CLAUDE.md §7). */
    @Test
    fun migrate_keepsTheCascadeFromTheEntry() {
        seedV13()

        val db = helper.runMigrationsAndValidate(TEST_DB, V14, true, MIGRATION_13_14)
        db.execSQL("PRAGMA foreign_keys = ON")
        db.execSQL("DELETE FROM ledger_entry WHERE id = 'e-1'")

        db.query("SELECT id FROM attachment").use { cursor ->
            assertThat(cursor.count).isEqualTo(1)
            cursor.moveToFirst()
            assertThat(cursor.getString(0)).isEqualTo("a-loose")
        }
    }

    @Test
    fun migrate_withNoReceipts_succeeds() {
        helper.createDatabase(TEST_DB, V13).close()

        val db = helper.runMigrationsAndValidate(TEST_DB, V14, true, MIGRATION_13_14)

        db.query("SELECT COUNT(*) FROM attachment").use { cursor ->
            cursor.moveToFirst()
            assertThat(cursor.getInt(0)).isEqualTo(0)
        }
    }
}
