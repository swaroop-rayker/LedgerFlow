package com.ledgerflow.core.database

import android.content.ContentValues
import android.database.sqlite.SQLiteConstraintException
import androidx.room.testing.MigrationTestHelper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.truth.Truth.assertThat
import com.ledgerflow.core.database.migration.MIGRATION_1_2
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * v1 -> v2, the BUG8 gate for this schema change (SPEC.md §8, §15.5).
 *
 * The migration is purely additive, which makes it the *easy* case — and
 * therefore the one where a test is most tempting to skip. It is not skipped,
 * for two reasons that additive migrations still get wrong: the hand-written
 * DDL can differ from what Room expects (which throws on every launch after the
 * upgrade, not during it), and a new foreign key can be declared against a
 * column that does not support it (which `PRAGMA foreign_key_check` catches and
 * nothing else does).
 *
 * Content equality, never row counts — §8 is explicit that counting rows is not
 * evidence a migration preserved anything.
 */
@RunWith(AndroidJUnit4::class)
class MigrationV1ToV2Test {

    private companion object {
        const val TEST_DB = "migration-v1-v2-test.db"
        const val V1 = 1
        const val V2 = 2

        val PASSPHRASE = ByteArray(32) { (it + 3).toByte() }

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

    /** Seeds one row in every v1 table that a real install would have. */
    private fun seedV1() {
        helper.createDatabase(TEST_DB, V1).use { db ->
            db.insert(
                "app_meta",
                android.database.sqlite.SQLiteDatabase.CONFLICT_ABORT,
                ContentValues().apply {
                    put("key", "baseCurrency")
                    put("value", "INR")
                },
            )
            db.insert(
                "category",
                android.database.sqlite.SQLiteDatabase.CONFLICT_ABORT,
                ContentValues().apply {
                    put("id", "cat-1")
                    putNull("parent_id")
                    put("parent_key", "")
                    put("ledger_scope", "DEBIT")
                    put("name", "Groceries")
                    put("icon", "food")
                    put("color_argb", -16777216)
                    put("sort_order", 0)
                    put("is_system", 1)
                    put("deleted_at", 0L)
                },
            )
            db.insert(
                "merchant",
                android.database.sqlite.SQLiteDatabase.CONFLICT_ABORT,
                ContentValues().apply {
                    put("id", "mer-1")
                    put("canonical_name", "Reliance Fresh")
                    put("normalized_key", "reliance fresh")
                    putNull("default_category_id")
                    putNull("logo_ref")
                    put("deleted_at", 0L)
                },
            )
            db.insert(
                "payment_method",
                android.database.sqlite.SQLiteDatabase.CONFLICT_ABORT,
                ContentValues().apply {
                    put("id", "pm-1")
                    put("type", "CASH")
                    put("label", "Cash")
                    putNull("issuer")
                    putNull("last4")
                    putNull("color_argb")
                    put("is_default", 1)
                    put("deleted_at", 0L)
                },
            )
            db.insert(
                "ledger_entry",
                android.database.sqlite.SQLiteDatabase.CONFLICT_ABORT,
                ContentValues().apply {
                    put("id", "ent-1")
                    put("ledger", "DEBIT")
                    put("amount_minor", 12_345L)
                    put("currency", "INR")
                    putNull("original_amount_minor")
                    putNull("original_currency")
                    putNull("fx_rate_micro")
                    put("occurred_at", 1_700_000_000_000L)
                    put("local_date", 19_700)
                    put("merchant_id", "mer-1")
                    put("category_id", "cat-1")
                    putNull("subcategory_id")
                    put("payment_method_id", "pm-1")
                    put("note", "weekly shop")
                    put("source", "MANUAL")
                    putNull("source_ref_id")
                    put("is_recurring", 0)
                    put("created_at", 1_700_000_000_000L)
                    put("updated_at", 1_700_000_000_000L)
                    putNull("deleted_at")
                },
            )
            db.insert(
                "line_item",
                android.database.sqlite.SQLiteDatabase.CONFLICT_ABORT,
                ContentValues().apply {
                    put("id", "li-1")
                    put("entry_id", "ent-1")
                    put("position", 0)
                    put("name", "Rice 5kg")
                    put("normalized_name", "rice 5kg")
                    put("quantity_milli", 1_000L)
                    put("unit_price_minor", 12_345L)
                    put("total_minor", 12_345L)
                    put("kind", "ITEM")
                    putNull("category_id")
                    putNull("subcategory_id")
                },
            )
        }
    }

    private fun migrate() = helper.runMigrationsAndValidate(
        TEST_DB,
        V2,
        /* validateDroppedTables = */ true,
        MIGRATION_1_2,
    )

    /**
     * Room validates the live database against the committed `2.json` here. A
     * migration whose DDL differs from the entity model by so much as a column
     * order fails on this line rather than on a user's device.
     */
    @Test
    fun migrationProducesTheSchemaRoomExpects() {
        seedV1()

        val database = migrate()

        assertThat(database.isOpen).isTrue()
        database.close()
    }

    @Test
    fun everySeededRowSurvivesWithItsContentIntact() {
        seedV1()

        val database = migrate()

        database.query("SELECT value FROM app_meta WHERE `key` = 'baseCurrency'").use {
            assertThat(it.moveToFirst()).isTrue()
            assertThat(it.getString(0)).isEqualTo("INR")
        }
        database.query("SELECT name, parent_key, is_system FROM category WHERE id = 'cat-1'").use {
            assertThat(it.moveToFirst()).isTrue()
            assertThat(it.getString(0)).isEqualTo("Groceries")
            assertThat(it.getString(1)).isEmpty()
            assertThat(it.getInt(2)).isEqualTo(1)
        }
        database.query("SELECT canonical_name, normalized_key FROM merchant WHERE id = 'mer-1'").use {
            assertThat(it.moveToFirst()).isTrue()
            assertThat(it.getString(0)).isEqualTo("Reliance Fresh")
            assertThat(it.getString(1)).isEqualTo("reliance fresh")
        }
        database.query("SELECT label, is_default FROM payment_method WHERE id = 'pm-1'").use {
            assertThat(it.moveToFirst()).isTrue()
            assertThat(it.getString(0)).isEqualTo("Cash")
            assertThat(it.getInt(1)).isEqualTo(1)
        }
        database.query(
            "SELECT amount_minor, note, category_id, merchant_id FROM ledger_entry WHERE id = 'ent-1'",
        ).use {
            assertThat(it.moveToFirst()).isTrue()
            assertThat(it.getLong(0)).isEqualTo(12_345L)
            assertThat(it.getString(1)).isEqualTo("weekly shop")
            assertThat(it.getString(2)).isEqualTo("cat-1")
            assertThat(it.getString(3)).isEqualTo("mer-1")
        }
        database.query("SELECT name, total_minor FROM line_item WHERE id = 'li-1'").use {
            assertThat(it.moveToFirst()).isTrue()
            assertThat(it.getString(0)).isEqualTo("Rice 5kg")
            assertThat(it.getLong(1)).isEqualTo(12_345L)
        }
        database.close()
    }

    /** The v1 views must still exist and still be disjoint (Law 2, §6.1.1). */
    @Test
    fun theLedgerViewsSurviveTheMigration() {
        seedV1()

        val database = migrate()

        database.query("SELECT COUNT(*) FROM debit_entries").use {
            assertThat(it.moveToFirst()).isTrue()
            assertThat(it.getInt(0)).isEqualTo(1)
        }
        database.query("SELECT COUNT(*) FROM credit_entries").use {
            assertThat(it.moveToFirst()).isTrue()
            assertThat(it.getInt(0)).isEqualTo(0)
        }
        database.close()
    }

    /**
     * CLAUDE.md §7 mandates this after every migration. It is the only thing
     * that catches a new foreign key pointing at a column that cannot support
     * it — a failure that is otherwise silent until a delete cascades wrongly.
     */
    @Test
    fun foreignKeyCheckPassesAfterTheMigration() {
        seedV1()

        val database = migrate()

        database.query("PRAGMA foreign_key_check").use {
            assertThat(it.count).isEqualTo(0)
        }
        database.close()
    }

    @Test
    fun theNewTablesExistAndAreEmpty() {
        seedV1()

        val database = migrate()

        listOf("draft_entry", "merchant_alias", "category_group", "category_group_member")
            .forEach { table ->
                database.query("SELECT COUNT(*) FROM $table").use {
                    assertThat(it.moveToFirst()).isTrue()
                    assertThat(it.getInt(0)).isEqualTo(0)
                }
            }
        database.close()
    }

    /**
     * The D-06 constraint, exercised against the real index rather than trusted.
     *
     * `(ledger, editing_entry_key)` is what stops drafts accumulating without
     * bound. `editing_entry_key` is a `''` sentinel rather than a nullable
     * `editing_entry_id` precisely because SQLite treats NULLs as distinct in a
     * unique index — with the nullable column the constraint would be
     * decorative, and this test would pass while enforcing nothing.
     */
    @Test
    fun onlyOneNewEntryDraftIsAllowedPerLedger() {
        seedV1()
        val database = migrate()

        fun insertDraft(id: String, ledger: String, slot: String) = database.insert(
            "draft_entry",
            android.database.sqlite.SQLiteDatabase.CONFLICT_ABORT,
            ContentValues().apply {
                put("id", id)
                put("ledger", ledger)
                putNull("editing_entry_id")
                put("editing_entry_key", slot)
                put("payload_json", "{}")
                put("payload_version", 1)
                put("created_at", 1L)
                put("updated_at", 1L)
            },
        )

        insertDraft("draft-debit", "DEBIT", "")
        // The other book is a different slot, so this must succeed.
        insertDraft("draft-credit", "CREDIT", "")

        val duplicate = runCatching { insertDraft("draft-debit-2", "DEBIT", "") }
        assertThat(duplicate.exceptionOrNull()).isInstanceOf(SQLiteConstraintException::class.java)

        database.query("SELECT COUNT(*) FROM draft_entry").use {
            assertThat(it.moveToFirst()).isTrue()
            assertThat(it.getInt(0)).isEqualTo(2)
        }
        database.close()
    }

    /**
     * A draft that is editing an entry must go when the entry does. Left behind,
     * it would be a resumable form pointing at a row that no longer exists.
     */
    @Test
    fun deletingAnEntryCascadesToItsEditDraft() {
        seedV1()
        val database = migrate()
        database.execSQL("PRAGMA foreign_keys = ON")
        database.insert(
            "draft_entry",
            android.database.sqlite.SQLiteDatabase.CONFLICT_ABORT,
            ContentValues().apply {
                put("id", "draft-edit")
                put("ledger", "DEBIT")
                put("editing_entry_id", "ent-1")
                put("editing_entry_key", "ent-1")
                put("payload_json", "{}")
                put("payload_version", 1)
                put("created_at", 1L)
                put("updated_at", 1L)
            },
        )

        database.execSQL("DELETE FROM ledger_entry WHERE id = 'ent-1'")

        database.query("SELECT COUNT(*) FROM draft_entry").use {
            assertThat(it.moveToFirst()).isTrue()
            assertThat(it.getInt(0)).isEqualTo(0)
        }
        database.close()
    }
}
