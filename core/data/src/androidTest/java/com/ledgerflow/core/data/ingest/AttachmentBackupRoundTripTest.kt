package com.ledgerflow.core.data.ingest

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import com.ledgerflow.core.crypto.bip39.Bip39
import com.ledgerflow.core.crypto.lfbk.LfbaContainer
import com.ledgerflow.core.data.backup.FileBackupFolder
import com.ledgerflow.core.data.ledger.LedgerTestVault
import com.ledgerflow.core.database.backup.BackupResult
import com.ledgerflow.core.database.backup.DatabaseBackupManager
import com.ledgerflow.core.database.backup.RestoreResult
import com.ledgerflow.core.domain.ingest.AttachmentOutcome
import java.io.File
import java.security.SecureRandom
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * ADR-0023's image copy beside the `.lfbk`, end to end on a real vault.
 *
 * **The scenario throughout is the one the feature exists for**: a phone is
 * lost, and a new install restores from the 24 words and a backup folder. So
 * the restore runs against a *second* vault with its own DEK — the case where
 * copying the locally sealed file instead of re-sealing it would produce files
 * that can never be opened again, and where a test reusing one vault would
 * pass regardless.
 *
 * Instrumented because every property here is a property of the real thing: a
 * real DEK, a real derived key, real files under a real `filesDir`.
 */
@RunWith(AndroidJUnit4::class)
class AttachmentBackupRoundTripTest {

    private lateinit var vault: LedgerTestVault
    private lateinit var backupFolder: File

    /** Three distinguishable "receipts", so a swapped file is visible. */
    private val images = List(3) { index ->
        ByteArray(3_000 + index) { ((it + index * 7) % 251).toByte() }
    }

    private val seed: ByteArray get() = Bip39.toSeed(vault.mnemonic)

    private val otherSeed: ByteArray =
        Bip39.toSeed(Bip39.fromEntropy(ByteArray(Bip39.ENTROPY_BYTES) { 42 }))

    @Before
    fun setUp() = runTest {
        vault = LedgerTestVault("attachment-backup-test").apply { open() }
        backupFolder = File(vault.context.filesDir, "backup-tree-test").apply {
            deleteRecursively()
            mkdirs()
        }
    }

    @After
    fun tearDown() {
        vault.close()
        backupFolder.deleteRecursively()
    }

    private suspend fun storeAll(): List<String> = images.map { bytes ->
        val stored = vault.attachments.store(bytes, "image/jpeg")
        assertThat(stored).isInstanceOf(AttachmentOutcome.Stored::class.java)
        (stored as AttachmentOutcome.Stored).attachmentId
    }

    private fun backup() = AttachmentBackup(vault.attachmentFiles, vault.session, Dispatchers.IO)

    private fun sealedFiles(): List<File> =
        File(backupFolder, AttachmentBackup.IMAGES_DIRECTORY).listFiles().orEmpty()
            .filter { it.isFile }
            .sortedBy { it.name }

    /**
     * **The whole feature, in one test.** Back up rows *and* images, destroy
     * the device's copy of both, restore onto a fresh vault from the phrase and
     * the folder, and get every image back byte for byte.
     *
     * The `.lfbk` half is `BackupRestoreRoundTripTest`'s subject and is used
     * here only to put the `attachment` rows back — the rows are what say which
     * images to look for and what each must hash to.
     */
    @Test
    fun backup_wipe_restoreOntoAFreshVault_returnsEveryImage() = runTest {
        val ids = storeAll()
        val lfbk = File(backupFolder, "ledgerflow.lfbk")
        val written = DatabaseBackupManager(vault.database).writeBackup(lfbk, seed)
        assertThat(written).isInstanceOf(BackupResult.Success::class.java)
        assertThat(backup().writeAll(FileBackupFolder(backupFolder), seed))
            .isEqualTo(AttachmentBackupReport(written = 3))

        // A new device: new vault, new DEK, new local key, nothing on disk.
        val phraseOfTheLostPhone = vault.mnemonic
        vault.close()
        vault.open(phrase = Bip39.generate(SecureRandom()))
        val recoveredSeed = Bip39.toSeed(phraseOfTheLostPhone)

        val rows = DatabaseBackupManager(vault.database).restore(lfbk, recoveredSeed)
        assertThat(rows).isInstanceOf(RestoreResult.Success::class.java)
        val report = backup().restoreAll(FileBackupFolder(backupFolder), recoveredSeed)

        assertThat(report).isEqualTo(AttachmentRestoreReport(restored = 3))
        ids.forEachIndexed { index, id ->
            assertWithMessage("image $index").that(vault.attachments.read(id))
                .isEqualTo(images[index])
        }
    }

