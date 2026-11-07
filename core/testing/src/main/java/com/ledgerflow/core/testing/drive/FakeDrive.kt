package com.ledgerflow.core.testing.drive

import com.ledgerflow.core.domain.drive.DriveAccess
import com.ledgerflow.core.domain.drive.DriveCall
import com.ledgerflow.core.domain.drive.DriveFiles
import com.ledgerflow.core.domain.drive.DriveLayout
import com.ledgerflow.core.domain.drive.DriveRestoreStaging
import com.ledgerflow.core.domain.drive.DriveToken
import com.ledgerflow.core.domain.drive.LocalBackupFile
import com.ledgerflow.core.domain.drive.LocalBackupFiles
import com.ledgerflow.core.domain.drive.RemoteFile
import java.security.MessageDigest

/** A [DriveAccess] that grants a token for any account unless told not to. */
public class FakeDriveAccess(public var granted: Boolean = true) : DriveAccess {
    public val askedFor: MutableList<String?> = mutableListOf()

    override suspend fun token(account: String?): DriveToken {
        askedFor += account
        return if (granted) DriveToken.Granted("token") else DriveToken.NeedsSignIn
    }

    override suspend fun forget(token: String): Unit = Unit
}

/**
 * An in-memory Drive: folders by id, each a map of name to bytes, with real
 * MD5s. [backup] and [receipt] fill "LedgerFlow backups" as Drive backup would.
 */
public class FakeDriveFiles : DriveFiles {
    private val folders = mutableMapOf<String, MutableMap<String, ByteArray>>()
    private val folderIds = mutableMapOf<Pair<String?, String>, String>()
    private var nextId = 0

    /** Puts a backup into "LedgerFlow backups". */
    public fun backup(name: String, bytes: ByteArray) {
        files(folderId(null, DriveLayout.FOLDER))[name] = bytes
    }

    /** Puts a receipt sidecar into "LedgerFlow backups/attachments". */
    public fun receipt(name: String, bytes: ByteArray) {
        files(folderId(folderId(null, DriveLayout.FOLDER), LocalBackupFiles.ATTACHMENTS))[name] = bytes
    }

    private fun folderId(parent: String?, name: String) = folderIds.getOrPut(parent to name) { "folder${nextId++}" }

    private fun files(folder: String) = folders.getOrPut(folder) { mutableMapOf() }

    private fun idOf(folder: String, name: String) = "$folder/$name"

    override suspend fun folder(token: String, name: String, parentId: String?): DriveCall<String> =
        DriveCall.Ok(folderId(parentId, name))

    override suspend fun findFolder(token: String, name: String, parentId: String?): DriveCall<String?> =
        DriveCall.Ok(folderIds[parentId to name])

    override suspend fun list(token: String, folderId: String): DriveCall<List<RemoteFile>> =
        DriveCall.Ok(
            folders[folderId].orEmpty().map { (name, bytes) ->
                RemoteFile(idOf(folderId, name), name, md5(bytes), bytes.size.toLong())
            },
        )

    override suspend fun upload(
        token: String,
        folderId: String,
        name: String,
        bytes: ByteArray,
    ): DriveCall<RemoteFile> {
        files(folderId)[name] = bytes
        return DriveCall.Ok(RemoteFile(idOf(folderId, name), name, md5(bytes), bytes.size.toLong()))
    }

    override suspend fun delete(token: String, fileId: String): DriveCall<Unit> {
        val folder = fileId.substringBeforeLast('/')
        folders[folder]?.remove(fileId.substringAfterLast('/'))
        return DriveCall.Ok(Unit)
    }

    override suspend fun download(token: String, fileId: String): DriveCall<ByteArray> {
        val bytes = folders[fileId.substringBeforeLast('/')]?.get(fileId.substringAfterLast('/'))
        return if (bytes != null) DriveCall.Ok(bytes) else DriveCall.Failed(retryable = false, reason = "HTTP 404")
    }

    private fun md5(bytes: ByteArray) =
        MessageDigest.getInstance("MD5").digest(bytes).joinToString("") { "%02x".format(it) }
}

/** A staging folder in memory. */
public class FakeDriveRestoreStaging : DriveRestoreStaging {
    public val files: MutableMap<LocalBackupFile, ByteArray> = mutableMapOf()
    public var clears: Int = 0

    override suspend fun clear() {
        clears++
        files.clear()
    }

    override suspend fun write(file: LocalBackupFile, bytes: ByteArray): Boolean {
        files[file] = bytes
        return true
    }
}
