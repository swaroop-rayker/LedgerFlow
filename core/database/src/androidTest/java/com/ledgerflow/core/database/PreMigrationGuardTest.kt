package com.ledgerflow.core.database

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase as AndroidSQLiteDatabase
import androidx.room.testing.MigrationTestHelper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.truth.Truth.assertThat
import com.ledgerflow.core.database.migration.MigrationAssessment
import com.ledgerflow.core.database.migration.PreMigrationGuard
import com.ledgerflow.core.database.migration.SnapshotResult
import java.io.File
import net.zetetic.database.sqlcipher.SQLiteDatabase
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * BUG8(d)'s rollback point, as ADR-0019 defines it.
 *
 * The migration chain tests prove that a migration *works*. This proves what
 * happens when one does not — which is the case no chain test can reach,
 * because a migration that fails in the test suite is a migration that gets
 * fixed before it ships. The failures that matter happen on a device, once, to
 * one user, and the only thing standing behind them is this.
 *
 * **The snapshot is a copy of the encrypted file, not a `.lfbk`** (ADR-0019): a
 * `.lfbk` is phrase-derived and the app never holds the phrase at launch. The
 * assertions therefore check that the copy opens *under the same DEK* and holds
 * the same rows — the file-copy equivalent of the backup writer's
 * decrypt-and-parse verification.
 */
@RunWith(AndroidJUnit4::class)
class PreMigrationGuardTest {

