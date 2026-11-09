package com.ledgerflow.core.data.ingest

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.ledgerflow.core.crypto.bip39.Bip39
import com.ledgerflow.core.crypto.kem.BackupSealKem
import com.ledgerflow.core.crypto.lfbk.LfbaContainer
import com.ledgerflow.core.data.backup.DefaultBackupRepository
import com.ledgerflow.core.data.backup.FileBackupFolder
import com.ledgerflow.core.database.entity.AppMetaEntity
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * BUG89 (SM-S721B, 2026-11-08): a receipt first backed up by "Back up now" was
 * sealed in the phrase format, re-sealed by the next nightly pass under the same
 * name, and Google Drive kept the old copy; "Remove from phone" could then never
 * match it. Once the install has a nightly key, "Back up now" seals to it, as
 * the nightly pass does, and still opens what it wrote with the words.
 */
@RunWith(AndroidJUnit4::class)
class Bug89_BackUpNowSealsToTheInstallKeyTest {

    private val rig = ReceiptsTestRig("bug89")
    private val seed: ByteArray get() = Bip39.toSeed(rig.vault.mnemonic)
    private val backup get() = AttachmentBackup(rig.vault.attachmentFiles, rig.vault.session, Dispatchers.IO)
    private val folder get() = FileBackupFolder(rig.folder)

    @Before
    fun setUp() = runTest { rig.open() }

    @After
    fun tearDown() = rig.close()

    private fun copyOf(id: String) =
        File(rig.folder, "${AttachmentBackup.IMAGES_DIRECTORY}/$id.${AttachmentBackup.EXTENSION}").readBytes()

    private suspend fun enrol(): ByteArray = BackupSealKem.publicKey(seed).also { key ->
        rig.vault.database.appMetaDao().put(
            AppMetaEntity(DefaultBackupRepository.KEY_SEAL_PUBLIC_KEY, key.joinToString("") { "%02x".format(it) }),
        )
    }

    @Test
    fun onAnEnrolledInstall_backUpNow_sealsToTheInstallKey_soTheNightlyPassRewritesNothing() = runTest {
        val id = rig.storeImage()
        val key = enrol()

        assertThat(backup.writeAll(folder, seed).written).isEqualTo(1)

        assertThat(LfbaContainer.sealedTo(copyOf(id), key)).isTrue()
        val nightly = backup.writeAllSealed(folder, key)
        assertThat(nightly.written).isEqualTo(0)
        assertThat(nightly.alreadyCurrent).isEqualTo(1)
    }

    /** The copy it wrote opens with the words: a restore brings the image back. */
    @Test
    fun theCopyItWrote_restoresWithTheWords() = runTest {
        val id = rig.storeImage()
        enrol()
        backup.writeAll(folder, seed)
        rig.vault.attachmentFiles.all().forEach { it.delete() }

        val report = backup.restoreAll(folder, seed)

        assertThat(report.restored).isEqualTo(1)
        assertThat(rig.receipts.image(id)).isInstanceOf(com.ledgerflow.core.domain.receipts.ReceiptImage.Available::class.java)
    }

    /** An older phrase-format copy is not current: the first such backup re-seals it once, then never again. */
    @Test
    fun anOlderPhraseCopy_isResealedOnce() = runTest {
        val id = rig.storeImage()
        backup.writeAll(folder, seed)
        val key = enrol()
        assertThat(LfbaContainer.sealedTo(copyOf(id), key)).isFalse()

        assertThat(backup.writeAll(folder, seed).written).isEqualTo(1)
        assertThat(LfbaContainer.sealedTo(copyOf(id), key)).isTrue()
        assertThat(backup.writeAll(folder, seed).written).isEqualTo(0)
    }

    /** The folder reports each copy's size, which the Drive copy compares (BUG89's second half). */
    @Test
    fun theFolder_reportsEachCopysSize() = runTest {
        val id = rig.storeImage()
        enrol()
        backup.writeAll(folder, seed)

        val sizes = folder.subfolder(AttachmentBackup.IMAGES_DIRECTORY)?.sizes().orEmpty()

        assertThat(sizes["$id.${AttachmentBackup.EXTENSION}"]).isEqualTo(copyOf(id).size.toLong())
    }
}
