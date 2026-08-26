package com.ledgerflow.core.database.migration

import com.ledgerflow.core.database.LedgerFlowDatabase
import java.io.File
import net.zetetic.database.sqlcipher.SQLiteDatabase

/** What opening the database on disk is about to involve (SPEC.md §8.1). */
public sealed interface MigrationAssessment {

    /** Nothing to do: the file is absent (fresh install) or already current. */
    public data object NotNeeded : MigrationAssessment

    /** The file is older than this build. A snapshot must be taken first. */
    public data class Required(val from: Int, val to: Int, val databaseBytes: Long) :
        MigrationAssessment

    /**
     * The file was written by a **newer** build (BUG3).
     *
     * Never migrated downward and never opened for normal use. This is the
     * earliest point that can see it, because it is the only one that reads
     * `user_version` before handing the file to Room.
     */
    public data class Downgrade(val onDisk: Int, val supported: Int) : MigrationAssessment

    /**
     * The file exists but could not be read at all under this key.
     *
     * Not the guard's problem to solve — the unlock flow already has a Recovery
     * screen for it (§7.3), and this must not be confused with "no migration
     * needed" or the caller would proceed to open a database it cannot read.
     */
    public data class Unreadable(val reason: String) : MigrationAssessment
}

/** Outcome of taking the pre-migration snapshot. */
public sealed interface SnapshotResult {
    public data class Success(val file: File, val entryCount: Int) : SnapshotResult

    /**
     * The snapshot was not taken, or was taken and did not verify.
     *
     * **The migration must not run.** An unverified snapshot is not a snapshot,
     * exactly as an unverified `.lfbk` is not a backup (CLAUDE.md §7).
     */
    public data class Failure(val reason: String) : SnapshotResult

    /** Not enough free space. The figures are shown to the user (§8.1). */
    public data class InsufficientStorage(val requiredBytes: Long, val availableBytes: Long) :
        SnapshotResult
}

/**
 * BUG8(d): a rollback point taken before any migration runs — **ADR-0019**.
 *
 * §8 and §8.1 originally specified this as a verified `.lfbk`. It cannot be one.
 * A `.lfbk` is encrypted from the BIP-39 seed and by nothing else (ADR-0011),
 * and the app never holds the seed outside onboarding and Recovery; a normal
 * launch has a DEK. ADR-0019 records the resolution: the snapshot is a byte copy
 * of the already-encrypted database file, which needs no phrase, introduces no
 * new key material and adds no third wrap.
 *
 * A backup and a rollback snapshot are different objects. A backup has to
 * survive the device being lost, so it must be portable and phrase-derived. This
 * has to survive the next sixty seconds on this device, and is deleted within a
 * launch or two. **It is not a backup and must never be presented as one** —
 * the same rule the purge dialog follows.
 *
 * The order is not negotiable:
 *
 * 1. [assess] reads `user_version` without opening the database for normal use.
 * 2. [takeSnapshot] checkpoints the WAL, copies, and **verifies by reading the
 *    copy back** under the same key.
 * 3. The caller opens Room, which runs the migration.
 * 4. On failure the caller calls [restore]. This is the only automatic restore
 *    path in the app.
 * 5. [discardStaleSnapshot] runs on the next launch that needs no migration —
 *    one launch later than the migration itself, so a migration that succeeded
 *    but produced something wrong is still recoverable when the user first sees
 *    the result.
 *
 * @param databaseFile the live `databases/ledgerflow.db`.
 * @param snapshotDir a directory under `filesDir` (Law 5 — never `cacheDir`,
 *   which the OS may clear between step 2 and step 4).
 */
