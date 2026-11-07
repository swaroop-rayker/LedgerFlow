package com.ledgerflow.core.domain.drive

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import org.junit.Test

/**
 * Restore from Drive's two steps (ADR-0034 §7): listing finds and never
 * creates; downloading checks every file against Drive's MD5 and leaves the
 * staging folder empty after any failure, so a half-downloaded backup can
 * never be restored.
 */
class DriveRestoreUseCasesTest {

    private val access = object : DriveAccess {
        var granted = true
        override suspend fun token(account: String?): DriveToken =
            if (granted) DriveToken.Granted("t") else DriveToken.NeedsSignIn
        override suspend fun forget(token: String) = Unit
    }

    /** "LedgerFlow backups" with two backups and a receipt, plus whatever a test breaks. */
    private inner class Drive : DriveFiles {
        val made = mutableListOf<String>()
        var hasFolder = true
        var badChecksum: String? = null
        var failDownload: String? = null
        val files = mapOf(
            "b1" to RemoteFile("b1", "ledgerflow-20261106-030100.lfbk", md5(byteArrayOf(1)), 1),
            "b2" to RemoteFile("b2", "ledgerflow-20261107-030200.lfbk", md5(byteArrayOf(2)), 1),
            "x" to RemoteFile("x", "notes.txt", null, 1),
            "r1" to RemoteFile("r1", "r1.lfba", md5(byteArrayOf(3)), 1),
        )
        val bytes = mapOf("b1" to byteArrayOf(1), "b2" to byteArrayOf(2), "r1" to byteArrayOf(3))

        override suspend fun folder(token: String, name: String, parentId: String?): DriveCall<String> {
            made += name
            return DriveCall.Ok(name)
        }

        override suspend fun findFolder(token: String, name: String, parentId: String?): DriveCall<String?> =
            DriveCall.Ok(if (hasFolder) name else null)

        override suspend fun list(token: String, folderId: String): DriveCall<List<RemoteFile>> = DriveCall.Ok(
            if (folderId == LocalBackupFiles.ATTACHMENTS) {
                listOf(files.getValue("r1"))
            } else {
                files.values.filterNot { it.id == "r1" }
            },
        )

        override suspend fun upload(token: String, folderId: String, name: String, bytes: ByteArray) =
            DriveCall.Failed(retryable = false, reason = "unused")

        override suspend fun delete(token: String, fileId: String): DriveCall<Unit> = DriveCall.Ok(Unit)

        override suspend fun download(token: String, fileId: String): DriveCall<ByteArray> = when (fileId) {
            failDownload -> DriveCall.Failed(retryable = true, reason = "HTTP 503")
            badChecksum -> DriveCall.Ok(byteArrayOf(9))
            else -> DriveCall.Ok(bytes.getValue(fileId))
        }
    }

    private val staging = object : DriveRestoreStaging {
        val files = mutableMapOf<LocalBackupFile, ByteArray>()
        override suspend fun clear() = files.clear()
        override suspend fun write(file: LocalBackupFile, bytes: ByteArray): Boolean {
            files[file] = bytes
            return true
        }
    }

    @Test
    fun listing_findsBackupsNewestFirst_andTheirReceipts_andCreatesNothing() = runBlocking<Unit> {
        val drive = Drive()

        val listing = ListDriveBackupsUseCase(access, drive)("me@example.com") as DriveBackupListing.Found

        assertThat(listing.backups.map { it.name })
            .containsExactly("ledgerflow-20261107-030200.lfbk", "ledgerflow-20261106-030100.lfbk").inOrder()
        assertThat(listing.receipts.map { it.name }).containsExactly("r1.lfba")
        assertThat(drive.made).isEmpty()
    }

    @Test
    fun listing_anAccountWithoutTheFolder_hasNothing_andGetsNoFolder() = runBlocking<Unit> {
        val drive = Drive().apply { hasFolder = false }

        assertThat(ListDriveBackupsUseCase(access, drive)("me@example.com"))
            .isEqualTo(DriveBackupListing.NothingThere)
        assertThat(drive.made).isEmpty()
    }

    @Test
    fun listing_withoutConsent_needsSignIn() = runBlocking<Unit> {
        access.granted = false

        assertThat(ListDriveBackupsUseCase(access, Drive())("me@example.com"))
            .isEqualTo(DriveBackupListing.NeedsSignIn)
    }

    @Test
    fun staging_downloadsTheBackupAndItsReceipts_withProgress() = runBlocking<Unit> {
        val drive = Drive()
        val progress = mutableListOf<Pair<Int, Int>>()

        val staged = StageDriveBackupUseCase(access, drive, staging)(
            "me@example.com",
            drive.files.getValue("b2"),
            listOf(drive.files.getValue("r1")),
        ) { done, total -> progress += done to total }

        assertThat(staged).isEqualTo(DriveStaging.Staged("ledgerflow-20261107-030200.lfbk"))
        assertThat(staging.files.keys).containsExactly(
            LocalBackupFile.Backup("ledgerflow-20261107-030200.lfbk"),
            LocalBackupFile.Attachment("r1.lfba"),
        )
        assertThat(progress).containsExactly(1 to 2, 2 to 2).inOrder()
    }

    /** A file that is not what Drive says it is never reaches a restore. */
    @Test
    fun staging_aChecksumMismatch_failsAndLeavesNothing() = runBlocking<Unit> {
        val drive = Drive().apply { badChecksum = "r1" }

        val staged = StageDriveBackupUseCase(access, drive, staging)(
            "me@example.com",
            drive.files.getValue("b2"),
            listOf(drive.files.getValue("r1")),
        )

        assertThat(staged).isEqualTo(DriveStaging.Failed(retryable = true))
        assertThat(staging.files).isEmpty()
    }

    @Test
    fun staging_aFailedDownloadHalfWay_leavesNothing() = runBlocking<Unit> {
        val drive = Drive().apply { failDownload = "r1" }

        val staged = StageDriveBackupUseCase(access, drive, staging)(
            "me@example.com",
            drive.files.getValue("b2"),
            listOf(drive.files.getValue("r1")),
        )

        assertThat(staged).isEqualTo(DriveStaging.Failed(retryable = true))
        assertThat(staging.files).isEmpty()
    }

    private fun md5(bytes: ByteArray) =
        java.security.MessageDigest.getInstance("MD5").digest(bytes).joinToString("") { "%02x".format(it) }
}
