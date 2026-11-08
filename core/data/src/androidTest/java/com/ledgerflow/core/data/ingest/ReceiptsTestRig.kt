package com.ledgerflow.core.data.ingest

import com.ledgerflow.core.data.backup.BackupFolderResolver
import com.ledgerflow.core.data.backup.FileBackupFolder
import com.ledgerflow.core.data.ledger.LedgerTestVault
import com.ledgerflow.core.data.vault.VaultSession
import com.ledgerflow.core.database.entity.AppMetaEntity
import com.ledgerflow.core.domain.drive.DriveAttempt
import com.ledgerflow.core.domain.drive.DriveStatus
import com.ledgerflow.core.domain.drive.DriveStatusStore
import com.ledgerflow.core.domain.ingest.AttachmentOutcome
import com.ledgerflow.core.domain.ledger.ApprovalRequest
import com.ledgerflow.core.model.EntryAssignment
import com.ledgerflow.core.model.LedgerType
import com.ledgerflow.core.model.Money
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow

/**
 * A real vault, a directory standing in for the user's backup folder, and the
 * Receipts page's repository over them (V2-5).
 */
internal class ReceiptsTestRig(name: String) {

    val vault = LedgerTestVault(name)

    /** The backup folder, as `FileBackupFolder` reads one. */
    val folder: File = File(vault.context.filesDir, "receipts-backup-$name")

    /** False: the folder's grant is gone, as when the user revokes it. */
    var folderReachable = true

    lateinit var receipts: DefaultReceiptsRepository
        private set

    /** The app's resolver, standing in: the folder while it is reachable. */
    val resolver = BackupFolderResolver { uri -> if (uri == TREE && folderReachable) FileBackupFolder(folder) else null }

    suspend fun open() {
        vault.open()
        folder.deleteRecursively()
        folder.mkdirs()
        vault.database.appMetaDao().put(AppMetaEntity(VaultSession.KEY_BACKUP_TREE_URI, TREE))
        receipts = DefaultReceiptsRepository(
            vault.session,
            vault.attachmentFiles,
            vault.attachments,
            resolver,
            DriveOff,
            vault.clock,
            Dispatchers.IO,
        )
    }

    fun close() {
        folder.deleteRecursively()
        vault.close()
    }

    /** A distinct image each call, so content dedupe never merges two. */
    private var seed = 0

    suspend fun storeImage(): String {
        seed++
        val bytes = ByteArray(2_048) { ((it + seed * 7) % 251).toByte() }
        return (vault.attachments.store(bytes, "image/png") as AttachmentOutcome.Stored).attachmentId
    }

    /** As the nightly pass leaves it: `attachments/<id>.lfba` in the folder. Its bytes are not read here. */
    fun writeBackupCopy(id: String): File =
        File(folder, "${AttachmentBackup.IMAGES_DIRECTORY}/$id.${AttachmentBackup.EXTENSION}").apply {
            parentFile?.mkdirs()
            writeBytes(byteArrayOf(1, 2, 3))
        }

    suspend fun approvedEntry(ledger: LedgerType, amount: Long): String = requireNotNull(
        vault.ledger.approve(
            ApprovalRequest(ledger = ledger, amount = Money(amount), occurredAt = 1_700_000_000_000L, assignment = EntryAssignment()),
        ).valueOrNull(),
    ).id

    suspend fun link(attachmentId: String, entryId: String) {
        vault.database.attachmentDao().linkToEntry(attachmentId, entryId)
    }

    private object DriveOff : DriveStatusStore {
        override fun observe(): Flow<DriveStatus> = emptyFlow()
        override suspend fun enabled(): Boolean = false
        override suspend fun account(): String? = null
        override suspend fun setEnabled(enabled: Boolean, account: String?) = Unit
        override suspend fun record(attempt: DriveAttempt) = Unit
    }

    private companion object {
        const val TREE = "content://test/tree/receipts"
    }
}
