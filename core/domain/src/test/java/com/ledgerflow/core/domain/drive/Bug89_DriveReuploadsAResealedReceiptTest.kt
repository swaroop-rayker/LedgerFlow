package com.ledgerflow.core.domain.drive

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import org.junit.Test

/**
 * BUG89 (found on the SM-S721B, 2026-11-08): "Back up now" wrote a receipt's
 * copy in the phrase format; the nightly pass re-sealed it to the install's key
 * under the same name; the Drive copy uploaded only names Drive lacked, so Drive
 * kept the old copy forever, its MD5 never matched the folder's, and "Remove
 * from phone" said "not in Google Drive yet" for that receipt indefinitely.
 *
 * Now a copy whose folder size differs from Drive's is uploaded again, the new
 * one verified before the old one is deleted.
 */
class Bug89_DriveReuploadsAResealedReceiptTest {

    private val status = FakeStatus(enabled = true)
    private val access = FakeAccess()
    private val drive = FakeDrive()
    private val receipts = FakeReceipts()
    private val resealed = ByteArray(PHRASE_SEALED.size + KEM_PARAMS) { 7 }
    private val local = FakeLocal(
        backups = mapOf("ledgerflow-20261108-030000.lfbk" to byteArrayOf(1)),
        attachments = mapOf("r1.lfba" to resealed),
    )
    private val copy = CopyBackupsToDriveUseCase(status, access, drive, local, receipts) { NOW }

    @Test
    fun aReceiptResealedInTheFolder_isUploadedAgain_andDriveMatchesTheFolder() = runBlocking<Unit> {
        drive.put(drive.receipts(), "r1.lfba", PHRASE_SEALED)

        val outcome = copy() as DriveOutcome.Copied

        assertThat(drive.content(drive.receipts(), "r1.lfba")).isEqualTo(resealed)
        assertThat(drive.names(drive.receipts())).containsExactly("r1.lfba")
        assertThat(outcome.attachments).isEqualTo(1)
    }

    /** The same size is the same copy: nothing travels, so a pass stays proportional to new receipts. */
    @Test
    fun aReceiptTheSameSizeOnBothSides_isNotUploadedAgain() = runBlocking<Unit> {
        val sameSize = ByteArray(resealed.size) { 9 }
        drive.put(drive.receipts(), "r1.lfba", sameSize)

        copy()

        assertThat(drive.content(drive.receipts(), "r1.lfba")).isEqualTo(sameSize)
    }

    private companion object {
        const val NOW = 1_794_000_000_000L

        /** A header without the KEM's 65-byte `enc`: what a phrase-sealed copy is short by. */
        val PHRASE_SEALED = ByteArray(40) { 3 }
        const val KEM_PARAMS = 65
    }
}
