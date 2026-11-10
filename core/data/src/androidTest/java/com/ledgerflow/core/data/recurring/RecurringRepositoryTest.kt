package com.ledgerflow.core.data.recurring

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.ledgerflow.core.data.ingest.PendingCandidateWriter
import com.ledgerflow.core.data.ledger.LedgerTestVault
import com.ledgerflow.core.domain.ingest.DedupeKey
import com.ledgerflow.core.domain.ingest.ExtractedDirection
import com.ledgerflow.core.domain.ingest.ExtractedTransaction
import com.ledgerflow.core.domain.ingest.PendingCandidate
import com.ledgerflow.core.domain.ingest.PendingWriteOutcome
import com.ledgerflow.core.domain.ledger.ApprovalRequest
import com.ledgerflow.core.domain.ledger.LedgerResult
import com.ledgerflow.core.domain.recurring.Cadence
import com.ledgerflow.core.domain.recurring.OccurrenceOutcome
import com.ledgerflow.core.domain.recurring.RecurringDetails
import com.ledgerflow.core.domain.recurring.RecurringItem
import com.ledgerflow.core.domain.recurring.RecurringMatch
import com.ledgerflow.core.domain.recurring.RecurringOccurrence
import com.ledgerflow.core.domain.recurring.RecurringResult
import com.ledgerflow.core.domain.taxonomy.TaxonomyResult
import com.ledgerflow.core.model.EntryAssignment
import com.ledgerflow.core.model.EntrySource
import com.ledgerflow.core.model.LedgerType
import com.ledgerflow.core.model.Merchant
import com.ledgerflow.core.model.Money
import com.ledgerflow.core.model.PendingStatus
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Recurring items over the real database (V2-PLAN §4.2, schema v16): the
 * matcher's rules, which are SQL and resolution the JVM fake cannot hold, and
 * the candidate write, which must go through the one candidate writer and
 * never touch the ledger (Law 1).
 */
@RunWith(AndroidJUnit4::class)
class RecurringRepositoryTest {

    private val vault = LedgerTestVault("lf_recurring_repository_test")
    private lateinit var recurring: DefaultRecurringRepository
    private lateinit var writer: PendingCandidateWriter
    private lateinit var landlord: Merchant
    private lateinit var grocer: Merchant

    private val due = LocalDate.of(2026, 12, 1).toEpochDay().toInt()

    @Before
    fun setUp() = runBlocking<Unit> {
        vault.open()
        writer = PendingCandidateWriter(vault.clock, vault.ids)
        recurring = DefaultRecurringRepository(vault.session, writer, vault.ids, vault.clock, Dispatchers.IO)
        landlord = vault.merchants.createOrGet("Landlord").success()
        grocer = vault.merchants.createOrGet("Grocer").success()
    }

    @After
    fun tearDown() = vault.close()

    @Test
    fun anEntryOfTheSameAmount_inItsWindow_andItsBook_matches() = runBlocking<Unit> {
        val rent = item()
        approve(LedgerType.DEBIT, 25_000_00, landlord.id, due - 2)

        val match = recurring.findMatch(rent, due)

        assertThat(match).isInstanceOf(RecurringMatch.Entry::class.java)
    }

    @Test
    fun outsideTheWindow_theOtherBook_aDifferentAmount_orMerchant_doNot() = runBlocking<Unit> {
        val rent = item()
        approve(LedgerType.DEBIT, 25_000_00, landlord.id, due - 4)
        approve(LedgerType.DEBIT, 25_000_00, landlord.id, due + 3)
        approve(LedgerType.CREDIT, 25_000_00, landlord.id, due)
        approve(LedgerType.DEBIT, 24_999_00, landlord.id, due)
        approve(LedgerType.DEBIT, 25_000_00, grocer.id, due)

        assertThat(recurring.findMatch(rent, due)).isNull()
    }

    /** An entry with no merchant still answers an exact amount; the merchant is only compared when both know it. */
    @Test
    fun anEntryWithNoMerchant_matchesOnTheAmount() = runBlocking<Unit> {
        val rent = item()
        approve(LedgerType.DEBIT, 25_000_00, null, due)

        assertThat(recurring.findMatch(rent, due)).isInstanceOf(RecurringMatch.Entry::class.java)
    }

    @Test
    fun anAmountThatVaries_matchesOnTheMerchant_andNeedsIt() = runBlocking<Unit> {
        val power = item(amountVaries = true, amount = 2_100_00)
        approve(LedgerType.DEBIT, 2_350_00, null, due)
        assertThat(recurring.findMatch(power, due)).isNull()

        approve(LedgerType.DEBIT, 2_350_00, landlord.id, due + 1)

        assertThat(recurring.findMatch(power, due)).isInstanceOf(RecurringMatch.Entry::class.java)
    }

