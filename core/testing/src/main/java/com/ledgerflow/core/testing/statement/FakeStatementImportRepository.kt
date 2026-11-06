package com.ledgerflow.core.testing.statement

import com.ledgerflow.core.domain.statement.CheckedRow
import com.ledgerflow.core.domain.statement.StatementCheck
import com.ledgerflow.core.domain.statement.StatementColumns
import com.ledgerflow.core.domain.statement.StatementImportRepository
import com.ledgerflow.core.domain.statement.StatementImported
import com.ledgerflow.core.domain.statement.StatementLine
import com.ledgerflow.core.domain.statement.StatementOpened
import com.ledgerflow.core.domain.statement.StatementReading
import com.ledgerflow.core.domain.statement.StatementTable

/**
 * A [StatementImportRepository] that answers with what the test sets, reads
 * rows with the real [StatementReading], and records what it was asked to
 * add — so a test can assert the screen handed over the columns the user chose.
 */
public class FakeStatementImportRepository(
    public var opened: StatementOpened = StatementOpened.Unreadable,
    public var imported: StatementImported =
        StatementImported(added = 0, suppressed = 0, alreadyImported = 0, failed = 0),
    /** Lines (by number) the check reports as already in LedgerFlow. */
    public var alreadyThere: Set<Int> = emptySet(),
    public var baseCurrency: String = "INR",
) : StatementImportRepository {

    public val openCalls: MutableList<String> = mutableListOf()
    public val checkCalls: MutableList<StatementColumns> = mutableListOf()
    public val addCalls: MutableList<Pair<StatementTable, StatementColumns>> = mutableListOf()

    override suspend fun open(documentUri: String): StatementOpened {
        openCalls += documentUri
        return opened
    }

    override suspend fun check(table: StatementTable, columns: StatementColumns): StatementCheck {
        checkCalls += columns
        val lines = StatementReading.read(table, columns, baseCurrency)
        return StatementCheck(
            baseCurrency = baseCurrency,
            rows = lines.filterIsInstance<StatementLine.Read>().map { CheckedRow(it, it.line in alreadyThere) },
            unreadable = lines.filterIsInstance<StatementLine.Unreadable>(),
        )
    }

    override suspend fun addToInbox(table: StatementTable, columns: StatementColumns): StatementImported {
        addCalls += table to columns
        return imported
    }
}
