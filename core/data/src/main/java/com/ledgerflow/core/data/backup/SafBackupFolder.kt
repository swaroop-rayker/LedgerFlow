package com.ledgerflow.core.data.backup

import android.content.ContentResolver
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import java.io.FileOutputStream
import java.io.SyncFailedException

/**
 * The user's backup folder as the Storage Access Framework hands it over: a
 * tree the user picked at onboarding, whose grant was persisted (§5.9).
 *
 * Written against `DocumentsContract` directly — no `androidx.documentfile`,
 * so no new dependency — and deliberately conservative, because a document
 * provider can be local storage, an SD card or a cloud drive, and they differ:
 *
 * - **`fsync` may be refused.** A cloud provider hands back a pipe, and
 *   `sync()` on it throws `SyncFailedException`. That is not a failed write,
 *   and the check that actually matters — reading the bytes back and
 *   verifying *them* — runs either way.
 * - **Rename may be unsupported.** When it is, the file is written directly
 *   under its final name and verified there instead; the promotion is then not
 *   atomic, and a failed verification deletes it rather than leaving a file a
 *   restore would trust.
 * - **A provider may rename to avoid a collision** (`name (1)`), so an existing
 *   target is removed before the promotion, and every name is re-read rather
 *   than assumed.
 *
 * Every operation catches what a revoked grant or a vanished provider throws
 * and reports failure rather than crashing: a backup that could not be written
 * is a sentence for the user, not a stack trace.
 *
 * **Not covered by an automated test**, and said so: exercising it needs a
 * real tree grant, which only the system picker can give. The logic above it
 * is tested against [FileBackupFolder]; this adapter's check is the manual
 * step in `TESTING.md`.
 */
public class SafBackupFolder(
    private val resolver: ContentResolver,
    private val treeUri: Uri,
    private val documentId: String,
) : BackupFolder {

    public companion object {
        /** The folder at the root of a persisted tree grant. */
        public fun fromTree(resolver: ContentResolver, treeUri: Uri): SafBackupFolder? =
            runCatching { SafBackupFolder(resolver, treeUri, DocumentsContract.getTreeDocumentId(treeUri)) }
                .getOrNull()

        private const val BINARY = "application/octet-stream"
    }

    private val folderUri: Uri get() = DocumentsContract.buildDocumentUriUsingTree(treeUri, documentId)

    private data class Child(val id: String, val name: String, val isDirectory: Boolean)

    private fun children(): List<Child> = runCatching {
        val uri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, documentId)
        val columns = arrayOf(Document.COLUMN_DOCUMENT_ID, Document.COLUMN_DISPLAY_NAME, Document.COLUMN_MIME_TYPE)
        resolver.query(uri, columns, null, null, null)?.use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    add(Child(cursor.getString(0), cursor.getString(1), cursor.getString(2) == Document.MIME_TYPE_DIR))
                }
            }
        }.orEmpty()
    }.getOrDefault(emptyList())

    private fun childUri(name: String, directory: Boolean = false): Uri? =
        children().firstOrNull { it.name == name && it.isDirectory == directory }
            ?.let { DocumentsContract.buildDocumentUriUsingTree(treeUri, it.id) }

    override fun list(): List<String> = children().filterNot { it.isDirectory }.map { it.name }

    override fun exists(name: String): Boolean = childUri(name) != null

    override fun read(name: String): ByteArray? = runCatching {
        childUri(name)?.let { uri -> resolver.openInputStream(uri)?.use { it.readBytes() } }
    }.getOrNull()

    override fun readPrefix(name: String, count: Int): ByteArray? = runCatching {
        childUri(name)?.let { uri ->
            resolver.openInputStream(uri)?.use { stream ->
                val buffer = ByteArray(count)
                var total = 0
                while (total < count) {
                    val read = stream.read(buffer, total, count - total)
                    if (read < 0) break
                    total += read
                }
                buffer.copyOf(total)
            }
        }
    }.getOrNull()

    override fun writeVerified(name: String, bytes: ByteArray, verify: (ByteArray) -> Boolean): Boolean {
        val tempName = name + BackupFolder.TEMP_SUFFIX
        delete(tempName)
        val temp = create(tempName) ?: return false

        val verified = write(temp, bytes) && readBack(temp)?.let(verify) == true
        if (!verified) {
            deleteUri(temp)
            return false
        }
        return promote(temp, name, bytes, verify)
    }

    /**
     * Put a verified temp in place under [name]. Rename when the provider can;
     * otherwise write the final name directly and verify that copy too.
     */
    private fun promote(temp: Uri, name: String, bytes: ByteArray, verify: (ByteArray) -> Boolean): Boolean {
        delete(name)
        val renamed = runCatching { DocumentsContract.renameDocument(resolver, temp, name) }.getOrNull()
        if (renamed != null) return childUri(name) != null

        deleteUri(temp)
        val direct = create(name) ?: return false
        val verified = write(direct, bytes) && readBack(direct)?.let(verify) == true
        if (!verified) deleteUri(direct)
        return verified
    }

    private fun create(name: String): Uri? =
        runCatching { DocumentsContract.createDocument(resolver, folderUri, BINARY, name) }.getOrNull()

    private fun write(uri: Uri, bytes: ByteArray): Boolean = runCatching {
        resolver.openFileDescriptor(uri, "wt")?.use { descriptor ->
            FileOutputStream(descriptor.fileDescriptor).use { stream ->
                stream.write(bytes)
                stream.flush()
                try {
                    descriptor.fileDescriptor.sync()
                } catch (_: SyncFailedException) {
                    // A pipe-backed provider cannot fsync. The read-back below
                    // is the verification that counts.
                }
            }
            true
        } ?: false
    }.getOrDefault(false)

    private fun readBack(uri: Uri): ByteArray? =
        runCatching { resolver.openInputStream(uri)?.use { it.readBytes() } }.getOrNull()

    override fun delete(name: String): Boolean {
        val uri = childUri(name) ?: return true
        return deleteUri(uri)
    }

    private fun deleteUri(uri: Uri): Boolean =
        runCatching { DocumentsContract.deleteDocument(resolver, uri) }.getOrDefault(false)

    override fun subfolder(name: String): BackupFolder? {
        val existing = children().firstOrNull { it.name == name && it.isDirectory }
        val id = existing?.id ?: runCatching {
            DocumentsContract.createDocument(resolver, folderUri, Document.MIME_TYPE_DIR, name)
                ?.let(DocumentsContract::getDocumentId)
        }.getOrNull()
        return id?.let { SafBackupFolder(resolver, treeUri, it) }
    }
}
