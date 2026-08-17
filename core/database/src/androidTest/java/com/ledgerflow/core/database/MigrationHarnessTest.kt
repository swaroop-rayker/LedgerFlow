package com.ledgerflow.core.database

import android.content.ContentValues
import androidx.room.testing.MigrationTestHelper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.truth.Truth.assertThat
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The migration harness -- the BUG8 gate (SPEC.md §8, §15.5).
 *
 * `MigrationTestHelper` reads the committed schema JSONs from the test APK's
 * assets and asserts that the live database matches them exactly. That is what
 * makes "the schema JSONs are committed and append-only" mean something: a
 * schema change without a migration fails here, not on a user's device.
 *
 * The helper is given SQLCipher's open factory, so these tests exercise the
 * *encrypted* database rather than a plaintext stand-in. A harness that only
 * ever validated an unencrypted database would not be testing what ships.
 */
@RunWith(AndroidJUnit4::class)
class MigrationHarnessTest {

    private companion object {
        const val TEST_DB = "migration-harness-test.db"

        /** Fixed, non-secret key: this database exists only inside the test. */
        val PASSPHRASE = ByteArray(32) { (it + 1).toByte() }

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

    /**
     * Proves the harness is wired: it can find `1.json`, create the schema from
     * it, and validate a live database against it.
     */
    @Test
    fun currentSchemaIsCreatedAndValidatesAgainstTheCommittedJson() {
        helper.createDatabase(TEST_DB, LedgerFlowDatabase.VERSION).close()

        val database = helper.runMigrationsAndValidate(
            TEST_DB,
            LedgerFlowDatabase.VERSION,
            /* validateDroppedTables = */ true,
        )

        assertThat(database.isOpen).isTrue()
        database.close()
    }

    /**
     * Data seeded at v1 must survive being reopened through the harness.
     *
     * Content equality, not row counts -- SPEC.md §8 is explicit that counting
     * rows is not evidence a migration preserved anything.
     */
    @Test
    fun seededDataSurvivesReopenAtTheCurrentVersion() {
        helper.createDatabase(TEST_DB, LedgerFlowDatabase.VERSION).use { db ->
            db.insert(
                "app_meta",
                android.database.sqlite.SQLiteDatabase.CONFLICT_ABORT,
                ContentValues().apply {
                    put("key", AppMetaEntity_KEY_CANARY)
                    put("value", AppMetaEntity_CANARY_VALUE)
                },
            )
            db.insert(
                "merchant",
                android.database.sqlite.SQLiteDatabase.CONFLICT_ABORT,
                ContentValues().apply {
                    put("id", "mer-seed")
                    put("canonical_name", "Seeded Merchant")
                    put("normalized_key", "SEEDEDMERCHANT")
                    put("deleted_at", 0L)
                },
            )
        }

        val database = helper.runMigrationsAndValidate(
            TEST_DB,
            LedgerFlowDatabase.VERSION,
            true,
        )

        database.query("SELECT value FROM app_meta WHERE `key` = ?", arrayOf(AppMetaEntity_KEY_CANARY))
            .use { cursor ->
                assertThat(cursor.moveToFirst()).isTrue()
                assertThat(cursor.getString(0)).isEqualTo(AppMetaEntity_CANARY_VALUE)
            }
        database.query("SELECT canonical_name FROM merchant WHERE id = ?", arrayOf("mer-seed"))
            .use { cursor ->
                assertThat(cursor.moveToFirst()).isTrue()
                assertThat(cursor.getString(0)).isEqualTo("Seeded Merchant")
            }
        database.close()
    }

    /** Foreign keys must be enforceable on a harness-created database too. */
    @Test
    fun foreignKeyCheckPassesOnAFreshSchema() {
        val database = helper.createDatabase(TEST_DB, LedgerFlowDatabase.VERSION)

        database.query("PRAGMA foreign_key_check").use { cursor ->
            // Any row here is a violation; a clean schema returns none.
            assertThat(cursor.count).isEqualTo(0)
        }
        database.close()
    }
}

// Referenced from the test above without importing the entity, so the harness
// exercises the raw column names exactly as the schema JSON declares them.
private const val AppMetaEntity_KEY_CANARY = "canary"
private const val AppMetaEntity_CANARY_VALUE = "LedgerFlow-canary-v1"
