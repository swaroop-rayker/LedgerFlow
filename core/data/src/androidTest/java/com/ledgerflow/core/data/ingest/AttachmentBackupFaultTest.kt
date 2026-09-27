package com.ledgerflow.core.data.ingest

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import com.ledgerflow.core.crypto.bip39.Bip39
import com.ledgerflow.core.crypto.kem.BackupSealKem
import com.ledgerflow.core.crypto.lfbk.LfbaContainer
import com.ledgerflow.core.crypto.lfbk.LfbaResult
import com.ledgerflow.core.data.backup.BackupFolder
import com.ledgerflow.core.data.backup.FileBackupFolder
import com.ledgerflow.core.data.ledger.LedgerTestVault
import com.ledgerflow.core.domain.ingest.AttachmentOutcome
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * A receipt image whose copy did not land intact is **never counted as backed
 * up**, and never left behind as though it were (SESSION-LOG-S14 §5 item 7:
 * "`AttachmentBackup`'s post-write verification is unexercised").
 *
 * The fault is storage handing back bytes that differ from the ones written — a
 * flipped byte, a truncated file — injected by [FaultyFolder] between the real
 * `FileBackupFolder`'s write and the verifier. That is the one place a bad copy
 * can be caught: after it, the pass reports `written` and the user believes the
 * receipt is safe. Both writers are held: the phrase path (§7's full
 * decrypt-and-parse) and the nightly path (ADR-0027's bytes-and-header check).
 */
@RunWith(AndroidJUnit4::class)
class AttachmentBackupFaultTest {

    private lateinit var vault: LedgerTestVault
    private lateinit var backupFolder: File
    private lateinit var ids: List<String>

    private val images = List(3) { index -> ByteArray(3_000 + index) { ((it + index * 11) % 251).toByte() } }
    private val seed: ByteArray get() = Bip39.toSeed(vault.mnemonic)

    @Before
    fun setUp() = runTest {
        vault = LedgerTestVault("attachment-fault-test").apply { open() }
        backupFolder = File(vault.context.filesDir, "backup-fault-test").apply {
            deleteRecursively()
            mkdirs()
        }
        ids = images.map { bytes ->
            (vault.attachments.store(bytes, "image/jpeg") as AttachmentOutcome.Stored).attachmentId
        }
    }

    @After
    fun tearDown() {
        vault.close()
        backupFolder.deleteRecursively()
    }

    private fun backup() = AttachmentBackup(vault.attachmentFiles, vault.session, Dispatchers.IO)

    /** Everything left in the images folder: sealed copies and any stray temp file. */
    private fun leftBehind(): List<String> =
        File(backupFolder, AttachmentBackup.IMAGES_DIRECTORY).listFiles().orEmpty().map { it.name }.sorted()

    @Test
    fun aFlippedByte_withThePhrase_isFailedNotWritten_andNothingIsLeft() = runTest {
        val report = backup().writeAll(FaultyFolder(FileBackupFolder(backupFolder), ::flipOneByte), seed)

        assertThat(report).isEqualTo(AttachmentBackupReport(failed = 3))
        assertThat(leftBehind()).isEmpty()
    }

    @Test
    fun aTruncatedCopy_withThePhrase_isFailedNotWritten() = runTest {
        val report = backup().writeAll(FaultyFolder(FileBackupFolder(backupFolder), ::truncate), seed)

        assertThat(report).isEqualTo(AttachmentBackupReport(failed = 3))
        assertThat(leftBehind()).isEmpty()
    }

    /** The nightly writer cannot decrypt, so it compares bytes — and still catches it. */
    @Test
    fun aFlippedByte_sealedWithoutThePhrase_isFailedNotWritten() = runTest {
        val publicKey = BackupSealKem.publicKey(seed)

        val report = backup().writeAllSealed(FaultyFolder(FileBackupFolder(backupFolder), ::flipOneByte), publicKey)

        assertThat(report).isEqualTo(AttachmentBackupReport(failed = 3))
        assertThat(leftBehind()).isEmpty()
    }

    /**
     * One bad copy among good ones: the good ones count, the bad one is failed
     * and absent — so the next clean pass writes it rather than trusting a file
     * that is not there — and afterwards every copy opens to its original.
     */
    @Test
    fun oneBadCopy_isRetriedByTheNextPass_andEveryCopyOpens() = runTest {
        val bad = ids[1]
        val damageOne: (String, ByteArray) -> ByteArray = { name, bytes ->
            if (name.startsWith(bad)) flipOneByte(name, bytes) else bytes
        }

        val first = backup().writeAll(FaultyFolder(FileBackupFolder(backupFolder), damageOne), seed)
        assertThat(first).isEqualTo(AttachmentBackupReport(written = 2, failed = 1))
        assertThat(leftBehind().none { it.startsWith(bad) }).isTrue()

        val second = backup().writeAll(FileBackupFolder(backupFolder), seed)
        assertThat(second).isEqualTo(AttachmentBackupReport(written = 1, alreadyCurrent = 2))

        // Every copy in the folder now opens with the phrase to its original.
        ids.forEachIndexed { index, id ->
            val file = File(File(backupFolder, AttachmentBackup.IMAGES_DIRECTORY), "$id.${AttachmentBackup.EXTENSION}")
            val read = LfbaContainer.read(file.readBytes(), seed, id)
            assertWithMessage("copy of image $index").that(read).isInstanceOf(LfbaResult.Success::class.java)
            assertWithMessage("copy of image $index").that((read as LfbaResult.Success).image).isEqualTo(images[index])
        }
    }

    /**
     * A real folder whose storage returns different bytes from those written,
     * as seen by the verifier. The real write, temp file and rename all happen;
     * only what "landed" looks like is changed.
     */
    private class FaultyFolder(
        private val real: BackupFolder,
        private val damage: (String, ByteArray) -> ByteArray,
    ) : BackupFolder by real {
        override fun subfolder(name: String): BackupFolder? = real.subfolder(name)?.let { FaultyFolder(it, damage) }

        override fun writeVerified(name: String, bytes: ByteArray, verify: (ByteArray) -> Boolean): Boolean =
            real.writeVerified(name, bytes) { landed -> verify(damage(name, landed)) }
    }

    private companion object {
        fun flipOneByte(@Suppress("UNUSED_PARAMETER") name: String, bytes: ByteArray): ByteArray =
            bytes.copyOf().also { it[it.size / 2] = (it[it.size / 2].toInt() xor 0x01).toByte() }

        fun truncate(@Suppress("UNUSED_PARAMETER") name: String, bytes: ByteArray): ByteArray =
            bytes.copyOf(bytes.size - 16)
    }
}
