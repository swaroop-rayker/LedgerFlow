package com.ledgerflow.core.domain.drive

import java.security.MessageDigest
import javax.inject.Inject

/**
 * Restore from Google Drive (ADR-0034 §7): the backups Drive backup copied
 * there, downloaded byte for byte into a private staging folder, then handed
 * to the existing restore as if it were a backup folder. Nothing here opens a
 * backup: that still takes the 24 words, in the restore itself.
 */
public interface DriveRestoreStaging {

    /** Empties the staging folder: before a download, and once a restore is done or left. */
    public suspend fun clear()

    /** Writes one downloaded file into the staging folder; false if it could not. */
    public suspend fun write(file: LocalBackupFile, bytes: ByteArray): Boolean
}

/** What Drive holds for restoring, as Drive backup left it. */
public sealed interface DriveBackupListing {

    /** The backups, newest first, and the receipt sidecars beside them. */
    public data class Found(val backups: List<RemoteFile>, val receipts: List<RemoteFile>) : DriveBackupListing {
        /** What restoring downloads besides the backup itself. */
        public val receiptBytes: Long get() = receipts.sumOf { it.size ?: 0L }
    }

    /** No "LedgerFlow backups" folder, or no backup in it, that this app can see. */
    public data object NothingThere : DriveBackupListing

    public data object NeedsSignIn : DriveBackupListing

    public data class Unavailable(val reason: DriveUnavailable) : DriveBackupListing

    public data class Failed(val retryable: Boolean) : DriveBackupListing
}

/** What downloading a backup for restore came to. */
public sealed interface DriveStaging {

    /** The backup and its receipts are in the staging folder, verified; restore [fileName]. */
    public data class Staged(val fileName: String) : DriveStaging

    public data object NeedsSignIn : DriveStaging

    /** A download failed or did not match Drive's checksum. Nothing was restored. */
    public data class Failed(val retryable: Boolean) : DriveStaging
}

/**
 * The backups in the user's "LedgerFlow backups" folder, for [account] (the one
 * chosen in Android's account picker). Only finds: never creates a folder, so
 * looking at Drive from a new phone leaves nothing behind.
 */
public class ListDriveBackupsUseCase @Inject constructor(
    private val access: DriveAccess,
    private val drive: DriveFiles,
) {

    public suspend operator fun invoke(account: String): DriveBackupListing =
        when (val answer = access.token(account)) {
            is DriveToken.Granted -> listWith(answer.accessToken)
            DriveToken.NeedsSignIn -> DriveBackupListing.NeedsSignIn
            is DriveToken.Unavailable -> DriveBackupListing.Unavailable(answer.reason)
        }

    private suspend fun listWith(token: String): DriveBackupListing =
        when (val found = drive.findFolder(token, DriveLayout.FOLDER, null)) {
            is DriveCall.Ok -> found.value?.let { root -> backupsIn(token, root) } ?: DriveBackupListing.NothingThere
            else -> found.asListingFailure()
        }

    private suspend fun backupsIn(token: String, root: String): DriveBackupListing =
        when (val listed = drive.list(token, root)) {
            is DriveCall.Ok -> listed.value
                .filter { DriveLayout.BACKUP_NAME.matches(it.name) }
                .sortedByDescending { it.name }
                .takeIf { it.isNotEmpty() }
                ?.let { DriveBackupListing.Found(it, receiptsIn(token, root)) }
                ?: DriveBackupListing.NothingThere
            else -> listed.asListingFailure()
        }

    /** The sidecars, or none: a backup restores without its receipts rather than not at all. */
    private suspend fun receiptsIn(token: String, root: String): List<RemoteFile> {
        val folder = (drive.findFolder(token, LocalBackupFiles.ATTACHMENTS, root) as? DriveCall.Ok)?.value
            ?: return emptyList()
        val listed = drive.list(token, folder) as? DriveCall.Ok ?: return emptyList()
        return listed.value.filter { DriveLayout.ATTACHMENT_NAME.matches(it.name) }
    }
}

/**
 * Downloads [backup] and [receipts] into the staging folder, each checked
 * against Drive's MD5, after emptying it. Any failure leaves the folder empty
 * again, so a half-downloaded backup can never be restored.
 */
public class StageDriveBackupUseCase @Inject constructor(
    private val access: DriveAccess,
    private val drive: DriveFiles,
    private val staging: DriveRestoreStaging,
) {

    /** @param onProgress files downloaded so far, of the total (the backup counts as one). */
    public suspend operator fun invoke(
        account: String,
        backup: RemoteFile,
        receipts: List<RemoteFile>,
        onProgress: (done: Int, total: Int) -> Unit = { _, _ -> },
    ): DriveStaging {
        val token = (access.token(account) as? DriveToken.Granted)?.accessToken ?: return DriveStaging.NeedsSignIn
        staging.clear()
        val wanted = listOf(backup to LocalBackupFile.Backup(backup.name)) +
            receipts.map { it to LocalBackupFile.Attachment(it.name) }
        wanted.forEachIndexed { index, (remote, local) ->
            fetch(token, remote, local)?.let { failure ->
                staging.clear()
                return failure
            }
            onProgress(index + 1, wanted.size)
        }
        return DriveStaging.Staged(backup.name)
    }

    /** One file into staging, or why not. */
    private suspend fun fetch(token: String, remote: RemoteFile, local: LocalBackupFile): DriveStaging? {
        val bytes = when (val got = drive.download(token, remote.id)) {
            is DriveCall.Ok -> got.value
            DriveCall.Unauthorized -> return DriveStaging.NeedsSignIn
            is DriveCall.Failed -> return DriveStaging.Failed(got.retryable)
        }
        val matches = remote.md5 == null || remote.md5.equals(md5Of(bytes), ignoreCase = true)
        return if (matches && staging.write(local, bytes)) null else DriveStaging.Failed(retryable = true)
    }

    private fun md5Of(bytes: ByteArray): String =
        MessageDigest.getInstance("MD5").digest(bytes).joinToString("") { "%02x".format(it) }
}

/** Empties the staging folder when the restore screen is left without restoring. */
public class ClearDriveRestoreStagingUseCase @Inject constructor(private val staging: DriveRestoreStaging) {
    public suspend operator fun invoke(): Unit = staging.clear()
}

private fun DriveCall<*>.asListingFailure(): DriveBackupListing = when (this) {
    DriveCall.Unauthorized -> DriveBackupListing.NeedsSignIn
    is DriveCall.Failed -> DriveBackupListing.Failed(retryable)
    is DriveCall.Ok -> DriveBackupListing.Failed(retryable = false)
}
