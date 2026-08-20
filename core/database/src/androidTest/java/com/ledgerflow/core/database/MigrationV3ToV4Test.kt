package com.ledgerflow.core.database

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import androidx.room.testing.MigrationTestHelper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.truth.Truth.assertThat
import com.ledgerflow.core.database.migration.MIGRATION_3_4
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * v3 -> v4, the BUG8 gate for `draft_entry`'s denormalised summary columns.
 *
 * The rows in `draft_entry` are unsaved user input — the whole reason the table
 * exists (BUG6) — and this migration **rebuilds the table**, which is the
 * riskiest thing a migration can do to them. So the assertion that matters is
 * not "the new columns exist" but **"every payload survived the rebuild
 * byte-for-byte"**, made on content rather than on a row count (SPEC.md §8).
 *
 * `CREATE new / INSERT SELECT / DROP old / RENAME` rather than three `ALTER
 * TABLE ADD COLUMN` statements, per CLAUDE.md §7: an `ALTER` chain can
 * half-apply and strand the schema between two shapes. That is also why the
 * indices are asserted — they belong to the dropped table and do not survive
 * the rename, so a migration that forgot to recreate them would leave a
 * database Room validates as wrong on the very next open.
 */
@RunWith(AndroidJUnit4::class)
class MigrationV3ToV4Test {

