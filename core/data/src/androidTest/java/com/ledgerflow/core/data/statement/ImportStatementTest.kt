package com.ledgerflow.core.data.statement

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.ledgerflow.core.common.time.Clock
import com.ledgerflow.core.data.ingest.PendingCandidateWriter
import com.ledgerflow.core.data.ledger.LedgerTestVault
import com.ledgerflow.core.domain.ingest.DedupeKey
import com.ledgerflow.core.domain.ingest.ExtractedDirection
import com.ledgerflow.core.domain.ingest.ExtractedTransaction
import com.ledgerflow.core.domain.ingest.PendingCandidate
import com.ledgerflow.core.domain.ledger.ApprovalRequest
import com.ledgerflow.core.domain.ledger.LedgerResult
import com.ledgerflow.core.domain.statement.StatementColumns
import com.ledgerflow.core.domain.statement.StatementImported
import com.ledgerflow.core.domain.statement.StatementOpened
import com.ledgerflow.core.domain.statement.StatementTable
import com.ledgerflow.core.model.EntryAssignment
import com.ledgerflow.core.model.EntrySource
import com.ledgerflow.core.model.LedgerEntry
import com.ledgerflow.core.model.LedgerType
import com.ledgerflow.core.model.Money
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Import a statement into the Inbox (V2-4, D8), on a real vault.
 *
 * What is under test is the owner's design: every row becomes a candidate and
 * nothing reaches the ledger (Law 1); a row already in LedgerFlow — same book,
 * same amount, a date within a day, in the ledger or waiting in the Inbox — is
 * kept suppressed, not dropped and not doubled; each existing transaction
 * answers for one row; the books never match each other (Law 2); and the same
 * file imported twice adds nothing the second time.
 */
@RunWith(AndroidJUnit4::class)
class ImportStatementTest {

    private val vault = LedgerTestVault("import-test")
    private lateinit var repository: DefaultStatementImportRepository
    private val documents = mutableMapOf<String, StatementDocument>()

    @Before
    fun setUp() = runBlocking<Unit> {
        vault.open()
        val clock = Clock { vault.now }
        repository = DefaultStatementImportRepository(
            session = vault.session,
            documents = StatementDocumentSource { uri, _ -> documents[uri] },
            writer = PendingCandidateWriter(clock, vault.ids),
            clock = clock,
            io = Dispatchers.IO,
        )
    }

    @After
    fun tearDown() = vault.close()

    /** A statement in the usual bank layout: date, narration, money out, money in. */
    private suspend fun open(vararg rows: String, name: String = "oct.csv"): Pair<StatementTable, StatementColumns> {
        val csv = (listOf("Date,Narration,Withdrawal Amt.,Deposit Amt.") + rows).joinToString("\n")
        documents[name] = StatementDocument(name, csv.toByteArray())
        val opened = repository.open(name) as StatementOpened.Opened
        return opened.table to requireNotNull(opened.guess).columns
    }

    private suspend fun approve(ledger: LedgerType, minor: Long, date: LocalDate): LedgerEntry {
        val result = vault.ledger.approve(
            ApprovalRequest(
                ledger = ledger,
                amount = Money(minor),
                occurredAt = date.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli(),
                assignment = EntryAssignment(),
                note = null,
                lineItems = emptyList(),
            ),
        )
        return (result as LedgerResult.Success).value
    }

    private suspend fun candidates() = vault.database.pendingTransactionDao().all()

    private suspend fun ledgerSize(): Int =
        vault.database.ledgerEntryDao().debitIdsMatching(41_000, 0, Int.MAX_VALUE).size +
            vault.database.ledgerEntryDao().creditIdsMatching(8_500_000, 0, Int.MAX_VALUE).size

    @Test
    fun aStatement_landsInTheInboxAsCandidates_andNothingInTheLedger() = runBlocking<Unit> {
        val (table, columns) = open("02/10/2026,SWIGGY,410.00,", "03/10/2026,SALARY,,85000.00")

        val imported = repository.addToInbox(table, columns)

        assertThat(imported).isEqualTo(StatementImported(added = 2, suppressed = 0, alreadyImported = 0, failed = 0))
        val rows = candidates()
        assertThat(rows.map { it.source }.toSet()).containsExactly(EntrySource.IMPORT)
        assertThat(rows.map { it.suppressedById }.toSet()).containsExactly(null)
        assertThat(rows.map { it.rawRefId }).containsExactly(
            "import:${table.fingerprint}:2",
            "import:${table.fingerprint}:3",
        )
        // Law 1: approval is still the only way in.
        assertThat(ledgerSize()).isEqualTo(0)
    }

