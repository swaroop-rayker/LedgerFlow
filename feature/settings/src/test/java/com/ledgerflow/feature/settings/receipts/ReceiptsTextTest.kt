package com.ledgerflow.feature.settings.receipts

import com.google.common.truth.Truth.assertThat
import com.ledgerflow.core.domain.receipts.ReceiptCopies
import com.ledgerflow.core.domain.receipts.ReceiptDeletion
import com.ledgerflow.core.domain.receipts.ReceiptFiling
import com.ledgerflow.core.model.LedgerType
import com.ledgerflow.core.model.Money
import org.junit.Test

/**
 * The Receipts page's words (V2-5). The delete dialog guards an irreversible
 * action, and **what it promises is the load-bearing part** (CLAUDE.md §7).
 */
class ReceiptsTextTest {

    private val folderAndDrive = ReceiptCopies(backupFolder = "LedgerFlow", drive = true)
    private val folderOnly = ReceiptCopies(backupFolder = "LedgerFlow", drive = false)
    private val nowhere = ReceiptCopies(backupFolder = null, drive = false)

    @Test
    fun theTitle_namesTheCountAndTheSize() {
        assertThat(ReceiptsText.deleteTitle(count = 2, bytes = 820_000, all = false))
            .isEqualTo("Delete 2 receipts (820 kB)?")
        assertThat(ReceiptsText.deleteTitle(count = 1, bytes = 412_000, all = false))
            .isEqualTo("Delete 1 receipt (412 kB)?")
        assertThat(ReceiptsText.deleteTitle(count = 46, bytes = 18_400_000, all = true))
            .isEqualTo("Delete all 46 receipts (18 MB)?")
    }

    /** BUG88: it says every place the receipts are removed from, Drive included. */
    @Test
    fun theBody_namesWhereTheyAreRemovedFrom() {
        assertThat(ReceiptsText.deleteBody(folderAndDrive, count = 2))
            .startsWith("They're removed from this phone and your backup folder, and from Google Drive")
        assertThat(ReceiptsText.deleteBody(folderOnly, count = 2))
            .startsWith("They're removed from this phone and your backup folder.")
        assertThat(ReceiptsText.deleteBody(nowhere, count = 1))
            .startsWith("It's removed from this phone, which keeps no other copy.")
    }

    /**
     * **The promises it must not make**, kept from the dialog it replaced: the
     * CSV export holds no image, "Back up now" needs the 24 words, and a restore
     * replaces the whole ledger.
     */
    @Test
    fun theBody_offersNoExportNoBackupAndNoRestore() {
        listOf(folderAndDrive, folderOnly, nowhere).forEach { copies ->
            val body = ReceiptsText.deleteBody(copies, count = 3).lowercase()

            assertThat(body).doesNotContain("export")
            assertThat(body).doesNotContain("back up")
            assertThat(body).doesNotContain("restore")
            assertThat(body).contains("can't be undone")
            assertThat(body).contains("your entries and their amounts stay")
        }
    }

    @Test
    fun theUnavailableImage_promisesNoRestore() {
        assertThat(ReceiptsText.IMAGE_UNAVAILABLE_BODY.lowercase()).doesNotContain("restore")
    }

    @Test
    fun aDeleteThatCouldNotReachTheFolder_saysItsCopiesStay() {
        val deletion = ReceiptDeletion(
            deleted = 2,
            bytes = 820_000,
            backupCopiesRemoved = 0,
            backupFolderUnreachable = true,
        )

        assertThat(ReceiptsText.deleted(deletion))
            .isEqualTo("Deleted 2 receipts (820 kB). The backup folder couldn't be reached, so its copies stay there.")
    }

    @Test
    fun aRowIsCalledByItsMerchant_thenItsCategory_thenItsBook() {
        val filed = ReceiptFiling.Filed(LedgerType.DEBIT, "Big Bazaar", "Groceries", Money(1), "INR", 0)

        assertThat(ReceiptsText.title(filed)).isEqualTo("Big Bazaar")
        assertThat(ReceiptsText.title(filed.copy(merchantName = null))).isEqualTo("Groceries")
        assertThat(ReceiptsText.title(filed.copy(merchantName = null, categoryName = null))).isEqualTo("Expense")
        assertThat(ReceiptsText.title(ReceiptFiling.InInbox("p"))).isEqualTo("Not filed")
        assertThat(ReceiptsText.status(ReceiptFiling.InInbox("p"))).isEqualTo("In Inbox")
        assertThat(ReceiptsText.status(ReceiptFiling.InBin)).isEqualTo("In the bin")
    }
}
