package com.ledgerflow.core.domain.drive

import com.ledgerflow.core.domain.receipts.ReceiptCopies
import com.ledgerflow.core.domain.receipts.ReceiptCount
import com.ledgerflow.core.domain.receipts.ReceiptDeletion
import com.ledgerflow.core.domain.receipts.ReceiptImage
import com.ledgerflow.core.domain.receipts.ReceiptListItem
import com.ledgerflow.core.domain.receipts.ReceiptPeriod
import com.ledgerflow.core.domain.receipts.ReceiptsRepository
import java.security.MessageDigest
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf

// In-memory Drive, Drive status, backup folder and receipts, shared by the
// Drive copy pass's test and Remove from phone's (ADR-0034, ADR-0035).

internal class FakeStatus(enabled: Boolean) : DriveStatusStore {
    var enabledNow = enabled
    var chosen: String? = null
    val recorded = mutableListOf<DriveAttempt>()
    override fun observe(): Flow<DriveStatus> = MutableStateFlow(DriveStatus(enabledNow, null, null, chosen))
    override suspend fun enabled() = enabledNow
    override suspend fun account() = chosen
    override suspend fun setEnabled(enabled: Boolean, account: String?) {
        enabledNow = enabled
        account?.let { chosen = it }
    }
    override suspend fun record(attempt: DriveAttempt) {
        recorded += attempt
    }
}

internal class FakeAccess : DriveAccess {
    var consent = true
    var asked = 0
    val askedFor = mutableListOf<String?>()
    val forgotten = mutableListOf<String>()
    override suspend fun token(account: String?): DriveToken {
        asked++
        askedFor += account
        return if (consent) DriveToken.Granted("t$asked") else DriveToken.NeedsSignIn
    }
    override suspend fun forget(token: String) {
        forgotten += token
    }
}

internal class FakeLocal(val backups: Map<String, ByteArray>, var attachments: Map<String, ByteArray>) :
    LocalBackupFiles {
    var reachable = true
    override suspend fun snapshot() = if (reachable) {
        LocalBackupSet(
            backups.keys.sortedDescending(),
            attachments.keys.sorted(),
            attachments.mapValues { it.value.size.toLong() },
        )
    } else {
        null
    }
    override suspend fun read(file: LocalBackupFile) = when (file) {
        is LocalBackupFile.Backup -> backups[file.name]
        is LocalBackupFile.Attachment -> attachments[file.name]
    }
}

/** Folders by id, each a map of name to (id, bytes). Only what `drive.file` would show. */
internal class FakeDrive : DriveFiles {
    private val folders = mutableMapOf<String, MutableMap<String, Pair<String, ByteArray>>>()
    private val folderIds = mutableMapOf<Pair<String?, String>, String>()
    private var nextId = 0
    var refuse: Set<String> = emptySet()
    var corrupt: String? = null
    var uploads = 0
    var calls = 0

    fun root() = folderId(null, DriveLayout.FOLDER)
    fun receipts() = folderId(root(), LocalBackupFiles.ATTACHMENTS)
    fun names(folder: String) = folders[folder].orEmpty().keys
    fun content(folder: String, name: String) = folders[folder]?.get(name)?.second
    fun put(folder: String, name: String, bytes: ByteArray) {
        folders.getOrPut(folder) { mutableMapOf() }[name] = "id${nextId++}" to bytes
    }

    private fun folderId(parent: String?, name: String) = folderIds.getOrPut(parent to name) { "folder${nextId++}" }

    private fun <T> answer(token: String, value: () -> T): DriveCall<T> {
        calls++
        return if (token in refuse) DriveCall.Unauthorized else DriveCall.Ok(value())
    }

    override suspend fun folder(token: String, name: String, parentId: String?) =
        answer(token) { folderId(parentId, name) }

    override suspend fun findFolder(token: String, name: String, parentId: String?) =
        answer(token) { folderIds[parentId to name] }

    override suspend fun download(token: String, fileId: String) = answer(token) {
        folders.values.flatMap { it.values }.first { it.first == fileId }.second
    }

    override suspend fun list(token: String, folderId: String) = answer(token) {
        folders[folderId].orEmpty().map { (name, file) ->
            RemoteFile(file.first, name, md5(file.second), file.second.size.toLong())
        }
    }

    override suspend fun upload(token: String, folderId: String, name: String, bytes: ByteArray) = answer(token) {
        uploads++
        put(folderId, name, bytes)
        val id = checkNotNull(folders[folderId]?.get(name)).first
        RemoteFile(id, name, if (name == corrupt) "0000" else md5(bytes), bytes.size.toLong())
    }

    override suspend fun delete(token: String, fileId: String) = answer(token) {
        folders.values.forEach { files -> files.entries.removeAll { it.value.first == fileId } }
    }
}


/** Receipts by id, as the use cases ask about them. */
internal class FakeReceipts(vararg initial: ReceiptListItem) : ReceiptsRepository {
    val rows = initial.associateBy { it.id }.toMutableMap()
    val removed = mutableListOf<String>()

    override fun observeReceipts(period: ReceiptPeriod) = flowOf(androidx.paging.PagingData.from(rows.values.toList()))
    override fun observeCount(period: ReceiptPeriod): Flow<ReceiptCount> = flowOf(ReceiptCount(rows.size, 0))
    override suspend fun idsIn(period: ReceiptPeriod) = rows.keys.toList()
    override suspend fun receipt(id: String) = rows[id]
    override suspend fun image(id: String): ReceiptImage = ReceiptImage.Unavailable
    override suspend fun copies() = ReceiptCopies(backupFolder = "LedgerFlow", drive = true)
    override suspend fun delete(ids: Set<String>) = ReceiptDeletion.Nothing
    override suspend fun deleteAll() = ReceiptDeletion.Nothing
    override fun observeRemoved(): Flow<ReceiptCount> = flowOf(ReceiptCount.None)
    override suspend fun filedBefore(before: Long) = rows.values
        .filter {
            it.capturedAt < before && it.removedFromPhoneAt == null &&
                it.filing is com.ledgerflow.core.domain.receipts.ReceiptFiling.Filed
        }
        .map { it.id }

    override suspend fun removeFromPhone(id: String): Boolean {
        val row = rows[id] ?: return false
        rows[id] = row.copy(removedFromPhoneAt = 1L)
        removed += id
        return true
    }
}

internal fun md5(bytes: ByteArray): String =
    MessageDigest.getInstance("MD5").digest(bytes).joinToString("") { "%02x".format(it) }