public class PreMigrationGuard(
    private val databaseFile: File,
    private val snapshotDir: File,
) {

    /** Where the snapshot for a database at [version] lives. */
    private fun snapshotFor(version: Int): File =
        File(snapshotDir, "ledgerflow-pre-v$version.db")

    /** Any snapshot left on disk, whatever version it came from. */
    private fun existingSnapshots(): List<File> =
        snapshotDir.listFiles { file -> file.name.startsWith(SNAPSHOT_PREFIX) }?.toList().orEmpty()

    /**
     * Reads the on-disk schema version without opening the database for use.
     *
     * Room keeps its schema version in SQLite's `user_version`, so this is a
     * single read against a connection that is closed immediately. Opening
     * through Room instead would *run the migration* as a side effect of asking
     * whether one is needed, which is the whole thing this has to happen before.
     */
    public fun assess(dek: ByteArray): MigrationAssessment {
        if (!databaseFile.exists() || databaseFile.length() == 0L) {
            // Fresh install: Room will create the file at the current version
            // and no migration runs.
            return MigrationAssessment.NotNeeded
        }

        loadNativeLibrary()
        val onDisk = runCatching {
            openReadOnly(databaseFile, dek).use { it.version }
        }.getOrElse { failure ->
            return MigrationAssessment.Unreadable(failure.message ?: failure::class.java.name)
        }

        val supported = LedgerFlowDatabase.VERSION
        return when {
            onDisk == supported -> MigrationAssessment.NotNeeded
            onDisk > supported -> MigrationAssessment.Downgrade(onDisk, supported)
            // A brand-new file Room has not stamped yet reads as 0.
            onDisk == 0 -> MigrationAssessment.NotNeeded
            else -> MigrationAssessment.Required(onDisk, supported, databaseFile.length())
        }
    }

    /**
     * Checkpoints, copies, and verifies. Returns [SnapshotResult.Success] only
     * once the copy has been opened under [dek] and shown to hold the same rows.
     *
     * **The checkpoint is load-bearing.** Copying `.db` while a populated `-wal`
     * sits beside it produces a snapshot missing the most recent writes — a
     * rollback point that silently rolls back further than it claims, which is
     * the quiet failure this mechanism exists to prevent. After
     * `wal_checkpoint(TRUNCATE)` the one file is self-contained.
     */
    @Suppress("ReturnCount")
    public fun takeSnapshot(dek: ByteArray, from: Int): SnapshotResult {
        loadNativeLibrary()

        val required = (databaseFile.length() * REQUIRED_SPACE_MULTIPLIER).toLong()
        val available = snapshotDir.parentFile?.usableSpace ?: snapshotDir.usableSpace
        if (available < required) {
            return SnapshotResult.InsufficientStorage(required, available)
        }

        val sourceEntries = runCatching {
            openReadWrite(databaseFile, dek).use { db ->
                // TRUNCATE rather than PASSIVE: PASSIVE may leave frames behind
                // if a reader is active, and "mostly checkpointed" is not a
                // property this can be built on.
                db.rawQuery("PRAGMA wal_checkpoint(TRUNCATE)", null).use { it.moveToFirst() }
                db.countLedgerEntries()
            }
        }.getOrElse { return SnapshotResult.Failure("checkpoint failed: ${it.message}") }

        val destination = snapshotFor(from)
        val copied = runCatching {
            snapshotDir.mkdirs()
            // Copy to a temp name and rename, for the same reason the backup
            // writer does: a crash mid-copy must not leave a truncated file
            // sitting where a valid snapshot is expected to be.
            val temp = File(snapshotDir, "${destination.name}.tmp")
            temp.delete()
            databaseFile.copyTo(temp, overwrite = true)
            temp to destination
        }.getOrElse { return SnapshotResult.Failure("copy failed: ${it.message}") }

        val (temp, target) = copied
        val verified = runCatching {
            openReadOnly(temp, dek).use { db ->
                db.version == from && db.countLedgerEntries() == sourceEntries
            }
        }.getOrDefault(false)

        if (!verified) {
            temp.delete()
            return SnapshotResult.Failure("verification failed; snapshot discarded")
        }
        target.delete()
        if (!temp.renameTo(target)) {
            temp.delete()
            return SnapshotResult.Failure("atomic rename failed")
        }
        return SnapshotResult.Success(target, sourceEntries)
    }

    /**
     * Puts the snapshot back. The only automatic restore in the app (§8.1).
     *
     * The stale `-wal` and `-shm` beside the failed database are deleted rather
     * than left: they describe the half-migrated file that is being replaced,
     * and SQLite would apply them over the restored one.
     */
    public fun restore(from: Int): Boolean {
        val snapshot = snapshotFor(from)
        if (!snapshot.isFile) return false

        return runCatching {
            File("${databaseFile.path}-wal").delete()
            File("${databaseFile.path}-shm").delete()
            snapshot.copyTo(databaseFile, overwrite = true)
            true
        }.getOrDefault(false)
    }

    /**
     * Deletes any snapshot left from a previous launch.
     *
     * Called only when [assess] reported [MigrationAssessment.NotNeeded], which
     * means the database opened cleanly at the current version at least once
     * since the migration ran.
     */
    public fun discardStaleSnapshot() {
        existingSnapshots().forEach { it.delete() }
    }

    private fun SQLiteDatabase.countLedgerEntries(): Int =
        rawQuery("SELECT COUNT(*) FROM ledger_entry", null).use { cursor ->
            if (cursor.moveToFirst()) cursor.getInt(0) else -1
        }

    private companion object {
        const val SNAPSHOT_PREFIX = "ledgerflow-pre-v"

        /**
         * §8.1's free-space rule, re-justified by ADR-0019.
         *
         * It was sized for snapshot + verification scratch + margin. A file copy
         * needs no verification scratch, but the migration does: the chain
         * rebuilds tables with `CREATE new / INSERT SELECT / DROP old`, which
         * transiently holds two copies of the largest table plus its journal.
         * 1x snapshot + migration working space + margin lands on the same
         * number, so the number did not move.
         */
        const val REQUIRED_SPACE_MULTIPLIER = 2.2

        fun loadNativeLibrary() {
            System.loadLibrary("sqlcipher")
        }

        fun openReadOnly(file: File, dek: ByteArray): SQLiteDatabase =
            SQLiteDatabase.openDatabase(file.path, dek, null, SQLiteDatabase.OPEN_READONLY, null)

        fun openReadWrite(file: File, dek: ByteArray): SQLiteDatabase =
            SQLiteDatabase.openDatabase(file.path, dek, null, SQLiteDatabase.OPEN_READWRITE, null)
    }
}
