package com.ledgerflow.core.database

import androidx.room.testing.MigrationTestHelper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.truth.Truth.assertThat
import com.ledgerflow.core.database.migration.MIGRATION_6_7
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * v6 -> v7, the BUG8 gate for `parser_rule.instrument_hint`.
 *
 * A one-column change to a table that already holds rows, which is the case
 * CLAUDE.md §7's ban on `ALTER` chains exists for — so this is a full rebuild
 * and the assertion that matters is that **every existing rule came through it
 * intact**, including a rule the user wrote. Losing one of those is losing
 * something the user cannot get back from an asset.
 */
@RunWith(AndroidJUnit4::class)
class MigrationV6ToV7Test {

    private companion object {
        const val TEST_DB = "migration-v6-v7-test.db"
        const val V6 = 6
        const val V7 = 7

        val PASSPHRASE = ByteArray(32) { (it + 53).toByte() }

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

    private fun seedV6() {
        helper.createDatabase(TEST_DB, V6).use { db ->
            db.execSQL(
                "INSERT INTO parser_rule (id, ruleset_version, priority, sender_pattern, " +
                    "body_pattern, field_map_json, direction, confidence_base, enabled, " +
                    "is_user_defined) VALUES " +
                    "('shipped-1', 1, 10, 'HDFCBK', 'Rs\\.(?<a>\\d+)', '{\"amount\":\"a\"}', " +
                    "'DEBIT', 0.9, 1, 0)",
            )
            db.execSQL(
                "INSERT INTO parser_rule (id, ruleset_version, priority, sender_pattern, " +
                    "body_pattern, field_map_json, direction, confidence_base, enabled, " +
                    "is_user_defined) VALUES " +
                    "('mine-1', 1, 5, 'MYBANK', 'paid (?<a>\\d+)', '{\"amount\":\"a\"}', " +
                    "NULL, 0.6, 0, 1)",
            )
        }
    }

    /**
     * **The assertion.** Both rules survive, with every column intact — and the
     * user-written one keeps `is_user_defined = 1` and its `enabled = 0`.
     *
     * A rebuild that copied positionally into a table with one more column, or
     * that dropped a column from the `INSERT ... SELECT`, would silently reorder
     * or blank these. The user's own disabled rule is the row that would be
     * hardest to notice missing.
     */
    @Test
    fun migrate_preservesEveryRuleIncludingTheUsersOwn() {
        seedV6()

        val db = helper.runMigrationsAndValidate(TEST_DB, V7, true, MIGRATION_6_7)

        db.query(
            "SELECT id, ruleset_version, priority, sender_pattern, body_pattern, " +
                "field_map_json, direction, instrument_hint, confidence_base, enabled, " +
                "is_user_defined FROM parser_rule ORDER BY priority",
        ).use { cursor ->
            assertThat(cursor.count).isEqualTo(2)

            cursor.moveToFirst()
            assertThat(cursor.getString(0)).isEqualTo("mine-1")
            assertThat(cursor.getInt(2)).isEqualTo(5)
            assertThat(cursor.getString(3)).isEqualTo("MYBANK")
            assertThat(cursor.isNull(6)).isTrue()
            assertThat(cursor.getInt(9)).isEqualTo(0)
            assertThat(cursor.getInt(10)).isEqualTo(1)

            cursor.moveToNext()
            assertThat(cursor.getString(0)).isEqualTo("shipped-1")
            assertThat(cursor.getString(3)).isEqualTo("HDFCBK")
            assertThat(cursor.getString(6)).isEqualTo("DEBIT")
            assertThat(cursor.getDouble(8)).isEqualTo(0.9)
            assertThat(cursor.getInt(10)).isEqualTo(0)
        }
    }

    /**
     * The new column starts null, and is not back-filled.
     *
     * Guessing an instrument from a rule's regex is exactly what this column
     * exists to stop the code doing. Shipped rules repopulate on the next
     * ruleset load; a user's rule has no instrument to claim.
     */
    @Test
    fun migrate_leavesInstrumentHintNull() {
        seedV6()

        val db = helper.runMigrationsAndValidate(TEST_DB, V7, true, MIGRATION_6_7)

        db.query("SELECT instrument_hint FROM parser_rule").use { cursor ->
            while (cursor.moveToNext()) {
                assertThat(cursor.isNull(0)).isTrue()
            }
        }
    }

    @Test
    fun migrate_acceptsAnInstrumentHintAfterwards() {
        seedV6()

        val db = helper.runMigrationsAndValidate(TEST_DB, V7, true, MIGRATION_6_7)
        db.execSQL("UPDATE parser_rule SET instrument_hint = 'UPI' WHERE id = 'shipped-1'")

        db.query("SELECT instrument_hint FROM parser_rule WHERE id = 'shipped-1'").use { cursor ->
            cursor.moveToFirst()
            assertThat(cursor.getString(0)).isEqualTo("UPI")
        }
    }

    /** CLAUDE.md §7: every migration ends with a clean foreign-key check. */
    @Test
    fun migrate_leavesNoForeignKeyViolations() {
        seedV6()

        val db = helper.runMigrationsAndValidate(TEST_DB, V7, true, MIGRATION_6_7)

        db.query("PRAGMA foreign_key_check").use { cursor ->
            assertThat(cursor.count).isEqualTo(0)
        }
    }
}