    private companion object {
        const val TEST_DB = "premigration-guard-test.db"
        const val V5 = 5

        val PASSPHRASE = ByteArray(32) { (it + 71).toByte() }

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

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var databaseFile: File
    private lateinit var snapshotDir: File
    private lateinit var guard: PreMigrationGuard

    @Before
    fun setUp() {
        databaseFile = context.getDatabasePath(TEST_DB)
        // filesDir, never cacheDir: the OS may clear cacheDir between taking the
        // snapshot and needing to restore it (Law 5).
        snapshotDir = File(context.filesDir, "premigration-test").also {
            it.deleteRecursively()
            it.mkdirs()
        }
        guard = PreMigrationGuard(databaseFile, snapshotDir)
    }

    private fun entry(id: String, amountMinor: Long) = ContentValues().apply {
        put("id", id)
        put("ledger", "DEBIT")
        put("amount_minor", amountMinor)
        put("currency", "INR")
        put("occurred_at", 1_700_000_000_000L)
        put("local_date", 19_700L)
        put("source", "MANUAL")
        put("is_recurring", 0)
        put("created_at", 1_700_000_000_000L)
        put("updated_at", 1_700_000_000_000L)
    }

    /** A v5 database on disk with [count] entries, closed. */
    private fun seedV5(count: Int = 3) {
        helper.createDatabase(TEST_DB, V5).use { db ->
            repeat(count) { index ->
                db.insert(
                    "ledger_entry",
                    AndroidSQLiteDatabase.CONFLICT_ABORT,
                    entry("e-$index", (index + 1) * 1_000L),
                )
            }
        }
    }

    private fun openSnapshot(file: File): SQLiteDatabase =
        SQLiteDatabase.openDatabase(file.path, PASSPHRASE, null, SQLiteDatabase.OPEN_READONLY, null)

    private fun SQLiteDatabase.entryCount(): Int =
        rawQuery("SELECT COUNT(*) FROM ledger_entry", null).use { cursor ->
            cursor.moveToFirst()
            cursor.getInt(0)
        }

    @Test
    fun assess_onAV5File_reportsAMigrationToTheCurrentVersion() {
        seedV5()

        val assessment = guard.assess(PASSPHRASE)

        assertThat(assessment).isInstanceOf(MigrationAssessment.Required::class.java)
        val required = assessment as MigrationAssessment.Required
        assertThat(required.from).isEqualTo(V5)
        assertThat(required.to).isEqualTo(LedgerFlowDatabase.VERSION)
    }

    /**
     * Asking whether a migration is needed must not *perform* one.
     *
     * `assess` reads `user_version` through a raw connection it closes
     * immediately. Going through Room to ask would run the migration as a side
     * effect of the question, which is the thing the snapshot has to happen
     * before.
     */
    @Test
    fun assess_doesNotItselfMigrate() {
        seedV5()

        guard.assess(PASSPHRASE)

        openSnapshot(databaseFile).use { assertThat(it.version).isEqualTo(V5) }
    }

    @Test
    fun assess_onAnAbsentFile_isNotNeeded() {
        databaseFile.delete()

        assertThat(guard.assess(PASSPHRASE)).isEqualTo(MigrationAssessment.NotNeeded)
    }

    /**
     * A database from a newer build is never migrated downward and never opened
     * (BUG3). This is the earliest point that can tell.
     */
    @Test
    fun assess_onANewerFile_reportsADowngrade() {
        seedV5()
        SQLiteDatabase.openDatabase(
            databaseFile.path,
            PASSPHRASE,
            null,
            SQLiteDatabase.OPEN_READWRITE,
            null,
        ).use { it.version = LedgerFlowDatabase.VERSION + 5 }

        val assessment = guard.assess(PASSPHRASE)

        assertThat(assessment).isInstanceOf(MigrationAssessment.Downgrade::class.java)
    }

    /** A wrong key is not "no migration needed" — the caller must not proceed to open. */
    @Test
    fun assess_underTheWrongKey_isUnreadableRatherThanNotNeeded() {
        seedV5()

        val assessment = guard.assess(ByteArray(32) { 1 })

        assertThat(assessment).isInstanceOf(MigrationAssessment.Unreadable::class.java)
    }

    /**
     * **The assertion this class exists for.** The snapshot opens under the same
     * DEK, is still at the old version, and holds the same rows.
     */
    @Test
    fun takeSnapshot_producesAVerifiableCopyAtTheOldVersion() {
        seedV5(count = 4)

        val result = guard.takeSnapshot(PASSPHRASE, V5)

        assertThat(result).isInstanceOf(SnapshotResult.Success::class.java)
        val success = result as SnapshotResult.Success
        assertThat(success.entryCount).isEqualTo(4)
        openSnapshot(success.file).use { snapshot ->
            assertThat(snapshot.version).isEqualTo(V5)
            assertThat(snapshot.entryCount()).isEqualTo(4)
        }
    }

    /**
     * Rows written just before the snapshot are in it.
     *
     * Without the `wal_checkpoint(TRUNCATE)`, the copy is of `.db` alone and the
     * newest writes are still sitting in `-wal` — producing a rollback point
     * that silently rolls back further than it claims. That is a worse failure
     * than no snapshot, because it looks like it worked.
     */
    @Test
    fun takeSnapshot_includesWritesStillInTheWriteAheadLog() {
        seedV5(count = 2)
        SQLiteDatabase.openDatabase(
            databaseFile.path,
            PASSPHRASE,
            null,
            SQLiteDatabase.OPEN_READWRITE,
            null,
        ).use { db ->
            // A PRAGMA that returns a value has to go through rawQuery;
            // execSQL refuses it. The guard's checkpoint does the same.
            db.rawQuery("PRAGMA journal_mode=WAL", null).use { it.moveToFirst() }
            db.insert("ledger_entry", AndroidSQLiteDatabase.CONFLICT_ABORT, entry("late", 99_00L))
        }

        val result = guard.takeSnapshot(PASSPHRASE, V5) as SnapshotResult.Success

        openSnapshot(result.file).use { snapshot ->
            assertThat(snapshot.entryCount()).isEqualTo(3)
            snapshot.rawQuery("SELECT amount_minor FROM ledger_entry WHERE id = 'late'", null)
                .use { cursor ->
                    assertThat(cursor.moveToFirst()).isTrue()
                    assertThat(cursor.getLong(0)).isEqualTo(99_00L)
                }
        }
    }

    /**
     * The automatic restore: the one in the app (§8.1).
     *
     * Simulates a migration that got partway — the version stamp advanced and a
     * table was dropped — and asserts the restore puts back a database at the
     * old version with every row intact.
     */
    @Test
    fun restore_undoesAHalfAppliedMigration() {
        seedV5(count = 3)
        guard.takeSnapshot(PASSPHRASE, V5)

        SQLiteDatabase.openDatabase(
            databaseFile.path,
            PASSPHRASE,
            null,
            SQLiteDatabase.OPEN_READWRITE,
            null,
        ).use { db ->
            db.execSQL("DROP TABLE line_item")
            db.execSQL("DELETE FROM ledger_entry")
            db.version = LedgerFlowDatabase.VERSION
        }

        assertThat(guard.restore(V5)).isTrue()

        openSnapshot(databaseFile).use { restored ->
            assertThat(restored.version).isEqualTo(V5)
            assertThat(restored.entryCount()).isEqualTo(3)
            // The dropped table is back, which a version stamp alone would not
            // have told us.
            restored.rawQuery("SELECT COUNT(*) FROM line_item", null).use { cursor ->
                assertThat(cursor.moveToFirst()).isTrue()
            }
        }
    }

    @Test
    fun restore_withNoSnapshot_reportsFailureRatherThanPretending() {
        seedV5()

        assertThat(guard.restore(V5)).isFalse()
    }

    /**
     * The snapshot survives only until a launch that needs no migration.
     *
     * One launch later than the migration itself, so a migration that succeeded
     * but produced something wrong is still recoverable at the point the user
     * first sees the result.
     */
    @Test
    fun discardStaleSnapshot_removesIt() {
        seedV5()
        val snapshot = (guard.takeSnapshot(PASSPHRASE, V5) as SnapshotResult.Success).file
        assertThat(snapshot.isFile).isTrue()

        guard.discardStaleSnapshot()

        assertThat(snapshot.isFile).isFalse()
    }

    /**
     * An unverifiable snapshot leaves nothing behind that a restore could pick
     * up and trust.
     *
     * Verification is against the *wrong* source version here, which is the
     * cheapest way to make the check fail without corrupting a file. The rule it
     * proves is the backup writer's: a snapshot that did not verify is not a
     * snapshot, and no partial file is left where a valid one is expected.
     */
    @Test
    fun takeSnapshot_thatDoesNotVerify_leavesNoFile() {
        seedV5()

        val result = guard.takeSnapshot(PASSPHRASE, V5 + 1)

        assertThat(result).isInstanceOf(SnapshotResult.Failure::class.java)
        assertThat(snapshotDir.listFiles().orEmpty().toList()).isEmpty()
    }
}
