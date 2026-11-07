package com.ledgerflow.core.domain.drive

import com.google.common.truth.Truth.assertThat
import java.security.MessageDigest
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Test

/**
 * One pass of copying to Drive (ADR-0034 §5), against an in-memory Drive and
 * backup folder: what goes up, that each upload is checked against Drive's MD5,
 * what a refused token does, how Drive is rotated, and that nothing happens
 * while Drive backup is off.
 */
class CopyBackupsToDriveUseCaseTest {

    private val status = FakeStatus(enabled = true)
    private val access = FakeAccess()
    private val drive = FakeDrive()
    private val local = FakeLocal(
        backups = mapOf(
            "ledgerflow-20261107-030200.lfbk" to bytes(1),
            "ledgerflow-20261106-030100.lfbk" to bytes(2),
        ),
        attachments = mapOf("r1.lfba" to bytes(3), "r2.lfba" to bytes(4)),
    )
    private val copy = CopyBackupsToDriveUseCase(status, access, drive, local) { NOW }

    @Test
    fun aPass_uploadsTheNewestBackupAndEveryMissingReceipt_andRecordsIt() = runBlocking<Unit> {
        drive.put(drive.receipts(), "r1.lfba", bytes(3))

        val outcome = copy()

        assertThat(outcome).isEqualTo(DriveOutcome.Copied(backups = 1, attachments = 1, bytes = 2, removed = 0))
        assertThat(drive.names(drive.root())).containsExactly("ledgerflow-20261107-030200.lfbk")
        assertThat(drive.names(drive.receipts())).containsExactly("r1.lfba", "r2.lfba")
        // Byte for byte, as sealed on the phone.
        assertThat(drive.content(drive.root(), "ledgerflow-20261107-030200.lfbk")).isEqualTo(bytes(1))
        assertThat(status.recorded.single()).isEqualTo(DriveAttempt(NOW, outcome, "me@example.com"))
    }

    @Test
    fun aSecondPass_uploadsNothingNew() = runBlocking<Unit> {
        copy()
        drive.uploads = 0

        assertThat(copy()).isEqualTo(DriveOutcome.Copied(backups = 0, attachments = 0, bytes = 0, removed = 0))
        assertThat(drive.uploads).isEqualTo(0)
    }

    /** What landed must be what was sealed: a mismatch is deleted and retried, never kept. */
    @Test
    fun aChecksumThatDoesNotMatch_isDeleted_andRetried() = runBlocking<Unit> {
        drive.corrupt = "ledgerflow-20261107-030200.lfbk"

        val outcome = copy()

        assertThat((outcome as DriveOutcome.Failed).retryable).isTrue()
        assertThat(drive.names(drive.root())).isEmpty()
    }

    @Test
    fun aRefusedToken_isForgotten_andThePassRunsAgainWithANewOne() = runBlocking<Unit> {
        drive.refuse = setOf("t1")

        val outcome = copy()

        assertThat(outcome).isInstanceOf(DriveOutcome.Copied::class.java)
        assertThat(access.forgotten).containsExactly("t1")
    }

    @Test
    fun aTokenRefusedTwice_needsSignIn() = runBlocking<Unit> {
        drive.refuse = setOf("t1", "t2")

        assertThat(copy()).isEqualTo(DriveOutcome.NeedsSignIn)
    }

    @Test
    fun noConsent_needsSignIn_andTouchesNothing() = runBlocking<Unit> {
        access.consent = false

        assertThat(copy()).isEqualTo(DriveOutcome.NeedsSignIn)
        assertThat(drive.calls).isEqualTo(0)
        assertThat(status.recorded.single().outcome).isEqualTo(DriveOutcome.NeedsSignIn)
    }

