package com.ledgerflow.core.data.drive

import android.content.Context
import com.ledgerflow.core.common.di.IoDispatcher
import com.ledgerflow.core.data.backup.BackupFolder
import com.ledgerflow.core.data.backup.FileBackupFolder
import com.ledgerflow.core.domain.drive.DriveRestoreStaging
import com.ledgerflow.core.domain.drive.LocalBackupFile
import com.ledgerflow.core.domain.drive.LocalBackupFiles
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext

/**
 * Where a backup downloaded from Google Drive waits to be restored (ADR-0034
 * §7): `filesDir/drive-restore/`, with its receipts in `attachments/`, the
 * same shape as a backup folder, so the existing restore reads it unchanged.
 *
 * In `filesDir`, never `cacheDir` (Law 5): Android may clear a cache mid-restore.
 * What it holds is ciphertext, sealed on the phone that made it. It is emptied
 * before each download and once the restore is done or left.
 */
@Singleton
public class DriveRestoreFolder internal constructor(
    locate: () -> File,
    private val io: CoroutineDispatcher,
) : DriveRestoreStaging {

    /**
     * Found on first use, not at construction (BUG86): Hilt builds this on the
     * main thread with the restore screen, and `filesDir` is a disk read that
     * StrictMode's `penaltyDeath` turns into a crash. Every use is on [io].
     */
    private val directory: File by lazy(locate)

    @Inject
    public constructor(
        @ApplicationContext context: Context,
        @IoDispatcher io: CoroutineDispatcher,
    ) : this({ File(context.filesDir, DIRECTORY) }, io)

    /** The staging folder as a backup folder, for the restore to read. Call it on [io]. */
    public fun folder(): BackupFolder {
        directory.mkdirs()
        return FileBackupFolder(directory)
    }

    override suspend fun clear(): Unit = withContext(io) {
        directory.deleteRecursively()
    }

    override suspend fun write(file: LocalBackupFile, bytes: ByteArray): Boolean = withContext(io) {
        val target = when (file) {
            is LocalBackupFile.Backup -> folder()
            is LocalBackupFile.Attachment -> folder().subfolder(LocalBackupFiles.ATTACHMENTS)
        }
        target?.writeVerified(file.name, bytes) { landed -> landed.contentEquals(bytes) } ?: false
    }

    internal companion object {
        const val DIRECTORY = "drive-restore"
    }
}
