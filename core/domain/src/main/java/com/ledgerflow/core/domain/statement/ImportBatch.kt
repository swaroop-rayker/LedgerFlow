package com.ledgerflow.core.domain.statement

import com.ledgerflow.core.domain.inbox.PendingTransaction
import com.ledgerflow.core.domain.ingest.ExtractedDirection
import com.ledgerflow.core.model.EntrySource
import com.ledgerflow.core.model.Money
import com.ledgerflow.core.model.PendingStatus

/**
 * One import's rows still waiting in the Inbox, for its "Approve all" panel
 * (V2-4, decision D8).
 *
 * A batch is one file: its rows share the reference prefix
 * `import:<fingerprint>:`, and the import noted its name and time in
 * `app_meta`. Each book is counted and totalled on its own (Law 2).
 */
public data class ImportBatch(
    val fingerprint: String,
    /** The file's name as it was picked, or null if the note was lost. */
    val fileName: String?,
    /** When it was imported, or null if the note was lost. */
    val importedAt: Long?,
    /** What [spent] and [received] are totalled in. */
    val baseCurrency: String,
    /** Rows "Approve all" takes. */
    val spent: BookShare,
    val received: BookShare,
    /** Rows waiting that "Approve all" leaves for one-by-one review. */
    val toReview: Int,
) {
    /** How many rows "Approve all" approves, and what its confirmation names. */
    public val ready: Int get() = spent.count + received.count
}

/** One book's part of a batch: how many rows, and their total in the base currency. */
public data class BookShare(val count: Int, val total: Money) {
    public companion object {
        public val NONE: BookShare = BookShare(0, Money.ZERO)
    }
}

/** What the import noted about a file when it added it. */
public data class ImportNote(val fileName: String?, val importedAt: Long?)

/** The rows of one import still waiting, and the base currency they are read in. */
public data class ImportWaiting(val baseCurrency: String, val rows: List<PendingTransaction>)

/**
 * The rules D8 set for "Approve all", in one place so the panel's figures and
 * the approval cannot disagree about which rows it covers.
 */
public object ImportBatches {

    /**
     * Whether "Approve all" may take [row]. **Only complete rows qualify** (D8):
     * an imported candidate, still waiting and not suppressed, in the base
     * currency (a foreign amount needs its base amount entered at review), with
     * its book, amount and date read, and nothing typed on the review screen
     * (a row the user has started reviewing is theirs to finish).
     */
    public fun isReady(row: PendingTransaction, baseCurrency: String): Boolean {
        val read = row.extracted
        return row.source == EntrySource.IMPORT &&
            row.status == PendingStatus.PENDING &&
            row.suppressedById == null &&
            row.edits == null &&
            !row.needsManualFill &&
            read.direction != ExtractedDirection.UNKNOWN &&
            read.amount != null &&
            read.occurredAt != null &&
            (read.currency == null || read.currency == baseCurrency)
    }

    /** The panel's figures for one import. Null when it has nothing left waiting. */
    public fun summarise(fingerprint: String, note: ImportNote?, waiting: ImportWaiting): ImportBatch? {
        if (waiting.rows.isEmpty()) return null
        val (ready, rest) = waiting.rows.partition { isReady(it, waiting.baseCurrency) }
        return ImportBatch(
            fingerprint = fingerprint,
            fileName = note?.fileName,
            importedAt = note?.importedAt,
            baseCurrency = waiting.baseCurrency,
            spent = shareOf(ready, ExtractedDirection.DEBIT),
            received = shareOf(ready, ExtractedDirection.CREDIT),
            toReview = rest.size,
        )
    }

    private fun shareOf(rows: List<PendingTransaction>, direction: ExtractedDirection): BookShare {
        val inBook = rows.filter { it.extracted.direction == direction }
        return BookShare(inBook.size, Money.sum(inBook.mapNotNull { it.extracted.amount }))
    }
}
