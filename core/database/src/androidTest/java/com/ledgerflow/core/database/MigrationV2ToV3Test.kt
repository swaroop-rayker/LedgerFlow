package com.ledgerflow.core.database

import android.content.ContentValues
import android.database.sqlite.SQLiteConstraintException
import androidx.room.testing.MigrationTestHelper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.truth.Truth.assertThat
import com.ledgerflow.core.database.migration.MIGRATION_2_3
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * v2 -> v3, the BUG8 gate for dropping `draft_entry`'s unique slot index
 * (ADR-0013, superseding D-06).
 *
 * The rows in `draft_entry` are unsaved user input — the whole reason the table
 * exists (BUG6) — so "the migration preserved them" is the assertion that
 * matters most here, and it is made on content rather than on a row count, per
 * SPEC.md §8.
 *
 * The interesting half is the *behaviour* change: before this migration a
 * second new-entry draft in the same book was rejected by the index, and after
 * it that is exactly what has to be possible. Both directions are asserted, so
 * the test would fail if the index were left in place *or* if the schema were
 * changed without the migration running.
 */
@RunWith(AndroidJUnit4::class)
class MigrationV2ToV3Test {

    private companion object {
        const val TEST_DB = "migration-v2-v3-test.db"
        const val V2 = 2
        const val V3 = 3

        val PASSPHRASE = ByteArray(32) { (it + 5).toByte() }

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

    /** One draft per book, which is all v2's index allowed. */
    private fun seedV2() {
        helper.createDatabase(TEST_DB, V2).use { db ->
            db.insert(
                "draft_entry",
                android.database.sqlite.SQLiteDatabase.CONFLICT_ABORT,
                draft("draft-debit", "DEBIT", """{"amountMinor":12500}""", 2_000L),
            )
            db.insert(
                "draft_entry",
                android.database.sqlite.SQLiteDatabase.CONFLICT_ABORT,
                draft("draft-credit", "CREDIT", """{"amountMinor":900000}""", 3_000L),
            )
        }
    }

    private fun migrate() = helper.runMigrationsAndValidate(
        TEST_DB,
        V3,
        /* validateDroppedTables = */ true,
        MIGRATION_2_3,
    )

    /**
     * Room validates the live database against the committed `3.json` here. An
     * index created with a different name, or one left behind, fails on this
     * line rather than as an `IllegalStateException` on every launch after a
     * user upgrades.
     */
    @Test
    fun migrationProducesTheSchemaRoomExpects() {
        seedV2()

        val database = migrate()

        assertThat(database.isOpen).isTrue()
        database.close()
    }

    /** Unsaved work is what this table holds; the migration must not cost any. */
    @Test
    fun everyDraftSurvivesWithItsPayloadIntact() {
        seedV2()

        val database = migrate()

        database.query(
            "SELECT ledger, payload_json, payload_version, created_at, updated_at " +
                "FROM draft_entry WHERE id = 'draft-debit'",
        ).use {
            assertThat(it.moveToFirst()).isTrue()
            assertThat(it.getString(0)).isEqualTo("DEBIT")
            assertThat(it.getString(1)).isEqualTo("""{"amountMinor":12500}""")
            assertThat(it.getInt(2)).isEqualTo(1)
            assertThat(it.getLong(3)).isEqualTo(1_000L)
            assertThat(it.getLong(4)).isEqualTo(2_000L)
        }
        database.query("SELECT payload_json FROM draft_entry WHERE id = 'draft-credit'").use {
            assertThat(it.moveToFirst()).isTrue()
            assertThat(it.getString(0)).isEqualTo("""{"amountMinor":900000}""")
        }
        database.close()
    }

    /**
     * The point of the migration.
     *
     * Two new-entry drafts in one book share `(ledger, editing_entry_key)` --
     * `('DEBIT', '')` -- which v2's unique index rejected. That rejection is
     * what made a second in-flight entry impossible, and read to the user as
     * the first one being destroyed.
     */
    @Test
    fun aSecondDraftInTheSameBookIsAcceptedAfterTheMigration() {
        seedV2()

        val database = migrate()
        database.insert(
            "draft_entry",
            android.database.sqlite.SQLiteDatabase.CONFLICT_ABORT,
            draft("draft-debit-2", "DEBIT", """{"amountMinor":4200}""", 4_000L),
        )

        database.query("SELECT COUNT(*) FROM draft_entry WHERE ledger = 'DEBIT'").use {
            assertThat(it.moveToFirst()).isTrue()
            assertThat(it.getInt(0)).isEqualTo(2)
        }
        database.close()
    }

    /**
     * Proves the migration is what changed it, rather than the seed having been
     * lenient all along. Without this, the test above would pass against a v2
     * database whose index had never existed.
     */
    @Test
    fun theSameSecondDraftIsRejectedBeforeTheMigration() {
        helper.createDatabase(TEST_DB, V2).use { db ->
            db.insert(
                "draft_entry",
                android.database.sqlite.SQLiteDatabase.CONFLICT_ABORT,
                draft("draft-debit", "DEBIT", """{"amountMinor":12500}""", 2_000L),
            )

            val rejected = runCatching {
                db.insert(
                    "draft_entry",
                    android.database.sqlite.SQLiteDatabase.CONFLICT_ABORT,
                    draft("draft-debit-2", "DEBIT", """{"amountMinor":4200}""", 4_000L),
                )
            }

            assertThat(rejected.exceptionOrNull())
                .isInstanceOf(SQLiteConstraintException::class.java)
        }
    }
}
