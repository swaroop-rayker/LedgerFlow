package com.ledgerflow.core.domain.drive

import com.google.common.truth.Truth.assertThat
import com.ledgerflow.core.domain.receipts.ReceiptBackupCopies
import com.ledgerflow.core.domain.receipts.ReceiptFiling
import com.ledgerflow.core.domain.receipts.ReceiptListItem
import com.ledgerflow.core.domain.receipts.RemovalOutcome
import com.ledgerflow.core.domain.receipts.RemoveReceiptsFromPhoneUseCase
import kotlinx.coroutines.runBlocking
import org.junit.Test

/**
 * "Remove from phone" (ADR-0035 §1, §2): a receipt leaves the phone only when
 * the backup folder holds its copy sealed to this install **and** Drive lists
 * a file of that name with the same MD5, checked in the same call. One test per
 * condition that must stop it.
 */
class RemoveReceiptsFromPhoneUseCaseTest {

    private val status = FakeStatus(enabled = true)
    private val access = FakeAccess()
    private val drive = FakeDrive()
    private val sealed = mutableMapOf<String, ByteArray>()
    private val copies = object : ReceiptBackupCopies {
        override suspend fun sealedCopy(id: String) = sealed[id]
    }
    private val receipts = FakeReceipts(
        receipt("filed", ReceiptFiling.NotFiled),
        receipt("waiting", ReceiptFiling.InInbox("p-1")),
    )
    private val remove = RemoveReceiptsFromPhoneUseCase(status, access, drive, copies, receipts)

    private fun receipt(id: String, filing: ReceiptFiling) =
        ReceiptListItem(id = id, capturedAt = 0, bytes = 412_000, filing = filing)

    /** The folder's sealed copy, and the same bytes on Drive. */
    private fun backedUp(id: String, bytes: ByteArray = byteArrayOf(1, 2, 3)) {
        sealed[id] = bytes
        drive.put(drive.receipts(), "$id.lfba", bytes)
    }

    @Test
    fun aCheckedCopy_removesTheReceipt() = runBlocking<Unit> {
        backedUp("filed")

        val outcome = remove(setOf("filed"))

        assertThat(outcome).isEqualTo(
            RemovalOutcome.Checked(removed = 1, bytes = 412_000, notInDriveYet = 0, inInbox = 0),
        )
        assertThat(receipts.removed).containsExactly("filed")
    }

    @Test
    fun noCopyOnDrive_staysOnThePhone() = runBlocking<Unit> {
        sealed["filed"] = byteArrayOf(1, 2, 3)
        drive.receipts()

        val outcome = remove(setOf("filed")) as RemovalOutcome.Checked

        assertThat(outcome.notInDriveYet).isEqualTo(1)
        assertThat(receipts.removed).isEmpty()
    }

    /** Not yet copied by a nightly pass, or sealed to another install: the folder has no usable copy. */
    @Test
    fun noSealedCopyInTheFolder_staysOnThePhone() = runBlocking<Unit> {
        drive.put(drive.receipts(), "filed.lfba", byteArrayOf(1, 2, 3))

        val outcome = remove(setOf("filed")) as RemovalOutcome.Checked

        assertThat(outcome.notInDriveYet).isEqualTo(1)
        assertThat(receipts.removed).isEmpty()
    }

    @Test
    fun aDriveCopyThatDiffersFromTheFolders_staysOnThePhone() = runBlocking<Unit> {
        sealed["filed"] = byteArrayOf(1, 2, 3)
        drive.put(drive.receipts(), "filed.lfba", byteArrayOf(9, 9, 9))

        val outcome = remove(setOf("filed")) as RemovalOutcome.Checked

        assertThat(outcome.notInDriveYet).isEqualTo(1)
        assertThat(receipts.removed).isEmpty()
    }

    @Test
    fun aReceiptWaitingInTheInbox_isNeverRemoved() = runBlocking<Unit> {
        backedUp("waiting")

        val outcome = remove(setOf("waiting")) as RemovalOutcome.Checked

        assertThat(outcome.inInbox).isEqualTo(1)
        assertThat(receipts.removed).isEmpty()
    }

    @Test
    fun driveOff_checksNothing_andRemovesNothing() = runBlocking<Unit> {
        backedUp("filed")
        status.enabledNow = false

        assertThat(remove(setOf("filed"))).isEqualTo(RemovalOutcome.DriveOff)
        assertThat(access.asked).isEqualTo(0)
        assertThat(receipts.removed).isEmpty()
    }

    @Test
    fun noConsent_needsSignIn_andRemovesNothing() = runBlocking<Unit> {
        backedUp("filed")
        access.consent = false

        assertThat(remove(setOf("filed"))).isEqualTo(RemovalOutcome.NeedsSignIn)
        assertThat(receipts.removed).isEmpty()
    }

    /** A check never creates anything on Drive: with no folder there, nothing is on Drive. */
    @Test
    fun noFolderOnDrive_isNotInDriveYet_andCreatesNoFolder() = runBlocking<Unit> {
        sealed["filed"] = byteArrayOf(1, 2, 3)

        val outcome = remove(setOf("filed")) as RemovalOutcome.Checked

        assertThat(outcome.notInDriveYet).isEqualTo(1)
        assertThat(drive.uploads).isEqualTo(0)
    }

    @Test
    fun aRefusedToken_isRetriedOnce_withAFreshOne() = runBlocking<Unit> {
        backedUp("filed")
        drive.refuse = setOf("t1")

        val outcome = remove(setOf("filed"))

        assertThat(outcome).isInstanceOf(RemovalOutcome.Checked::class.java)
        assertThat(access.forgotten).containsExactly("t1")
    }
}