    /**
     * ADR-0023's honest-degradation promise, as a number.
     *
     * A user who copies the `.lfbk` alone loses the images — that is the risk
     * the decision takes — so the requirement is that the restore *says so*
     * rather than crashing or showing a blank.
     */
    @Test
    fun restore_withNoImagesBesideTheBackup_reportsTheCount() = runTest {
        val ids = storeAll()
        val lfbk = File(backupFolder, "ledgerflow.lfbk")
        DatabaseBackupManager(vault.database).writeBackup(lfbk, seed)
        // Deliberately no writeAll: the user moved the .lfbk on its own.

        val phrase = vault.mnemonic
        vault.close()
        vault.open(phrase = Bip39.generate(SecureRandom()))
        DatabaseBackupManager(vault.database).restore(lfbk, Bip39.toSeed(phrase))

        val report = backup().restoreAll(FileBackupFolder(backupFolder), Bip39.toSeed(phrase))

        assertThat(report).isEqualTo(AttachmentRestoreReport(notFound = 3))
        // And the rows are still there, reading as "no image" rather than
        // crashing every surface that draws one.
        ids.forEach { id ->
            assertThat(vault.database.attachmentDao().byId(id)).isNotNull()
            assertThat(vault.attachments.read(id)).isNull()
        }
    }

    /** "A night with no new receipts writes nothing" (ADR-0023). */
    @Test
    fun backup_secondPass_writesNothingAndLeavesTheFilesAlone() = runTest {
        storeAll()
        assertThat(backup().writeAll(FileBackupFolder(backupFolder), seed).written).isEqualTo(3)
        val before = sealedFiles().map { it.name to it.readBytes().toList() }

        val second = backup().writeAll(FileBackupFolder(backupFolder), seed)

        assertThat(second).isEqualTo(AttachmentBackupReport(alreadyCurrent = 3))
        assertThat(sealedFiles().map { it.name to it.readBytes().toList() }).isEqualTo(before)
    }

    /**
     * **Why "already there" is judged by the phrase, not by the filesystem.**
     * After a rotation the folder is full of files sealed under words the user
     * no longer has; skipping them would leave a backup that quietly cannot be
     * restored.
     */
    @Test
    fun backup_afterThePhraseChanges_resealsTheFolder() = runTest {
        storeAll()
        backup().writeAll(FileBackupFolder(backupFolder), seed)

        val rewritten = backup().writeAll(FileBackupFolder(backupFolder), otherSeed)

        assertThat(rewritten).isEqualTo(AttachmentBackupReport(written = 3))
        // And the folder now opens with the new phrase only.
        assertThat(backup().writeAll(FileBackupFolder(backupFolder), otherSeed).alreadyCurrent).isEqualTo(3)
    }

    /** The bytes in the folder are neither the image nor the local file. */
    @Test
    fun theSealedCopies_areNeitherThePlaintextNorTheLocalFile() = runTest {
        storeAll()
        backup().writeAll(FileBackupFolder(backupFolder), seed)

        val local = vault.attachmentFiles.all().map { it.readBytes().toList() }
        sealedFiles().forEachIndexed { index, file ->
            val bytes = file.readBytes()
            assertWithMessage("copy $index").that(bytes.toList()).isNotIn(local)
            assertThat(images.any { image -> bytes.toList().windowed(64).any { it == image.take(64) } })
                .isFalse()
        }
    }

    /**
     * **The file-swap case.** Renaming two sealed copies past each other must
     * not restore one receipt onto another's entry.
     *
     * **Two independent guards stop it, and a mutation sweep said which is
     * which**: removing the attachment id from the authenticated header
     * entirely leaves this test green, because the row's `sha256` refuses the
     * image anyway. The id binding fails *earlier* and reports *why* — see
     * `LfbaContainerTest.aFileBelongingToAnotherAttachment_isRefusedByName`,
     * which is what actually pins it. This test pins the outcome the user
     * cares about: no receipt lands on the wrong entry.
     *
     * The local images are deleted first, so the restore actually reaches for
     * the folder: with them present every row reports `alreadyPresent` and the
     * swap would never be tested.
     */
    @Test
    fun restore_withTwoFilesRenamedOntoEachOther_refusesThemAndRestoresTheRest() = runTest {
        val ids = storeAll()
        backup().writeAll(FileBackupFolder(backupFolder), seed)
        vault.attachmentFiles.all().forEach { assertThat(it.delete()).isTrue() }

        val files = sealedFiles()
        val parked = File(files[0].parentFile, "parked")
        assertThat(files[0].renameTo(parked)).isTrue()
        assertThat(files[1].renameTo(files[0])).isTrue()
        assertThat(parked.renameTo(files[1])).isTrue()

        val report = backup().restoreAll(FileBackupFolder(backupFolder), seed)

        // The swapped pair is refused; the untouched third image comes back.
        assertThat(report).isEqualTo(AttachmentRestoreReport(restored = 1, unreadable = 2))
        val recovered = ids.map { vault.attachments.read(it) }
        assertThat(recovered.count { it != null }).isEqualTo(1)
        // And nothing was restored onto the wrong entry: whatever came back is
        // the image its own row describes.
        ids.forEachIndexed { index, id ->
            vault.attachments.read(id)?.let { bytes ->
                assertWithMessage("row $index").that(bytes).isEqualTo(images[index])
            }
        }
    }

