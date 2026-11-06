package com.ledgerflow.core.data.statement

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.ledgerflow.core.common.time.Clock
import com.ledgerflow.core.data.ingest.PendingCandidateWriter
import com.ledgerflow.core.data.ledger.LedgerTestVault
import com.ledgerflow.core.domain.ledger.ApprovalRequest
import com.ledgerflow.core.domain.ledger.LedgerError
import com.ledgerflow.core.domain.ledger.LedgerRepository
import com.ledgerflow.core.domain.ledger.LedgerResult
import com.ledgerflow.core.domain.statement.ApproveImportBatchUseCase
import com.ledgerflow.core.domain.statement.ImportApproval
import com.ledgerflow.core.domain.statement.StatementOpened
import com.ledgerflow.core.domain.statement.StatementTable
import com.ledgerflow.core.domain.taxonomy.NewCategory
import com.ledgerflow.core.domain.taxonomy.TaxonomyResult
import com.ledgerflow.core.domain.usecase.ApprovePendingUseCase
import com.ledgerflow.core.domain.usecase.ApproveTransactionUseCase
import com.ledgerflow.core.model.LedgerEntry
import com.ledgerflow.core.model.LedgerType
import com.ledgerflow.core.model.Money
import com.ledgerflow.core.model.PendingStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * "Approve all" for one import (V2-4, D8), on a real vault.
 *
 * What is under test is D8's five rules where only a database can show them:
 * every ready row reaches its own book through the Inbox's one approval path;
 * the batch is **one transaction**, so a refusal half-way leaves nothing in the
 * ledger and every row still waiting; a confirmation for a count that no longer
 * holds approves nothing; rows that are not complete, and candidates from any
 * other source, are left alone; and no merchant is created (owner, 2026-11-06).
 */
@RunWith(AndroidJUnit4::class)
class ApproveImportBatchTest {

    private val vault = LedgerTestVault("approve-import-test")
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

    private fun approveAll(ledger: LedgerRepository = vault.ledger) = ApproveImportBatchUseCase(
        repository = repository,
        approvePending = ApprovePendingUseCase(
            vault.pending,
            vault.merchants,
            ApproveTransactionUseCase(ledger) {},
        ),
        merchants = vault.merchants,
        categories = vault.categories,
    )

    /** Imports a statement in the usual layout; [header] can add a currency column. */
    private suspend fun import(
        vararg rows: String,
        header: String = "Date,Narration,Withdrawal Amt.,Deposit Amt.",
    ): StatementTable {
        val csv = (listOf(header) + rows).joinToString("\n")
        documents["oct.csv"] = StatementDocument("oct.csv", csv.toByteArray())
        val opened = repository.open("oct.csv") as StatementOpened.Opened
        repository.addToInbox(opened.table, requireNotNull(opened.guess).columns)
        return opened.table
    }

    private suspend fun candidates() = vault.database.pendingTransactionDao().all()

    /** Every committed entry as `book|amount|merchant|category|note`, read past the ports. */
    private fun entries(): List<String> {
        val rows = mutableListOf<String>()
        vault.database.openHelper.writableDatabase
            .query(
                "SELECT ledger, amount_minor, merchant_id, category_id, note FROM ledger_entry " +
                    "ORDER BY amount_minor, ledger",
            )
            .use { cursor ->
                while (cursor.moveToNext()) {
                    rows += (0..4).joinToString("|") { if (cursor.isNull(it)) "-" else cursor.getString(it) }
                }
            }
        return rows
    }

    @Test
    fun approveAll_putsEachRowInItsOwnBook_andClosesTheBatch() = runBlocking<Unit> {
        val table = import(
            "02/10/2026,SWIGGY 1,410.00,",
            "03/10/2026,SALARY,,85000.00",
            "05/10/2026,BIGBASKET,1862.40,",
        )

        val batch = repository.observeBatches().first().single()
        assertThat(batch.ready).isEqualTo(3)
        assertThat(batch.spent.count to batch.spent.total).isEqualTo(2 to Money(227_240))
        assertThat(batch.received.count to batch.received.total).isEqualTo(1 to Money(8_500_000))

        val outcome = approveAll()(table.fingerprint, confirmed = 3)

        assertThat(outcome).isEqualTo(ImportApproval.Approved(3))
        assertThat(entries()).containsExactly(
            "DEBIT|41000|-|-|SWIGGY 1",
            "DEBIT|186240|-|-|BIGBASKET",
            "CREDIT|8500000|-|-|SALARY",
        )
        assertThat(candidates().map { it.status }.toSet()).containsExactly(PendingStatus.APPROVED)
        assertThat(repository.observeBatches().first()).isEmpty()
    }

