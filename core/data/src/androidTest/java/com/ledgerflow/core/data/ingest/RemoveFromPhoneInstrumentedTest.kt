package com.ledgerflow.core.data.ingest

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.ledgerflow.core.crypto.bip39.Bip39
import com.ledgerflow.core.crypto.kem.BackupSealKem
import com.ledgerflow.core.crypto.lfbk.LfbaContainer
import com.ledgerflow.core.data.backup.DefaultBackupRepository
import com.ledgerflow.core.data.backup.FileBackupFolder
import com.ledgerflow.core.database.entity.AppMetaEntity
import com.ledgerflow.core.domain.receipts.ReceiptImage
import java.io.File
import java.security.SecureRandom
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Removing a receipt from the phone against a real vault and folder (ADR-0035):
 * the row stays and the file goes; an Inbox candidate's image is refused; the
 * backup folder's copy counts only when sealed to this install; a restore brings
 * a removed receipt back and clears the mark; the nightly pass expects it.
 */
@RunWith(AndroidJUnit4::class)
class RemoveFromPhoneInstrumentedTest {

    private val rig = ReceiptsTestRig("remove-from-phone")

    private val seed: ByteArray get() = Bip39.toSeed(rig.vault.mnemonic)
    private val backup get() = AttachmentBackup(rig.vault.attachmentFiles, rig.vault.session, Dispatchers.IO)
    private val copies get() = DefaultReceiptBackupCopies(rig.vault.session, rig.resolver, Dispatchers.IO)

    @Before
    fun setUp() = runTest { rig.open() }

    @After
    fun tearDown() = rig.close()

    @Test
    fun removing_keepsTheRow_unlinksTheFile_andTheImageSaysWhere() = runTest {
        val id = rig.storeImage()

        assertThat(rig.receipts.removeFromPhone(id)).isTrue()

        val row = rig.vault.database.attachmentDao().byId(id)
        assertThat(row).isNotNull()
        assertThat(row?.removedFromPhoneAt).isNotNull()
        assertThat(rig.vault.attachmentFiles.all()).isEmpty()
        assertThat(rig.receipts.image(id)).isInstanceOf(ReceiptImage.RemovedFromPhone::class.java)
        assertThat(rig.receipts.observeRemoved().first().count).isEqualTo(1)
        // A second removal is refused: it is already done.
        assertThat(rig.receipts.removeFromPhone(id)).isFalse()
    }

    @Test
    fun aReceiptWaitingInTheInbox_isNotRemoved() = runTest {
        val id = rig.storeImage()
        rig.vault.seedPendingCandidate(rawRefId = id)

        assertThat(rig.receipts.removeFromPhone(id)).isFalse()

        assertThat(rig.vault.database.attachmentDao().byId(id)?.removedFromPhoneAt).isNull()
        assertThat(rig.vault.attachmentFiles.all()).hasSize(1)
    }

    /** ADR-0035 §1, condition 2: sealed to this install's nightly key, and nothing else. */
    @Test
    fun theFoldersCopy_countsOnlyWhenSealedToThisInstall() = runTest {
        val mine = rig.storeImage()
        val other = rig.storeImage()
        val publicKey = BackupSealKem.publicKey(seed)
        rig.vault.database.appMetaDao().put(
            AppMetaEntity(DefaultBackupRepository.KEY_SEAL_PUBLIC_KEY, publicKey.toHex()),
        )
        backup.writeAllSealed(FileBackupFolder(rig.folder), publicKey)
        // Another phrase's copy of the second, as if the folder held a different install's.
        val strangers = BackupSealKem.publicKey(Bip39.toSeed(Bip39.generate(SecureRandom())))
        File(rig.folder, "${AttachmentBackup.IMAGES_DIRECTORY}/$other.${AttachmentBackup.EXTENSION}")
            .writeBytes(LfbaContainer.writeSealed(byteArrayOf(1, 2, 3), strangers, other))

        assertThat(copies.sealedCopy(mine)).isNotNull()
        assertThat(copies.sealedCopy(other)).isNull()
        assertThat(copies.sealedCopy("no-such-receipt")).isNull()
    }

    @Test
    fun noFolder_noCopy() = runTest {
        val id = rig.storeImage()
        rig.folderReachable = false

        assertThat(copies.sealedCopy(id)).isNull()
    }

    /** ADR-0035 §3: durability before space. A restore brings it back and the mark goes. */
    @Test
    fun aRestore_bringsARemovedReceiptBack_andClearsTheMark() = runTest {
        val id = rig.storeImage()
        backup.writeAll(FileBackupFolder(rig.folder), seed)
        rig.receipts.removeFromPhone(id)

        backup.restoreAll(FileBackupFolder(rig.folder), seed)

        assertThat(rig.vault.attachmentFiles.all()).hasSize(1)
        assertThat(rig.vault.database.attachmentDao().byId(id)?.removedFromPhoneAt).isNull()
        assertThat(rig.receipts.image(id)).isInstanceOf(ReceiptImage.Available::class.java)
    }

    /** The nightly pass, after the user picked a new, empty folder: removed is expected, not unreadable. */
    @Test
    fun theNightlyPass_countsARemovedReceiptAsRemoved_notUnreadable() = runTest {
        val id = rig.storeImage()
        rig.receipts.removeFromPhone(id)

        val report = backup.writeAllSealed(FileBackupFolder(rig.folder), BackupSealKem.publicKey(seed))

        assertThat(report.removedFromPhone).isEqualTo(1)
        assertThat(report.unreadableLocally).isEqualTo(0)
    }

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
}
