package com.ledgerflow.core.data.ingest

import com.ledgerflow.core.data.backup.BackupFolderResolver
import com.ledgerflow.core.data.vault.VaultSession
import com.ledgerflow.core.database.LedgerFlowDatabase
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Removes receipts' sealed copies from the backup folder when the receipts
 * themselves are destroyed: by Delete on the Receipts page (BUG88) and by a
 * purge of the bin (BUG90). Google Drive's copies follow on Drive's next pass,
 * which keeps a sidecar only while the folder has it or a row names it
 * (ADR-0035 §4), and neither is then true.
 *
 * Best effort, after the rows are gone: a folder that cannot be reached keeps
 * its copies, and the caller can say so. Never a reason to undo the delete.
 */
@Singleton
public class ReceiptBackupFolder @Inject constructor(
    private val folders: BackupFolderResolver,
) {

    /**
     * Deletes `attachments/<id>.lfba` for each of [ids].
     *
     * @return how many were there and are now gone, or null when the folder
     *   cannot be reached (no folder chosen, its grant gone, or it was removed).
     */
    internal suspend fun deleteCopies(database: LedgerFlowDatabase, ids: Collection<String>): Int? {
        if (ids.isEmpty()) return 0
        val folder = runCatching {
            database.appMetaDao().value(VaultSession.KEY_BACKUP_TREE_URI)
                ?.let(folders::resolve)
                ?.takeIf { it.displayName() != null }
                ?.subfolder(AttachmentBackup.IMAGES_DIRECTORY)
        }.getOrNull() ?: return null
        return runCatching { folder.deleteEach(ids.map { "$it.${AttachmentBackup.EXTENSION}" }) }.getOrDefault(0)
    }

    public companion object {
        /** No folder anywhere: for callers built by hand in tests. */
        public val NONE: ReceiptBackupFolder = ReceiptBackupFolder { null }
    }
}