    /** D8's "as one transaction": the third row is refused, so the first two are undone too. */
    @Test
    fun aRowRefusedHalfWay_leavesNothingInTheLedger_andEveryRowWaiting() = runBlocking<Unit> {
        val table = import("02/10/2026,A,410.00,", "03/10/2026,B,238.00,", "04/10/2026,C,512.00,")
        var calls = 0
        val refusesTheThird = object : LedgerRepository by vault.ledger {
            override suspend fun approve(request: ApprovalRequest): LedgerResult<LedgerEntry> = if (++calls == 3) {
                LedgerResult.Failure(LedgerError.BaseCurrencyMissing)
            } else {
                vault.ledger.approve(request)
            }
        }

        val outcome = approveAll(refusesTheThird)(table.fingerprint, confirmed = 3)

        assertThat(outcome).isInstanceOf(ImportApproval.Refused::class.java)
        assertThat(calls).isEqualTo(3)
        assertThat(entries()).isEmpty()
        assertThat(candidates().map { it.status }.toSet()).containsExactly(PendingStatus.PENDING)
        assertThat(candidates().mapNotNull { it.approvedEntryId }).isEmpty()
        assertThat(repository.observeBatches().first().single().ready).isEqualTo(3)
    }

    /** The confirmation named a count that no longer holds: nothing is approved. */
    @Test
    fun aConfirmationForAnotherCount_approvesNothing() = runBlocking<Unit> {
        val table = import("02/10/2026,A,410.00,", "03/10/2026,B,238.00,")

        val outcome = approveAll()(table.fingerprint, confirmed = 3)

        assertThat(outcome).isEqualTo(ImportApproval.Changed(confirmed = 3, readyNow = 2))
        assertThat(entries()).isEmpty()
    }

    /**
     * Only complete rows of this import qualify: a row in another currency
     * needs its base amount at review, and a candidate from any other source
     * is never part of a batch.
     */
    @Test
    fun foreignRowsAndOtherSources_areLeftForReview() = runBlocking<Unit> {
        val other = vault.seedPendingCandidate(rawRefId = "sms:1")
        val table = import(
            "02/10/2026,SWIGGY,410.00,,INR",
            "03/10/2026,HOTEL LISBON,120.00,,EUR",
            header = "Date,Narration,Withdrawal Amt.,Deposit Amt.,Currency",
        )
        val batch = repository.observeBatches().first().single()
        assertThat(batch.ready to batch.toReview).isEqualTo(1 to 1)

        val outcome = approveAll()(table.fingerprint, confirmed = 1)

        assertThat(outcome).isEqualTo(ImportApproval.Approved(1))
        assertThat(entries()).containsExactly("DEBIT|41000|-|-|SWIGGY")
        val waiting = candidates().filter { it.status == PendingStatus.PENDING }.map { it.rawRefId }
        assertThat(waiting).containsExactly("sms:1", "import:${table.fingerprint}:3")
        assertThat(candidates().single { it.id == other }.status).isEqualTo(PendingStatus.PENDING)
    }

    /**
     * Owner, 2026-11-06: an existing merchant is used, with its category when
     * that belongs to the row's book; none is created from a narration.
     */
    @Test
    fun noMerchantIsCreated_anExistingOneIsUsed_andTheTextIsTheNote() = runBlocking<Unit> {
        val food = (vault.categories.create(NewCategory(LedgerType.DEBIT, "Food")) as TaxonomyResult.Success).value
        val swiggy = (vault.merchants.createOrGet("SWIGGY", food.id) as TaxonomyResult.Success).value
        val merchantsBefore = vault.database.merchantDao().observeLive().first().size
        val table = import(
            "02/10/2026,SWIGGY,410.00,",
            "03/10/2026,UPI-ZEPTO-628845678901,512.00,",
            "04/10/2026,SWIGGY,,410.00",
        )

        approveAll()(table.fingerprint, confirmed = 3)

        assertThat(vault.database.merchantDao().observeLive().first()).hasSize(merchantsBefore)
        assertThat(entries()).containsExactly(
            "DEBIT|41000|${swiggy.id}|${food.id}|SWIGGY",
            // A refund from the same merchant: the merchant, not a debit category on a credit.
            "CREDIT|41000|${swiggy.id}|-|SWIGGY",
            "DEBIT|51200|-|-|UPI-ZEPTO-628845678901",
        )
    }
}
