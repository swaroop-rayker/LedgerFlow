package com.ledgerflow.core.data.ingest

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * BUG88: deleting receipts left every sealed copy in the backup folder, and on
 * Google Drive, which mirrors it. Nothing could use them (a restore looks only
 * for the images its rows name), yet they stayed forever. Delete now removes
 * the folder's copy too, and Drive follows on its next pass (ADR-0035 §5).
 */
@RunWith(AndroidJUnit4::class)
class Bug88_DeleteRemovesTheBackupCopyTest {

    private val rig = ReceiptsTestRig("bug88")

    @Before
    fun setUp() = runTest { rig.open() }

    @After
    fun tearDown() = rig.close()

    @Test
    fun deletingSomeReceipts_removesTheirBackupCopies_andNoOthers() = runTest {
        val doomed = rig.storeImage()
        val kept = rig.storeImage()
        val doomedCopy = rig.writeBackupCopy(doomed)
        val keptCopy = rig.writeBackupCopy(kept)

        val deletion = rig.receipts.delete(setOf(doomed))

        assertThat(deletion.deleted).isEqualTo(1)
        assertThat(deletion.backupCopiesRemoved).isEqualTo(1)
        assertThat(deletion.backupFolderUnreachable).isFalse()
        assertThat(doomedCopy.exists()).isFalse()
        assertThat(keptCopy.exists()).isTrue()
        assertThat(rig.vault.database.attachmentDao().byId(kept)).isNotNull()
    }

    @Test
    fun deletingAll_removesEveryBackupCopyItOwned() = runTest {
        val first = rig.writeBackupCopy(rig.storeImage())
        val second = rig.writeBackupCopy(rig.storeImage())

        val deletion = rig.receipts.deleteAll()

        assertThat(deletion.deleted).isEqualTo(2)
        assertThat(first.exists()).isFalse()
        assertThat(second.exists()).isFalse()
        assertThat(rig.vault.attachmentFiles.all()).isEmpty()
    }

    /** The folder can't be reached: the phone's copy still goes, and the result says the folder's stays. */
    @Test
    fun anUnreachableFolder_stillDeletesFromThePhone_andSaysSo() = runTest {
        val id = rig.storeImage()
        val copy = rig.writeBackupCopy(id)
        rig.folderReachable = false

        val deletion = rig.receipts.delete(setOf(id))

        assertThat(deletion.deleted).isEqualTo(1)
        assertThat(deletion.backupFolderUnreachable).isTrue()
        assertThat(rig.vault.attachmentFiles.all()).isEmpty()
        assertThat(copy.exists()).isTrue()
    }
}
