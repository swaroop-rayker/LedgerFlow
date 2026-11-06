package com.ledgerflow.core.database.backup

/**
 * What a vault holds, as a restore from Settings shows it (ADR-0033 c): the
 * entries in each book, never one figure for both (Law 2), and the Inbox.
 *
 * Counted from a [BackupPayload] for **both** sides — the backup's, and the
 * phone's own vault exported the same way — so the two columns on the
 * confirmation can only differ because the data does.
 */
public data class BackupCounts(
    /** Live debit entries: the Expenses book, without the bin. */
    val expenses: Int,
    /** Live credit entries: the Income book, without the bin. */
    val income: Int,
    /** Candidates in the Inbox's queue, counted as its badge is (`observePendingCount`). */
    val inbox: Int,
)

/** [BackupCounts] for this payload. */
public fun BackupPayload.counts(): BackupCounts = BackupCounts(
    expenses = ledgerEntries.count { it.ledger == DEBIT && it.deletedAt == null },
    income = ledgerEntries.count { it.ledger == CREDIT && it.deletedAt == null },
    inbox = pendingTransactions.count {
        it.status == PENDING && it.suppressedById == null && it.nonTransactionKind == null
    },
)

/** The backup's own counts, read before anything is written. */
public val OpenedBackup.Ready.counts: BackupCounts get() = payload.counts()

private const val DEBIT = "DEBIT"
private const val CREDIT = "CREDIT"
private const val PENDING = "PENDING"