    private companion object {
        const val TEST_DB = "migration-v3-v4-test.db"
        const val V3 = 3
        const val V4 = 4

        val PASSPHRASE = ByteArray(32) { (it + 9).toByte() }

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

    private fun draft(id: String, ledger: String, payload: String, updatedAt: Long) =
        ContentValues().apply {
            put("id", id)
            put("ledger", ledger)
            putNull("editing_entry_id")
            put("editing_entry_key", "")
            put("payload_json", payload)
            put("payload_version", 1)
            put("created_at", 1_000L)
            put("updated_at", updatedAt)
        }

    private fun seedV3() {
        helper.createDatabase(TEST_DB, V3).use { db ->
            db.insert(
                "draft_entry",
                SQLiteDatabase.CONFLICT_ABORT,
                draft("draft-a", "DEBIT", DEBIT_PAYLOAD, 2_000L),
            )
            db.insert(
                "draft_entry",
                SQLiteDatabase.CONFLICT_ABORT,
                draft("draft-b", "DEBIT", SECOND_PAYLOAD, 3_000L),
            )
            db.insert(
                "draft_entry",
                SQLiteDatabase.CONFLICT_ABORT,
                draft("draft-c", "CREDIT", CREDIT_PAYLOAD, 4_000L),
            )
        }
    }

    /**
     * **The assertion this test exists for.**
     *
     * Every draft survives the rebuild with its payload intact. A migration
     * that dropped and recreated the table without copying, or that copied
     * positionally into a table with a different arity, fails here rather than
     * on a user's device with their half-typed entries gone.
     */
    @Test
    fun migrate_preservesEveryDraftAndItsPayload() {
        seedV3()

        val db = helper.runMigrationsAndValidate(TEST_DB, V4, true, MIGRATION_3_4)

        db.query(
            "SELECT id, ledger, payload_json, payload_version, created_at, updated_at " +
                "FROM draft_entry ORDER BY updated_at",
        ).use { cursor ->
            assertThat(cursor.count).isEqualTo(3)

            cursor.moveToFirst()
            assertThat(cursor.getString(0)).isEqualTo("draft-a")
            assertThat(cursor.getString(1)).isEqualTo("DEBIT")
            assertThat(cursor.getString(2)).isEqualTo(DEBIT_PAYLOAD)
            assertThat(cursor.getInt(3)).isEqualTo(1)
            assertThat(cursor.getLong(4)).isEqualTo(1_000L)
            assertThat(cursor.getLong(5)).isEqualTo(2_000L)

            cursor.moveToNext()
            assertThat(cursor.getString(0)).isEqualTo("draft-b")
            assertThat(cursor.getString(2)).isEqualTo(SECOND_PAYLOAD)

            cursor.moveToNext()
            assertThat(cursor.getString(0)).isEqualTo("draft-c")
            assertThat(cursor.getString(1)).isEqualTo("CREDIT")
            assertThat(cursor.getString(2)).isEqualTo(CREDIT_PAYLOAD)
        }
    }

    /**
     * Existing drafts get a zeroed summary, not a guessed one.
     *
     * The payload stays authoritative, so nothing is lost — the next debounce
     * write from the form fills these in. Back-filling by parsing the JSON was
     * never an option: SQLite cannot read it, and §6.1.2 is explicit that the
     * app does not touch user input to tidy up after itself.
     */
    @Test
    fun migrate_leavesTheSummaryEmptyForExistingDrafts() {
        seedV3()

        val db = helper.runMigrationsAndValidate(TEST_DB, V4, true, MIGRATION_3_4)

        db.query(
            "SELECT amount_minor, category_id, merchant_id FROM draft_entry",
        ).use { cursor ->
            while (cursor.moveToNext()) {
                assertThat(cursor.getLong(0)).isEqualTo(0L)
                assertThat(cursor.isNull(1)).isTrue()
                assertThat(cursor.isNull(2)).isTrue()
            }
        }
    }

    /** The new columns are writable, which is the point of adding them. */
    @Test
    fun migrate_acceptsASummaryAfterwards() {
        seedV3()

        val db = helper.runMigrationsAndValidate(TEST_DB, V4, true, MIGRATION_3_4)
        db.execSQL(
            "UPDATE draft_entry SET amount_minor = 24050, category_id = 'cat-1' " +
                "WHERE id = 'draft-a'",
        )

        db.query(
            "SELECT amount_minor, category_id FROM draft_entry WHERE id = 'draft-a'",
        ).use { cursor ->
            cursor.moveToFirst()
            assertThat(cursor.getLong(0)).isEqualTo(24_050L)
            assertThat(cursor.getString(1)).isEqualTo("cat-1")
        }
    }

    /**
     * The indices come back.
     *
     * They belonged to the table this migration drops, so they do not survive
     * the rename. `runMigrationsAndValidate` already compares the schema
     * against `schemas/4.json` and would fail on a missing one — this asserts
     * it by name as well, so the failure says *which* index went rather than
     * that something differed.
     */
    @Test
    fun migrate_recreatesEveryIndex() {
        seedV3()

        val db = helper.runMigrationsAndValidate(TEST_DB, V4, true, MIGRATION_3_4)

        val names = buildSet {
            db.query(
                "SELECT name FROM sqlite_master WHERE type = 'index' AND tbl_name = 'draft_entry'",
            ).use { cursor ->
                while (cursor.moveToNext()) add(cursor.getString(0))
            }
        }

        assertThat(names).containsAtLeast(
            "index_draft_entry_ledger_updated_at",
            "index_draft_entry_ledger_editing_entry_key",
            "index_draft_entry_updated_at",
            "index_draft_entry_editing_entry_id",
        )
    }

    /**
     * The foreign key survives the rebuild, and is still enforced.
     *
     * `draft_entry.editing_entry_id` cascades from `ledger_entry`. A rebuild
     * that dropped the constraint would leave edit-drafts stranded behind
     * deleted entries, and nothing else in the app would notice.
     */
    @Test
    fun migrate_keepsTheForeignKeyToLedgerEntry() {
        seedV3()

        val db = helper.runMigrationsAndValidate(TEST_DB, V4, true, MIGRATION_3_4)

        val foreignKeys = buildList {
            db.query("PRAGMA foreign_key_list(`draft_entry`)").use { cursor ->
                val table = cursor.getColumnIndexOrThrow("table")
                val from = cursor.getColumnIndexOrThrow("from")
                val onDelete = cursor.getColumnIndexOrThrow("on_delete")
                while (cursor.moveToNext()) {
                    add(Triple(cursor.getString(table), cursor.getString(from), cursor.getString(onDelete)))
                }
            }
        }

        assertThat(foreignKeys)
            .containsExactly(Triple("ledger_entry", "editing_entry_id", "CASCADE"))
    }

    /** No orphans left behind — the check §7 requires after every migration. */
    @Test
    fun migrate_leavesNoForeignKeyViolations() {
        seedV3()

        val db = helper.runMigrationsAndValidate(TEST_DB, V4, true, MIGRATION_3_4)

        db.query("PRAGMA foreign_key_check").use { cursor ->
            assertThat(cursor.count).isEqualTo(0)
        }
    }
}

private const val DEBIT_PAYLOAD = """{"amountMinor":24050,"note":"half typed"}"""
private const val SECOND_PAYLOAD = """{"amountMinor":6900,"lineItems":[]}"""
private const val CREDIT_PAYLOAD = """{"amountMinor":8500000,"note":"salary"}"""