    /** One entry answers one due date: a second item of the same amount is not given it too. */
    @Test
    fun anEntryAlreadyClaimed_isPassedOver() = runBlocking<Unit> {
        val rent = item()
        val other = item(name = "Rent, the other flat")
        approve(LedgerType.DEBIT, 25_000_00, landlord.id, due)
        val first = recurring.findMatch(rent, due) as RecurringMatch.Entry
        recurring.record(RecurringOccurrence(rent.id, due, OccurrenceOutcome.MATCHED, null, first.id, null))

        assertThat(recurring.findMatch(other, due)).isNull()
    }

    @Test
    fun aCapturedCandidateStillInTheInbox_matches() = runBlocking<Unit> {
        val rent = item()
        capture(25_000_00, "LANDLORD", due - 1)

        assertThat(recurring.findMatch(rent, due)).isInstanceOf(RecurringMatch.Candidate::class.java)
    }

    /** Law 1: a recurring candidate is written once per due date, as RECURRING, and never reaches the ledger. */
    @Test
    fun writeCandidate_isOnePendingRow_andNoEntry() = runBlocking<Unit> {
        val rent = item()

        val first = recurring.writeCandidate(rent, due)
        val second = recurring.writeCandidate(rent, due)

        assertThat(first).isNotNull()
        assertThat(second).isEqualTo(first)
        val row = requireNotNull(vault.database.pendingTransactionDao().byId(requireNotNull(first)))
        assertThat(row.source).isEqualTo(EntrySource.RECURRING)
        assertThat(row.status).isEqualTo(PendingStatus.PENDING)
        assertThat(row.rawRefId).isEqualTo(DefaultRecurringRepository.referenceOf(rent.id, due))
        assertThat(vault.database.ledgerEntryDao().allForLedger(LedgerType.DEBIT)).isEmpty()
    }

    /** Its own candidate is not something captured: a due date never matches itself. */
    @Test
    fun aRecurringCandidate_neverMatchesADueDate() = runBlocking<Unit> {
        val rent = item()
        recurring.writeCandidate(rent, due)

        assertThat(recurring.findMatch(rent, due)).isNull()
    }

    @Test
    fun aMerchantMerge_takesItsItemsAlong() = runBlocking<Unit> {
        val rent = item()

        vault.merchants.merge(landlord.id, grocer.id).success()

        assertThat(recurring.observeItems().first().single { it.id == rent.id }.merchantId).isEqualTo(grocer.id)
    }

    @Test
    fun deletingAnItem_takesItsLog_andLeavesItsEntries() = runBlocking<Unit> {
        val rent = item()
        approve(LedgerType.DEBIT, 25_000_00, landlord.id, due)
        val match = recurring.findMatch(rent, due) as RecurringMatch.Entry
        recurring.record(RecurringOccurrence(rent.id, due, OccurrenceOutcome.MATCHED, null, match.id, null))

        recurring.delete(rent.id)

        assertThat(vault.database.recurringDao().allOccurrences()).isEmpty()
        assertThat(vault.database.ledgerEntryDao().allForLedger(LedgerType.DEBIT)).hasSize(1)
    }

    private suspend fun item(
        name: String = "Rent",
        amount: Long = 25_000_00,
        amountVaries: Boolean = false,
    ): RecurringItem {
        val result = recurring.create(
            RecurringDetails(
                name = name, ledger = LedgerType.DEBIT, amount = Money(amount), amountVaries = amountVaries,
                merchantId = landlord.id, categoryId = null, subcategoryId = null, cadence = Cadence.MONTHLY,
                everyDays = null, firstDue = due, remindDaysBefore = 1, toInbox = true,
            ),
        )
        assertThat(result).isInstanceOf(RecurringResult.Success::class.java)
        return (result as RecurringResult.Success).value
    }

    private suspend fun approve(book: LedgerType, amount: Long, merchantId: String?, on: Int) {
        val result = vault.ledger.approve(
            ApprovalRequest(
                ledger = book,
                amount = Money(amount),
                occurredAt = noonOf(on),
                assignment = EntryAssignment(merchantId = merchantId),
            ),
        )
        assertThat(result).isInstanceOf(LedgerResult.Success::class.java)
    }

    /** A bank SMS's candidate, as the capture pipeline writes it. */
    private suspend fun capture(amount: Long, merchantRaw: String, on: Int) {
        val ref = "sms-${vault.ids.generate()}"
        val extracted = ExtractedTransaction(
            amount = Money(amount), currency = "INR", direction = ExtractedDirection.DEBIT,
            merchantRaw = merchantRaw, occurredAt = noonOf(on), confidence = 0.9,
        )
        val outcome = writer.write(
            vault.database,
            ref,
            PendingCandidate(EntrySource.SMS, extracted, DedupeKey.compute(extracted, ref)),
            verdict = null,
            duplicates = PendingCandidateWriter.Duplicates.Decided(null),
        )
        assertThat(outcome).isInstanceOf(PendingWriteOutcome.Created::class.java)
    }

    private fun noonOf(day: Int) =
        LocalDate.ofEpochDay(day.toLong()).atTime(12, 0).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()

    private fun <T> TaxonomyResult<T>.success(): T {
        assertThat(this).isInstanceOf(TaxonomyResult.Success::class.java)
        return (this as TaxonomyResult.Success).value
    }
}
