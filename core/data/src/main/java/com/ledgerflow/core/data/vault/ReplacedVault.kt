package com.ledgerflow.core.data.vault

import android.content.Context
import com.ledgerflow.core.data.ingest.AttachmentFiles
import java.io.File
import java.io.FileOutputStream

/**
 * Where a vault's files live besides its database: the wrapped DEKs and the
 * sealed receipt images. A restore from Settings moves all three together
 * (ADR-0033 e), so the session has to be told where the other two are.
 *
 * Providers, resolved on first use, for the reason `FileWrappedDekStore` gives:
 * `filesDir` stats the data directory, and this is built on the main thread.
 */
public class VaultFiles(
    keyDirectory: () -> File,
    attachmentDirectory: () -> File,
) {
    internal val keyDirectory: File by lazy(keyDirectory)
    internal val attachmentDirectory: File by lazy(attachmentDirectory)

    public companion object {
        /** `filesDir/keys` is `CryptoModule`'s; `filesDir/attachments` is [AttachmentFiles]'. */
        public fun forApp(context: Context): VaultFiles = VaultFiles(
            keyDirectory = { File(context.filesDir, KEY_DIRECTORY) },
            attachmentDirectory = { File(context.filesDir, AttachmentFiles.DIRECTORY) },
        )

        /** The wrapped DEKs' directory under `filesDir` (Law 5). Shared with `CryptoModule`. */
        public const val KEY_DIRECTORY: String = "keys"
    }
}

/**
 * The vault a restore from Settings set aside (ADR-0033 e): moved, by rename,
 * into [root], and kept until the restored vault has opened cleanly once.
 *
 * **Not a backup**, and nothing says it is (ADR-0019's rule for the migration
 * snapshot): it lives on this phone only and is deleted on the next clean
 * launch. It exists so that a restore that does not finish can be undone.
 *
 * Everything here is a rename within `filesDir` and `databases/`, one
 * filesystem, so each move is atomic and costs no space. A restore writes
 * nothing of its own until [setAside] has finished, and records that it has
 * ([complete]), which is what lets [putBack] tell the restore's files from
 * the old vault's after a crash at any point.
 */
internal class ReplacedVault(
    private val root: File,
    private val databaseFile: File,
    private val files: VaultFiles,
) {
    /** One thing moved as a whole: a file of the database, or a directory. */
    private class Part(val live: File, val aside: File)

    private val parts: List<Part>
        get() {
            val databaseAside = File(root, DATABASE)
            val databaseParts = DATABASE_SUFFIXES.map { suffix ->
                val name = databaseFile.name + suffix
                Part(File(databaseFile.parentFile, name), File(databaseAside, name))
            }
            return databaseParts + listOf(
                Part(files.keyDirectory, File(root, KEYS)),
                Part(files.attachmentDirectory, File(root, ATTACHMENTS)),
            )
        }

    private val complete: File get() = File(root, COMPLETE)

    /** Something is set aside. */
    fun exists(): Boolean = root.exists()

    /**
     * Moves every part of the vault aside. Idempotent: a part is moved only if
     * it is still in place and not already aside, so a restore resumed after a
     * crash finishes the job rather than starting it again. Once everything is
     * moved, [complete] is written; from then on, anything in the live places
     * was written by the restore.
     */
    fun setAside(): Boolean {
        if (complete.isFile) return true
        if (!root.exists() && !root.mkdirs()) return false
        File(root, DATABASE).mkdirs()
        val moved = parts.all { part ->
            when {
                !part.live.exists() -> true
                part.aside.exists() -> false
                else -> part.live.renameTo(part.aside)
            }
        }
        return moved && writeSynced(complete)
    }

    /**
     * Returns the set-aside vault to where it was. A part that is aside goes
     * back over whatever is in its place now. A part that was never aside is
     * left where it is — unless the set-aside had finished, in which case what
     * is in its place was written by the restore, and is removed.
     *
     * The caller must have closed any database open on these files.
     */
    fun putBack(): Boolean {
        if (!root.exists()) return false
        val finished = complete.isFile
        val returned = parts.all { part ->
            when {
                part.aside.exists() -> {
                    part.live.deleteRecursively()
                    part.live.parentFile?.mkdirs()
                    part.aside.renameTo(part.live)
                }
                finished -> {
                    part.live.deleteRecursively()
                    !part.live.exists()
                }
                else -> true
            }
        }
        if (returned) root.deleteRecursively()
        return returned
    }

    /**
     * Whatever was set aside, back in place. Nothing set aside at all — the
     * marker was written and the process died before the first move — means
     * the vault never left, and that counts as returned.
     */
    fun returnAll(): Boolean = !exists() || putBack()

    /** The restored vault opened cleanly: the old one is no longer needed. */
    fun discard() {
        root.deleteRecursively()
    }

    private fun writeSynced(file: File): Boolean = runCatching {
        FileOutputStream(file).use { stream ->
            stream.write(COMPLETE_CONTENT)
            stream.flush()
            stream.fd.sync()
        }
    }.isSuccess

    companion object {
        /** `filesDir/<database name>` + this. */
        const val SUFFIX: String = ".replaced"

        private const val DATABASE = "database"
        private const val KEYS = "keys"
        private const val ATTACHMENTS = "attachments"
        private const val COMPLETE = "set-aside-complete"
        private val COMPLETE_CONTENT = "set aside v1".toByteArray()

        /** The database file and SQLite's companions to it. */
        private val DATABASE_SUFFIXES = listOf("", "-wal", "-shm", "-journal")
    }
}
