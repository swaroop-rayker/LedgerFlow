package com.ledgerflow.core.database

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import androidx.room.testing.MigrationTestHelper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.truth.Truth.assertThat
import com.ledgerflow.core.database.migration.MIGRATION_4_5
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * v4 -> v5, the BUG8 gate for `draft_entry.occurred_at`.
 *
 * A second table rebuild on the same table, one version after the first, and
 * the reason is worth recording: **v4 had already shipped to a real device** by
 * the time this column was wanted. Widening v4 instead would have left that
 * database claiming v4 with a shape the code no longer recognised, and Room
 * would have refused the open — a Recovery screen for a user whose data was
 * perfectly intact. Migrations are append-only for the same reason committed
 * schema JSONs are.
 *
 * The rows here are unsaved user input (BUG6), so the assertion that matters is
 * that **every payload and every v4 summary column survived** — not that the
 * new column exists.
 */
@RunWith(AndroidJUnit4::class)
class MigrationV4ToV5Test {

    private companion object {
        const val TEST_DB = "migration-v4-v5-test.db"
        const val V4 = 4
        const val V5 = 5

        val PASSPHRASE = ByteArray(32) { (it + 13).toByte() }

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

    private fun draft(
        id: String,
        ledger: String,
        payload: String,
        amountMinor: Long,
        categoryId: String?,
        updatedAt: Long,
    ) = ContentValues().apply {
        put("id", id)
        put("ledger", ledger)
        putNull("editing_entry_id")
        put("editing_entry_key", "")
        put("payload_json", payload)
        put("payload_version", 1)
        put("amount_minor", amountMinor)
        if (categoryId == null) putNull("category_id") else put("category_id", categoryId)
        putNull("merchant_id")
        put("created_at", 1_000L)
        put("updated_at", updatedAt)
    }

    private fun seedV4() {
        helper.createDatabase(TEST_DB, V4).use { db ->
            db.insert(
                "draft_entry",
                SQLiteDatabase.CONFLICT_ABORT,
                draft("draft-a", "DEBIT", DEBIT_PAYLOAD, 24_050L, "cat-1", 2_000L),
            )
            db.insert(
                "draft_entry",
                SQLiteDatabase.CONFLICT_ABORT,
                draft("draft-b", "CREDIT", CREDIT_PAYLOAD, 85_000_00L, null, 3_000L),
            )
        }
    }

    /**
     * **The assertion this test exists for.**
     *
     * The payload *and* the v4 summary columns come through the rebuild. A
     * migration that copied positionally into a table with one more column, or
     * that forgot `amount_minor` in the `INSERT ... SELECT`, would silently
     * reset every draft's amount to zero — which looks exactly like the drafts
     * were lost.
     */
    @Test
    fun migrate_preservesPayloadsAndTheV4Summary() {
        seedV4()

        val db = helper.runMigrationsAndValidate(TEST_DB, V5, true, MIGRATION_4_5)

        db.query(
            "SELECT id, ledger, payload_json, amount_minor, category_id, " +
                "created_at, updated_at FROM draft_entry ORDER BY updated_at",
        ).use { cursor ->
            assertThat(cursor.count).isEqualTo(2)

            cursor.moveToFirst()
            assertThat(cursor.getString(0)).isEqualTo("draft-a")
            assertThat(cursor.getString(2)).isEqualTo(DEBIT_PAYLOAD)
            assertThat(cursor.getLong(3)).isEqualTo(24_050L)
            assertThat(cursor.getString(4)).isEqualTo("cat-1")
            assertThat(cursor.getLong(5)).isEqualTo(1_000L)
            assertThat(cursor.getLong(6)).isEqualTo(2_000L)

            cursor.moveToNext()
            assertThat(cursor.getString(0)).isEqualTo("draft-b")
            assertThat(cursor.getString(2)).isEqualTo(CREDIT_PAYLOAD)
            assertThat(cursor.getLong(3)).isEqualTo(85_000_00L)
            assertThat(cursor.isNull(4)).isTrue()
        }
    }

    /**
     * Existing drafts get `occurred_at = 0`, and the reader treats that as
     * "unknown" rather than as 1 January 1970.
     *
     * Back-filling was impossible: the real date lives in the payload and
     * SQLite cannot read it, and §6.1.2 forbids rewriting user input to tidy
     * up. `DraftSummaryRow.datedAt` is the other half of this contract.
     */
    @Test
    fun migrate_leavesOccurredAtUnsetForExistingDrafts() {
        seedV4()

        val db = helper.runMigrationsAndValidate(TEST_DB, V5, true, MIGRATION_4_5)

        db.query("SELECT occurred_at FROM draft_entry").use { cursor ->
            while (cursor.moveToNext()) {
                assertThat(cursor.getLong(0)).isEqualTo(0L)
            }
        }
    }

    @Test
    fun migrate_acceptsAnOccurredAtAfterwards() {
        seedV4()

        val db = helper.runMigrationsAndValidate(TEST_DB, V5, true, MIGRATION_4_5)
        db.execSQL("UPDATE draft_entry SET occurred_at = 1755540000000 WHERE id = 'draft-a'")

        db.query("SELECT occurred_at FROM draft_entry WHERE id = 'draft-a'").use { cursor ->
            cursor.moveToFirst()
            assertThat(cursor.getLong(0)).isEqualTo(1_755_540_000_000L)
        }
    }

    @Test
    fun migrate_recreatesEveryIndex() {
        seedV4()

        val db = helper.runMigrationsAndValidate(TEST_DB, V5, true, MIGRATION_4_5)

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

    @Test
    fun migrate_keepsTheForeignKeyToLedgerEntry() {
        seedV4()

        val db = helper.runMigrationsAndValidate(TEST_DB, V5, true, MIGRATION_4_5)

        val foreignKeys = buildList {
            db.query("PRAGMA foreign_key_list(`draft_entry`)").use { cursor ->
                val table = cursor.getColumnIndexOrThrow("table")
                val from = cursor.getColumnIndexOrThrow("from")
                val onDelete = cursor.getColumnIndexOrThrow("on_delete")
                while (cursor.moveToNext()) {
                    add(
                        Triple(
                            cursor.getString(table),
                            cursor.getString(from),
                            cursor.getString(onDelete),
                        ),
                    )
                }
            }
        }

        assertThat(foreignKeys)
            .containsExactly(Triple("ledger_entry", "editing_entry_id", "CASCADE"))
    }

    /** No orphans left behind — the check §7 requires after every migration. */
    @Test
    fun migrate_leavesNoForeignKeyViolations() {
        seedV4()

        val db = helper.runMigrationsAndValidate(TEST_DB, V5, true, MIGRATION_4_5)

        db.query("PRAGMA foreign_key_check").use { cursor ->
            assertThat(cursor.count).isEqualTo(0)
        }
    }
}

private const val DEBIT_PAYLOAD = """{"amountMinor":24050,"note":"half typed"}"""
private const val CREDIT_PAYLOAD = """{"amountMinor":8500000,"note":"salary"}"""
