package com.ledgerflow.core.data.ingest

import androidx.paging.testing.asSnapshot
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.ledgerflow.core.domain.receipts.ReceiptFiling
import com.ledgerflow.core.domain.receipts.ReceiptImage
import com.ledgerflow.core.domain.receipts.ReceiptPeriod
import com.ledgerflow.core.model.LedgerType
import com.ledgerflow.core.model.Money
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The Receipts page's reads (V2-5) against a real vault: what each receipt
 * belongs to, found per book (Law 2), and the delete's order.
 */
@RunWith(AndroidJUnit4::class)
class ReceiptsRepositoryInstrumentedTest {

    private val rig = ReceiptsTestRig("receipts-repository")

    @Before
    fun setUp() = runTest { rig.open() }

    @After
    fun tearDown() = rig.close()

    @Test
    fun eachReceipt_saysWhatItBelongsTo_inItsOwnBook() = runTest {
        val expense = rig.storeImage().also { rig.link(it, rig.approvedEntry(LedgerType.DEBIT, 124_000)) }
        val income = rig.storeImage().also { rig.link(it, rig.approvedEntry(LedgerType.CREDIT, 500_000)) }
        val waiting = rig.storeImage().also { rig.vault.seedPendingCandidate(rawRefId = it) }
        val loose = rig.storeImage()

        val filings = rig.receipts.observeReceipts(ReceiptPeriod.All).asSnapshot().associate { it.id to it.filing }

        assertThat((filings.getValue(expense) as ReceiptFiling.Filed).ledger).isEqualTo(LedgerType.DEBIT)
        assertThat((filings.getValue(expense) as ReceiptFiling.Filed).amount).isEqualTo(Money(124_000))
        assertThat((filings.getValue(income) as ReceiptFiling.Filed).ledger).isEqualTo(LedgerType.CREDIT)
        assertThat(filings.getValue(waiting)).isInstanceOf(ReceiptFiling.InInbox::class.java)
        assertThat(filings.getValue(loose)).isEqualTo(ReceiptFiling.NotFiled)
    }

    @Test
    fun aReceiptWhoseEntryIsBinned_saysSo() = runTest {
        val id = rig.storeImage()
        val entry = rig.approvedEntry(LedgerType.DEBIT, 9_900)
        rig.link(id, entry)
        rig.vault.ledger.softDeleteEntry(LedgerType.DEBIT, entry)

        assertThat(rig.receipts.receipt(id)?.filing).isEqualTo(ReceiptFiling.InBin)
    }

    @Test
    fun theCount_andSelectAll_coverThePeriod() = runTest {
        val ids = List(3) { rig.storeImage() }

        assertThat(rig.receipts.observeCount(ReceiptPeriod.All).first().count).isEqualTo(3)
        assertThat(rig.receipts.idsIn(ReceiptPeriod.All)).containsExactlyElementsIn(ids)
    }

    /** Rows go before files: the row must never outlive its image. Both go; the entry stays. */
    @Test
    fun delete_removesTheRowAndTheFile_andLeavesTheEntry() = runTest {
        val id = rig.storeImage()
        val entry = rig.approvedEntry(LedgerType.DEBIT, 4_500)
        rig.link(id, entry)

        rig.receipts.delete(setOf(id))

        assertThat(rig.vault.database.attachmentDao().byId(id)).isNull()
        assertThat(rig.vault.attachmentFiles.all()).isEmpty()
        assertThat(rig.receipts.image(id)).isEqualTo(ReceiptImage.Unavailable)
        assertThat(rig.vault.database.ledgerEntryDao().countForLedger(LedgerType.DEBIT)).isEqualTo(1)
    }

    @Test
    fun theImage_opensFromThePhone() = runTest {
        val id = rig.storeImage()

        assertThat(rig.receipts.image(id)).isInstanceOf(ReceiptImage.Available::class.java)
    }
}
