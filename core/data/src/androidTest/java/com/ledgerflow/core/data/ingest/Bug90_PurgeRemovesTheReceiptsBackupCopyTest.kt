package com.ledgerflow.core.data.ingest

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.ledgerflow.core.data.ledger.DefaultLedgerRepository
import com.ledgerflow.core.model.LedgerType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * BUG90 (found 2026-11-09 closing V2-5): purging an entry from the bin unlinked
 * its receipt's file on the phone and left the sealed copy in the backup folder,
 * and on Google Drive, which keeps what the folder has (ADR-0035 §4). Nothing
 * could restore it once the backups naming its row had rotated: BUG88 by another
 * door. The purge now removes the folder's copy too, and only for a purge that
 * actually destroyed the entry.
 */
@RunWith(AndroidJUnit4::class)
class Bug90_PurgeRemovesTheReceiptsBackupCopyTest {

    private val rig = ReceiptsTestRig("bug90")

    private val ledger get() = DefaultLedgerRepository(
        rig.vault.session,
        rig.vault.ids,
        rig.vault.clock,
        rig.vault.attachmentFiles,
        Dispatchers.IO,
        ReceiptBackupFolder(rig.resolver),
    )

    @Before
    fun setUp() = runTest { rig.open() }

    @After
    fun tearDown() = rig.close()

    /** A receipt on a binned entry, with its copy in the backup folder. */
    private suspend fun binnedWithCopy(ledgerType: LedgerType): Pair<String, java.io.File> {
        val receipt = rig.storeImage()
        val entry = rig.approvedEntry(ledgerType, 4_500)
        rig.link(receipt, entry)
        val copy = rig.writeBackupCopy(receipt)
        rig.vault.ledger.softDeleteEntry(ledgerType, entry)
        return entry to copy
    }

    @Test
    fun purgingOneEntry_removesItsReceiptsBackupCopy() = runTest {
        val (entry, copy) = binnedWithCopy(LedgerType.DEBIT)

        assertThat(ledger.purgeDeletedEntry(LedgerType.DEBIT, entry)).isEqualTo(1)

        assertThat(copy.exists()).isFalse()
        assertThat(rig.vault.attachmentFiles.all()).isEmpty()
    }

    @Test
    fun emptyingABook_removesEveryBinnedReceiptsCopy_andNoOtherBooks() = runTest {
        val (_, first) = binnedWithCopy(LedgerType.DEBIT)
        val (_, second) = binnedWithCopy(LedgerType.DEBIT)
        val (_, income) = binnedWithCopy(LedgerType.CREDIT)

        assertThat(ledger.purgeDeletedEntries(LedgerType.DEBIT)).isEqualTo(2)

        assertThat(first.exists()).isFalse()
        assertThat(second.exists()).isFalse()
        // Law 2: the income book's bin is untouched, its receipt's copy with it.
        assertThat(income.exists()).isTrue()
    }

    /** The statement binds `deleted_at IS NOT NULL`: a live entry is not purged, and its copy stays. */
    @Test
    fun aPurgeThatDestroysNothing_removesNoCopy() = runTest {
        val receipt = rig.storeImage()
        val live = rig.approvedEntry(LedgerType.DEBIT, 4_500)
        rig.link(receipt, live)
        val copy = rig.writeBackupCopy(receipt)

        assertThat(ledger.purgeDeletedEntry(LedgerType.DEBIT, live)).isEqualTo(0)

        assertThat(copy.exists()).isTrue()
    }

    /** The folder cannot be reached: the purge still destroys the entry; the copy stays where it is. */
    @Test
    fun anUnreachableFolder_doesNotStopThePurge() = runTest {
        val (entry, copy) = binnedWithCopy(LedgerType.DEBIT)
        rig.folderReachable = false

        assertThat(ledger.purgeDeletedEntry(LedgerType.DEBIT, entry)).isEqualTo(1)

        assertThat(copy.exists()).isTrue()
    }
}
