package com.ledgerflow.feature.settings.receipts

import com.ledgerflow.core.domain.receipts.ReceiptCopies
import com.ledgerflow.core.domain.receipts.ReceiptDeletion
import com.ledgerflow.core.domain.receipts.ReceiptFiling
import com.ledgerflow.core.domain.receipts.ReceiptPeriod
import com.ledgerflow.core.model.LedgerType
import com.ledgerflow.feature.settings.formatSize

/**
 * The Receipts page's words (V2-5), apart from the composables so that what a
 * dialog promises, or refuses to promise, is pinned by `ReceiptsTextTest`.
 */
internal object ReceiptsText {

    fun periodLabel(period: ReceiptPeriod): String = when (period) {
        ReceiptPeriod.Today -> "Today"
        ReceiptPeriod.Week -> "Week"
        ReceiptPeriod.Month -> "Month"
        ReceiptPeriod.All -> "All"
    }

    fun receipts(count: Int): String = if (count == 1) "1 receipt" else "$count receipts"

    /** "12 receipts · 4 MB": the line above the list. */
    fun countLine(count: Int, bytes: Long): String = "${receipts(count)} · ${formatSize(bytes)}"

    /** Where the copies are, for the summary's second row. */
    fun copies(copies: ReceiptCopies): String = when {
        copies.backupFolder != null && copies.drive -> "Backup folder · Google Drive"
        copies.backupFolder != null -> "Backup folder “${copies.backupFolder}”"
        else -> "None. This phone only."
    }

    /** What a row is called: its merchant, else its category, else what it is. */
    fun title(filing: ReceiptFiling): String = when (filing) {
        is ReceiptFiling.Filed -> filing.merchantName ?: filing.categoryName ?: bookName(filing.ledger)
        ReceiptFiling.InBin -> "Deleted entry"
        is ReceiptFiling.InInbox, ReceiptFiling.NotFiled -> "Not filed"
    }

    /** A row's trailing words, where it has no amount. */
    fun status(filing: ReceiptFiling): String? = when (filing) {
        is ReceiptFiling.Filed -> null
        ReceiptFiling.InBin -> "In the bin"
        is ReceiptFiling.InInbox -> "In Inbox"
        ReceiptFiling.NotFiled -> null
    }

    /** The viewer's first fact: "Expense · Groceries". */
    fun filedAs(filing: ReceiptFiling.Filed): String =
        listOfNotNull(bookName(filing.ledger), filing.categoryName).joinToString(" · ")

    fun bookName(ledger: LedgerType): String = when (ledger) {
        LedgerType.DEBIT -> "Expense"
        LedgerType.CREDIT -> "Income"
    }

    fun deleteTitle(count: Int, bytes: Long, all: Boolean): String =
        if (all) {
            "Delete all ${receipts(count)} (${formatSize(bytes)})?"
        } else {
            "Delete ${receipts(count)} (${formatSize(bytes)})?"
        }

    /**
     * Where the receipts are removed from, said exactly (BUG88), that the
     * entries stay, and that it cannot be undone.
     *
     * **What it must not say**, kept from the dialog this replaced (ADR-0023):
     * no "export first", because the export is CSV and holds no image; and no
     * "back up first", because "Back up now" needs the 24 words and the app
     * never holds them (`CLAUDE.md` §7). Nor a promise of a restore.
     */
    fun deleteBody(copies: ReceiptCopies, count: Int): String {
        val them = if (count == 1) "It's" else "They're"
        val where = when {
            copies.backupFolder != null && copies.drive ->
                "$them removed from this phone and your backup folder, and from Google Drive the next time it copies."
            copies.backupFolder != null -> "$them removed from this phone and your backup folder."
            else -> "$them removed from this phone, which keeps no other copy."
        }
        return "$where Your entries and their amounts stay. This can't be undone."
    }

    /** What a delete did, for the notice that follows it. */
    fun deleted(deletion: ReceiptDeletion): String {
        val done = "Deleted ${receipts(deletion.deleted)} (${formatSize(deletion.bytes)})."
        return if (deletion.backupFolderUnreachable) {
            "$done The backup folder couldn't be reached, so its copies stay there."
        } else {
            done
        }
    }

    const val NOTHING_DELETED: String = "Nothing was deleted. Try again."

    const val IMAGE_UNAVAILABLE_TITLE: String = "This image can't be opened"
    /**
     * No promise of a restore: restoring replaces the whole ledger (ADR-0033),
     * which is no way to fetch one picture.
     */
    const val IMAGE_UNAVAILABLE_BODY: String = "The file on this phone is missing or damaged."
}
