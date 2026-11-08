package com.ledgerflow.core.domain.drive

import com.google.common.truth.Truth.assertThat
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
    private val receipts = FakeReceipts()
    private val local = FakeLocal(
        backups = mapOf(
            "ledgerflow-20261107-030200.lfbk" to bytes(1),
            "ledgerflow-20261106-030100.lfbk" to bytes(2),
        ),
        attachments = mapOf("r1.lfba" to bytes(3), "r2.lfba" to bytes(4)),
    )
    private val copy = CopyBackupsToDriveUseCase(status, access, drive, local, receipts) { NOW }

    @Test
    fun aPass_uploadsTheNewestBackupAndEveryMissingReceipt_andRecordsIt() = runBlocking<Unit> {
        drive.put(drive.receipts(), "r1.lfba", bytes(3))

        val outcome = copy()

        assertThat(outcome).isEqualTo(DriveOutcome.Copied(backups = 1, attachments = 1, bytes = 2, removed = 0))
        assertThat(drive.names(drive.root())).containsExactly("ledgerflow-20261107-030200.lfbk")
        assertThat(drive.names(drive.receipts())).containsExactly("r1.lfba", "r2.lfba")
        // Byte for byte, as sealed on the phone.
        assertThat(drive.content(drive.root(), "ledgerflow-20261107-030200.lfbk")).isEqualTo(bytes(1))
        assertThat(status.recorded.single()).isEqualTo(DriveAttempt(NOW, outcome))
    }

    /** The account the user chose when turning on, not whichever one Play services finds. */
    @Test
    fun theTokenIsAskedForTheChosenAccount() = runBlocking<Unit> {
        status.chosen = "me@example.com"

        copy()

        assertThat(access.askedFor.toSet()).containsExactly("me@example.com")
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

    /**
     * ADR-0035 §4: a receipt removed from the phone, after the user picked a
     * new backup folder, is in no folder; Drive's copy is its last and stays
     * while its row names it. One no row names still goes.
     */
    @Test
    fun rotation_keepsAReceiptsCopy_whileItsRowNamesIt_evenIfTheFolderLacksIt() = runBlocking<Unit> {
        receipts.rows["removed"] = com.ledgerflow.core.domain.receipts.ReceiptListItem(
            id = "removed",
            capturedAt = 0,
            bytes = 1,
            filing = com.ledgerflow.core.domain.receipts.ReceiptFiling.NotFiled,
            removedFromPhoneAt = 1,
        )
        drive.put(drive.receipts(), "removed.lfba", bytes(7))
        drive.put(drive.receipts(), "deleted.lfba", bytes(8))

        copy()

        assertThat(drive.names(drive.receipts())).containsExactly("r1.lfba", "r2.lfba", "removed.lfba")
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

    private companion object {
        const val NOW = 1_794_000_000_000L

        fun bytes(seed: Int) = byteArrayOf(seed.toByte())
    }
}