    /** One entry in the ledger answers for one of two identical rows, not both. */
    @Test
    fun aRowAlreadyInTheLedger_isKeptSuppressed_andEachEntryAnswersOnce() = runBlocking<Unit> {
        val entry = approve(LedgerType.DEBIT, 41_000, LocalDate.of(2026, 10, 2))
        val (table, columns) = open("02/10/2026,SWIGGY,410.00,", "02/10/2026,SWIGGY,410.00,")

        val check = repository.check(table, columns)
        assertThat(check.alreadyThere).isEqualTo(1)
        assertThat(check.toAdd).isEqualTo(1)

        val imported = repository.addToInbox(table, columns)

        assertThat(imported).isEqualTo(StatementImported(added = 1, suppressed = 1, alreadyImported = 0, failed = 0))
        // Typed in by hand, so the suppressed row points at the entry itself.
        assertThat(candidates().mapNotNull { it.suppressedById }).containsExactly(entry.id)
    }

    @Test
    fun aDayEitherSide_isTheSameTransaction_twoDaysIsNot() = runBlocking<Unit> {
        approve(LedgerType.DEBIT, 41_000, LocalDate.of(2026, 10, 2))
        approve(LedgerType.DEBIT, 41_000, LocalDate.of(2026, 10, 20))
        val (table, columns) = open("03/10/2026,SWIGGY,410.00,", "22/10/2026,SWIGGY,410.00,")

        val check = repository.check(table, columns)

        assertThat(check.rows.map { it.alreadyThere }).containsExactly(true, false).inOrder()
    }

    /** Law 2: a debit is never "already there" because of a credit of the same amount. */
    @Test
    fun theBooksNeverMatchEachOther() = runBlocking<Unit> {
        approve(LedgerType.CREDIT, 41_000, LocalDate.of(2026, 10, 2))
        val (table, columns) = open("02/10/2026,REFUND?,410.00,")

        assertThat(repository.check(table, columns).alreadyThere).isEqualTo(0)
    }

    /**
     * A candidate captured today from an SMS about a payment on 2 October is
     * matched by its stated date — not by the capture window, which would see
     * every imported row as captured in the same instant.
     */
    @Test
    fun aWaitingCandidate_isMatchedByItsStatedDate() = runBlocking<Unit> {
        val sms = ExtractedTransaction(
            amount = Money(41_000),
            currency = "INR",
            direction = ExtractedDirection.DEBIT,
            merchantRaw = "SWIGGY",
            occurredAt = LocalDate.of(2026, 10, 2).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli(),
            confidence = 0.9,
        )
        PendingCandidateWriter(Clock { vault.now }, vault.ids).write(
            vault.database,
            "sms-1",
            PendingCandidate(EntrySource.SMS, sms, DedupeKey.compute(sms, "sms-1")),
            verdict = null,
        )
        val (table, columns) = open("02/10/2026,SWIGGY,410.00,", "15/10/2026,SWIGGY,410.00,")

        val imported = repository.addToInbox(table, columns)

        assertThat(imported.suppressed).isEqualTo(1)
        assertThat(imported.added).isEqualTo(1)
    }

    /** Two identical rows in one statement are two payments: neither suppresses the other. */
    @Test
    fun identicalRowsInOneFile_areBothAdded() = runBlocking<Unit> {
        val (table, columns) = open("02/10/2026,COFFEE,50.00,", "02/10/2026,COFFEE,50.00,")

        assertThat(repository.addToInbox(table, columns).added).isEqualTo(2)
    }

    @Test
    fun theSameFileTwice_addsNothingTheSecondTime() = runBlocking<Unit> {
        val (table, columns) = open("02/10/2026,SWIGGY,410.00,", "03/10/2026,SALARY,,85000.00")
        repository.addToInbox(table, columns)

        assertThat(repository.check(table, columns).alreadyThere).isEqualTo(2)
        assertThat(repository.addToInbox(table, columns))
            .isEqualTo(StatementImported(added = 0, suppressed = 0, alreadyImported = 2, failed = 0))
        assertThat(candidates()).hasSize(2)
    }

    /** A row in another currency keeps it, for the user to give the base amount at review. */
    @Test
    fun aRowInAnotherCurrency_isAddedWithItsCurrency() = runBlocking<Unit> {
        val csv = "Date,Description,Amount,Currency\n02/10/2026,Hotel,-120.50,USD\n"
        documents["usd.csv"] = StatementDocument("usd.csv", csv.toByteArray())
        val opened = repository.open("usd.csv") as StatementOpened.Opened

        val check = repository.check(opened.table, requireNotNull(opened.guess).columns)
        repository.addToInbox(opened.table, requireNotNull(opened.guess).columns)

        assertThat(check.otherCurrency).isEqualTo(1)
        assertThat(check.expensesTotal).isEqualTo(Money(0))
        assertThat(candidates().single().extractedJson).contains("USD")
    }
}
