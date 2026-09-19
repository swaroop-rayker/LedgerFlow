package com.ledgerflow.core.data.backup

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.RandomAccessFile

/**
 * The user's backup folder, whatever it physically is (§5.9, ADR-0023).
 *
 * The app writes backups into a folder the user granted through the Storage
 * Access Framework, which is a `content://` tree and not a `File`; the tests
 * write into a directory. This is the small surface both need, so the backup
 * logic above it is written once and tested against a real directory.
 *
 * **Every write is verified before it counts** (CLAUDE.md §7, backup writer):
 * [writeVerified] writes a temporary, flushes it to storage, reads back the
 * bytes that actually landed, runs the caller's check on *those*, and only then
 * puts the file in place. A write that fails any step leaves nothing behind
 * under the final name.
 */
public interface BackupFolder {

    /**
     * The folder's own name, or null if it no longer exists (BUG27).
     *
     * A held grant says nothing about the folder: renaming or deleting it
     * leaves the grant in place, and every write then fails with a message
     * about space. This is the question the grant cannot answer.
     */
    public fun displayName(): String?

    /** Names of the plain files directly in this folder. */
    public fun list(): List<String>

    public fun exists(name: String): Boolean

    /** The whole file, or null if it is absent or unreadable. */
    public fun read(name: String): ByteArray?

    /** At most the first [count] bytes — a header, without reading an image. */
    public fun readPrefix(name: String, count: Int): ByteArray?

    /**
     * Writes [bytes] as [name], verified. [verify] receives the bytes read back
     * from storage, not [bytes]; returning false abandons the write.
     *
     * @return true only if the file is in place under [name] and verified.
     */
    public fun writeVerified(name: String, bytes: ByteArray, verify: (ByteArray) -> Boolean): Boolean

    /** @return true if [name] is gone afterwards, including if it was never there. */
    public fun delete(name: String): Boolean

    /** A child folder, created if it does not exist; null if it cannot be. */
    public fun subfolder(name: String): BackupFolder?

    public companion object {
        /** The suffix a file carries while it is being written and verified. */
        public const val TEMP_SUFFIX: String = ".tmp"
    }
}

/**
 * A [BackupFolder] that is a directory. The tests' folder, and a faithful one:
 * the same temp → fsync → verify → rename discipline the SAF tree follows.
 */
public class FileBackupFolder(private val directory: File) : BackupFolder {

    override fun displayName(): String? = directory.takeIf { it.isDirectory }?.name

    override fun list(): List<String> =
        directory.listFiles().orEmpty().filter { it.isFile }.map { it.name }

    override fun exists(name: String): Boolean = File(directory, name).isFile

    override fun read(name: String): ByteArray? =
        runCatching { File(directory, name).takeIf { it.isFile }?.readBytes() }.getOrNull()

    override fun readPrefix(name: String, count: Int): ByteArray? = runCatching {
        val file = File(directory, name).takeIf { it.isFile } ?: return null
        RandomAccessFile(file, "r").use { handle ->
            val size = minOf(count.toLong(), handle.length()).toInt()
            ByteArray(size).also { handle.readFully(it) }
        }
    }.getOrNull()

    override fun writeVerified(name: String, bytes: ByteArray, verify: (ByteArray) -> Boolean): Boolean {
        directory.mkdirs()
        val temp = File(directory, name + BackupFolder.TEMP_SUFFIX)
        val written = try {
            FileOutputStream(temp).use { stream ->
                stream.write(bytes)
                stream.flush()
                stream.fd.sync()
            }
            true
        } catch (_: IOException) {
            false
        }
        val verified = written && runCatching { verify(temp.readBytes()) }.getOrDefault(false)
        if (!verified || !promote(temp, File(directory, name))) {
            temp.delete()
            return false
        }
        return true
    }

    /**
     * Rename over the target. On Linux `rename(2)` replaces atomically, so the
     * old copy is never absent; delete-then-rename is only the fallback for a
     * filesystem that refuses to replace.
     */
    private fun promote(temp: File, target: File): Boolean =
        temp.renameTo(target) || (target.delete() && temp.renameTo(target))

    override fun delete(name: String): Boolean {
        val file = File(directory, name)
        return !file.exists() || file.delete()
    }

    override fun subfolder(name: String): BackupFolder? {
        val child = File(directory, name)
        return if (child.isDirectory || child.mkdirs()) FileBackupFolder(child) else null
    }
}