    /** Newest five backups; receipts the phone no longer has; never a file this app did not name. */
    @Test
    fun rotation_keepsTheNewestFive_mirrorsReceipts_andLeavesOtherFilesAlone() = runBlocking<Unit> {
        (1..6).forEach { day -> drive.put(drive.root(), "ledgerflow-2026110$day-030000.lfbk", bytes(day)) }
        drive.put(drive.root(), "my-notes.txt", bytes(9))
        drive.put(drive.receipts(), "gone.lfba", bytes(9))

        val outcome = copy() as DriveOutcome.Copied

        assertThat(drive.names(drive.root())).containsExactly(
            "ledgerflow-20261107-030200.lfbk",
            "ledgerflow-20261106-030000.lfbk",
            "ledgerflow-20261105-030000.lfbk",
            "ledgerflow-20261104-030000.lfbk",
            "ledgerflow-20261103-030000.lfbk",
            "my-notes.txt",
        )
        assertThat(drive.names(drive.receipts())).containsExactly("r1.lfba", "r2.lfba")
        assertThat(outcome.removed).isEqualTo(3)
    }

    @Test
    fun whileOff_nothingIsAskedOrRecorded() = runBlocking<Unit> {
        status.enabledNow = false

        assertThat(copy()).isEqualTo(DriveOutcome.Off)
        assertThat(access.asked).isEqualTo(0)
        assertThat(status.recorded).isEmpty()
    }

    @Test
    fun noBackupFolder_hasNothingToCopy() = runBlocking<Unit> {
        local.reachable = false

        assertThat(copy()).isEqualTo(DriveOutcome.NothingToCopy)
        assertThat(drive.calls).isEqualTo(0)
    }

    @Test
    fun anOutcome_survivesItsRecordedForm() {
        val outcomes = listOf(
            DriveOutcome.Copied(1, 2, 3, 0),
            DriveOutcome.NothingToCopy,
            DriveOutcome.NeedsSignIn,
            DriveOutcome.Unavailable(DriveUnavailable.NotConfigured),
            DriveOutcome.Failed(retryable = true, reason = ""),
            DriveOutcome.Failed(retryable = false, reason = ""),
        )
        outcomes.forEach { assertThat(DriveOutcome.parse(it.record())).isEqualTo(it) }
    }

    // ── Fakes ───────────────────────────────────────────────────────────────

    private class FakeStatus(enabled: Boolean) : DriveStatusStore {
        var enabledNow = enabled
        val recorded = mutableListOf<DriveAttempt>()
        override fun observe(): Flow<DriveStatus> = MutableStateFlow(DriveStatus(enabledNow, null, null))
        override suspend fun enabled() = enabledNow
        override suspend fun setEnabled(enabled: Boolean) {
            enabledNow = enabled
        }
        override suspend fun record(attempt: DriveAttempt) {
            recorded += attempt
        }
    }

    private class FakeAccess : DriveAccess {
        var consent = true
        var asked = 0
        val forgotten = mutableListOf<String>()
        override suspend fun token(): DriveToken {
            asked++
            return if (consent) DriveToken.Granted("t$asked", "me@example.com") else DriveToken.NeedsSignIn
        }
        override suspend fun forget(token: String) {
            forgotten += token
        }
    }

    private class FakeLocal(val backups: Map<String, ByteArray>, val attachments: Map<String, ByteArray>) :
        LocalBackupFiles {
        var reachable = true
        override suspend fun snapshot() = if (reachable) {
            LocalBackupSet(backups.keys.sortedDescending(), attachments.keys.sorted())
        } else {
            null
        }
        override suspend fun read(file: LocalBackupFile) = when (file) {
            is LocalBackupFile.Backup -> backups[file.name]
            is LocalBackupFile.Attachment -> attachments[file.name]
        }
    }

    /** Folders by id, each a map of name to (id, bytes). Only what `drive.file` would show. */
    private class FakeDrive : DriveFiles {
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

        override suspend fun list(token: String, folderId: String) = answer(token) {
            folders[folderId].orEmpty().map { (name, file) -> RemoteFile(file.first, name, md5(file.second), null) }
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

    private companion object {
        const val NOW = 1_794_000_000_000L

        fun bytes(seed: Int) = byteArrayOf(seed.toByte())

        fun md5(bytes: ByteArray) =
            MessageDigest.getInstance("MD5").digest(bytes).joinToString("") { "%02x".format(it) }
    }
}
