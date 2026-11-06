package com.ledgerflow.core.domain.statement

import com.ledgerflow.core.domain.ingest.ExtractedDirection
import com.ledgerflow.core.model.Money
import javax.inject.Inject

/**
 * Import a bank statement or a spreadsheet into the Inbox (V2-4,
 * `docs/V2-PLAN.md` §3.6, decision D8).
 *
 * **Law 1 holds by construction:** every row becomes a *candidate*, written
 * through the same writer SMS, notifications and receipts use, and nothing
 * reaches the ledger until the user approves it.
 */
public interface StatementImportRepository {

    /** Reads the file the user picked into rows, and guesses its columns. Writes nothing. */
    public suspend fun open(documentUri: String): StatementOpened

    /**
     * Reads every row with [columns] and finds the ones already in LedgerFlow:
     * same book, same amount, a date within a day, in the ledger or the Inbox.
     * Writes nothing.
     */
    public suspend fun check(table: StatementTable, columns: StatementColumns): StatementCheck

    /**
     * Adds the rows to the Inbox as candidates. Rows already in LedgerFlow go
     * in suppressed — kept and visible under the Inbox's Suppressed filter, not
     * in the queue. A row already imported from this same file is not added
     * twice.
     */
    public suspend fun addToInbox(table: StatementTable, columns: StatementColumns): StatementImported
}

/** What opening a file came to. */
public sealed interface StatementOpened {

    /**
     * Rows were read. [guess] is null when no column reads as dates and
     * amounts. [baseCurrency] is the vault's, which an amount is read in
     * unless a currency column says otherwise.
     */
    public data class Opened(
        val table: StatementTable,
        val guess: StatementGuess.Guess?,
        val baseCurrency: String,
    ) : StatementOpened

    /** A kind of file this import does not read, named so the screen can say what to do instead. */
    public data class Unsupported(val kind: UnsupportedStatement) : StatementOpened

    /** Larger than an import reads at once. */
    public data object TooLarge : StatementOpened

    /** The file could not be read: access lost, damaged, or empty. */
    public data object Unreadable : StatementOpened
}

public enum class UnsupportedStatement {
    /** The pre-2007 binary `.xls`: save it as `.xlsx` or CSV first. */
    OldExcel,

    /** A PDF statement: most banks also offer CSV or Excel. */
    Pdf,
}

/** One row read, and whether it is already in LedgerFlow. */
public data class CheckedRow(val line: StatementLine.Read, val alreadyThere: Boolean)

/**
 * The check page's figures. Each book is counted and totalled on its own (Law 2),
 * and only rows in the base currency are totalled: a row in another currency is
 * counted, and its base amount is the user's to enter at review.
 */
public data class StatementCheck(
    val baseCurrency: String,
    val rows: List<CheckedRow>,
    val unreadable: List<StatementLine.Unreadable>,
) {
    private val new: List<StatementLine.Read> get() = rows.filterNot { it.alreadyThere }.map { it.line }

    public val expenses: Int get() = new.count { it.direction == ExtractedDirection.DEBIT }
    public val income: Int get() = new.count { it.direction == ExtractedDirection.CREDIT }
    public val expensesTotal: Money get() = totalOf(ExtractedDirection.DEBIT)
    public val incomeTotal: Money get() = totalOf(ExtractedDirection.CREDIT)

    /** New rows in another currency: counted above, not in either total. */
    public val otherCurrency: Int get() = new.count { it.currency != baseCurrency }
    public val alreadyThere: Int get() = rows.count { it.alreadyThere }

    /** What "Add to the Inbox" adds to the queue. */
    public val toAdd: Int get() = new.size

    private fun totalOf(direction: ExtractedDirection): Money = Money.sum(
        new.filter { it.direction == direction && it.currency == baseCurrency }.map { it.amount },
    )
}

/** What adding to the Inbox did. */
public data class StatementImported(
    /** New candidates in the queue. */
    val added: Int,
    /** Rows already in LedgerFlow, kept under the Suppressed filter. */
    val suppressed: Int,
    /** Rows this same file had already added on an earlier import; not added again. */
    val alreadyImported: Int,
    /** Rows that could not be written. Their lines stay in the file. */
    val failed: Int,
)

/** Thin, so the screen depends on what it does rather than on the repository. */
public class OpenStatementUseCase @Inject constructor(private val repository: StatementImportRepository) {
    public suspend operator fun invoke(documentUri: String): StatementOpened = repository.open(documentUri)
}

public class CheckStatementUseCase @Inject constructor(private val repository: StatementImportRepository) {
    public suspend operator fun invoke(table: StatementTable, columns: StatementColumns): StatementCheck =
        repository.check(table, columns)
}

public class ImportStatementUseCase @Inject constructor(private val repository: StatementImportRepository) {
    public suspend operator fun invoke(table: StatementTable, columns: StatementColumns): StatementImported =
        repository.addToInbox(table, columns)
}