    /** The wrong words recover nothing, and say so as "unreadable", not "absent". */
    @Test
    fun restore_withTheWrongPhrase_recoversNothing() = runTest {
        storeAll()
        backup().writeAll(FileBackupFolder(backupFolder), seed)
        vault.attachmentFiles.all().forEach { assertThat(it.delete()).isTrue() }

        val report = backup().restoreAll(FileBackupFolder(backupFolder), otherSeed)

        assertThat(report).isEqualTo(AttachmentRestoreReport(unreadable = 3))
    }

    /**
     * A local file that no longer matches its row is **not** copied into the
     * backup: the one place meant to survive a corruption must not be handed
     * one.
     */
    @Test
    fun backup_aLocalFileThatDisagreesWithItsRow_isNotCopied() = runTest {
        storeAll()
        val damaged = vault.attachmentFiles.all().first()
        damaged.writeBytes(damaged.readBytes().also { it[it.size - 1] = (it[it.size - 1] + 1).toByte() })

        val report = backup().writeAll(FileBackupFolder(backupFolder), seed)

        assertThat(report).isEqualTo(AttachmentBackupReport(written = 2, unreadableLocally = 1))
        assertThat(sealedFiles()).hasSize(2)
    }

    /**
     * **A local file swapped for a different, perfectly valid image is not
     * copied.** The corrupt-file case above never reaches this rule — a flipped
     * byte fails the GCM tag first — so without this test the `sha256` check
     * before copying is an unexercised branch, which a mutation sweep proved by
     * deleting it and reddening nothing.
     *
     * The impostor is sealed with this vault's own local key, so it opens
     * cleanly. Only the row's hash says it is not the image it claims to be.
     */
    @Test
    fun backup_aLocalFileReplacedByAnotherValidImage_isNotCopied() = runTest {
        val ids = storeAll()
        val key = requireNotNull(vault.session.attachmentKeyOrNull())
        val row = requireNotNull(vault.database.attachmentDao().byId(ids[0]))
        val impostor = ByteArray(2_500) { (it % 97).toByte() }
        vault.attachmentFiles.resolve(row.filePath)
            .writeBytes(LocalAttachmentSeal.seal(key, impostor))

        val report = backup().writeAll(FileBackupFolder(backupFolder), seed)

        assertThat(report).isEqualTo(AttachmentBackupReport(written = 2, unreadableLocally = 1))
        assertThat(sealedFiles().map { it.name }).doesNotContain("${ids[0]}.${AttachmentBackup.EXTENSION}")
    }

    /**
     * **The mirror on the restore side**: a copy that authenticates under the
     * right phrase *and* the right attachment id, whose content is not the
     * image the row describes. Also an unexercised branch until this test —
     * every other failure path in the folder is caught by the tag or the id.
     *
     * Reachable in practice by a stale copy left behind if an id were ever
     * reused, and it is the last check between the folder and a receipt shown
     * against the wrong entry.
     */
    @Test
    fun restore_aCopyWhoseContentDoesNotMatchTheRow_isRefused() = runTest {
        val ids = storeAll()
        backup().writeAll(FileBackupFolder(backupFolder), seed)
        vault.attachmentFiles.all().forEach { assertThat(it.delete()).isTrue() }

        val impostor = ByteArray(2_500) { (it % 89).toByte() }
        File(
            File(backupFolder, AttachmentBackup.IMAGES_DIRECTORY),
            "${ids[0]}.${AttachmentBackup.EXTENSION}",
        ).writeBytes(LfbaContainer.write(impostor, seed, ids[0]))

        val report = backup().restoreAll(FileBackupFolder(backupFolder), seed)

        assertThat(report).isEqualTo(AttachmentRestoreReport(restored = 2, unreadable = 1))
        assertThat(vault.attachments.read(ids[0])).isNull()
    }

    /** An image already on the device is left alone rather than rewritten. */
    @Test
    fun restore_whenTheImageIsAlreadyPresent_doesNothing() = runTest {
        storeAll()
        backup().writeAll(FileBackupFolder(backupFolder), seed)

        val report = backup().restoreAll(FileBackupFolder(backupFolder), seed)

        assertThat(report).isEqualTo(AttachmentRestoreReport(alreadyPresent = 3))
    }

    /**
     * A damaged copy in the folder is recovered from nothing — but the local
     * image, if still present, is untouched. The restore's job is to fill gaps,
     * never to overwrite a good file with a bad one.
     */
    @Test
    fun restore_withADamagedCopy_leavesAGoodLocalImageAlone() = runTest {
        val ids = storeAll()
        backup().writeAll(FileBackupFolder(backupFolder), seed)
        val corrupt = sealedFiles().first()
        corrupt.writeBytes(corrupt.readBytes().also { it[it.size - 1] = (it[it.size - 1] + 1).toByte() })

        val report = backup().restoreAll(FileBackupFolder(backupFolder), seed)

        assertThat(report).isEqualTo(AttachmentRestoreReport(alreadyPresent = 3))
        ids.forEachIndexed { index, id ->
            assertThat(vault.attachments.read(id)).isEqualTo(images[index])
        }
    }
}
