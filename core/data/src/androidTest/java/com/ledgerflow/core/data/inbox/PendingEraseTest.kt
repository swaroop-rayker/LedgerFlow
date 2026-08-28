package com.ledgerflow.core.data.inbox

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.ledgerflow.core.data.ingest.ExtractedTransactionJson
import com.ledgerflow.core.data.ledger.LedgerTestVault
import com.ledgerflow.core.database.entity.PendingTransactionEntity
import com.ledgerflow.core.database.entity.SmsRawEntity
import com.ledgerflow.core.domain.inbox.InboxFilter
import com.ledgerflow.core.domain.ingest.ExtractedDirection
import com.ledgerflow.core.domain.ingest.ExtractedTransaction
import com.ledgerflow.core.model.EntrySource
import com.ledgerflow.core.model.Money
import com.ledgerflow.core.model.PendingStatus
import com.ledgerflow.core.model.RawParseStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * **Erasing candidates for good — the Inbox's only irreversible operation.**
 * CHANGE#1.
 *
 * Instrumented rather than a JVM test because **what is under test is the two
 * predicates in the SQL**, not the Kotlin around them. A fake would assert the
 * guard we believe we wrote; this asserts the guard SQLite actually applies,
 * which is the same reason the sender-allowlist regression test is instrumented
 * (`GLOB` semantics are SQLite's).
 *
 * The two guards, and why each is load-bearing:
 *
 * - `approved_entry_id IS NULL`. An approved candidate is a `ledger_entry`'s
 *   only record of where it came from, and `findApprovedEntryId`'s half of the
 *   idempotency guard. **A row can be both suppressed and approved**, so this
 *   cannot be inferred from the filter the user is looking at — which is
 *   precisely the case the UI would never show and the statement must still
 *   refuse.
 * - `status IN ('DISCARDED','FAILED') OR suppressed_by_id IS NOT NULL`. A live
 *   `PENDING` candidate is the queue Law 1 protects. The path to destroying one
 *   is to discard it first, which is reversible for 30 days.
 *
 * And the thing that is *not* destroyed: the captured message. §5.1 makes a
 * rejected candidate information — the material a future parser rule is written
 * against, and what P2-9's corpus is made of — so tidying the Inbox must not
 * throw away a message that cost a real payment to obtain (owner decision).
 */
@RunWith(AndroidJUnit4::class)
class PendingEraseTest {

    private val vault = LedgerTestVault("lf_erase_pending_test")
    private lateinit var repository: DefaultPendingRepository

    @Before
    fun setUp() = runBlocking<Unit> {
        vault.open()
        repository = DefaultPendingRepository(vault.session, vault.clock, Dispatchers.IO)
    }

    @After
    fun tearDown() = vault.close()

    private suspend fun seed(
        id: String,
        status: PendingStatus = PendingStatus.PENDING,
        suppressedById: String? = null,
        approvedEntryId: String? = null,
        rawRefId: String? = null,
    ) {
        vault.session.requireDatabase().pendingTransactionDao().insert(
            PendingTransactionEntity(
                id = id,
                source = EntrySource.SMS,
                dedupeKey = "6900|DEBIT|$id",
                suppressedById = suppressedById,
                rawRefId = rawRefId,
                extractedJson = ExtractedTransactionJson.encode(
                    ExtractedTransaction(
                        amount = Money(6_900L),
                        direction = ExtractedDirection.DEBIT,
                        merchantRaw = "SWIGGY",
                        confidence = 0.9,
                    ),
                ),
                confidence = 0.9,
                status = status,
                needsManualFill = false,
                createdAt = vault.now,
                reviewedAt = null,
                approvedEntryId = approvedEntryId,
            ),
        )
    }

    private suspend fun ids(): Set<String> =
        vault.session.requireDatabase().pendingTransactionDao().all().map { it.id }.toSet()

    // ── What it erases ──────────────────────────────────────────────────────

    @Test
    fun purge_erasesADiscardedCandidate() = runBlocking<Unit> {
        seed("discarded", status = PendingStatus.DISCARDED)

        assertThat(repository.erase(listOf("discarded"))).isEqualTo(1)
        assertThat(ids()).doesNotContain("discarded")
    }

    @Test
    fun purge_erasesASuppressedCandidateEvenThoughItIsStillPending() = runBlocking<Unit> {
        // The common shape: suppression is not a status, so this row's status
        // is PENDING while `suppressed_by_id` is what makes it a duplicate.
        seed("winner")
        seed("loser", suppressedById = "winner")

        assertThat(repository.erase(listOf("loser"))).isEqualTo(1)
        assertThat(ids()).containsExactly("winner")
    }

    @Test
    fun purgeAll_erasesEverythingOnThatFilterAndNothingElse() = runBlocking<Unit> {
        seed("live")
        seed("gone-1", status = PendingStatus.DISCARDED)
        seed("gone-2", status = PendingStatus.DISCARDED)
        seed("failed", status = PendingStatus.FAILED)

        assertThat(repository.eraseAll(InboxFilter.DISCARDED)).isEqualTo(2)
        assertThat(ids()).containsExactly("live", "failed")
    }

    // ── What it refuses ─────────────────────────────────────────────────────

    /**
     * **The audit trail is untouchable, whoever asks.**
     *
     * The id is passed directly, as a caller with a stale selection or a future
     * bulk action would. `approved_entry_id IS NULL` is what makes this affect
     * no rows rather than destroy the only link from a committed entry back to
     * the message that produced it.
     */
    @Test
    fun purge_refusesAnApprovedCandidate() = runBlocking<Unit> {
        seed("approved", status = PendingStatus.APPROVED, approvedEntryId = "entry-1")

        assertThat(repository.erase(listOf("approved"))).isEqualTo(0)
        assertThat(ids()).contains("approved")
    }

    /**
     * **The case no filter would ever show, and the reason the guard is in SQL.**
     *
     * A candidate can be approved *and* suppressed — §3.1 keeps the higher
     * confidence extraction, and the loser may already have been approved
     * before the winner arrived. It appears under "Suppressed", so an "Erase
     * all" there would sweep it up on a status check alone. Only the
     * `approved_entry_id` predicate saves it.
     */
    @Test
    fun purgeAll_suppressed_refusesOneThatWasAlsoApproved() = runBlocking<Unit> {
        seed("winner")
        seed(
            "approved-loser",
            status = PendingStatus.APPROVED,
            suppressedById = "winner",
            approvedEntryId = "entry-1",
        )
        seed("plain-loser", suppressedById = "winner")

        assertThat(repository.eraseAll(InboxFilter.SUPPRESSED)).isEqualTo(1)
        assertThat(ids()).containsExactly("winner", "approved-loser")
    }

    /** A live candidate is never erasable by id. Discard it first. */
    @Test
    fun purge_refusesALivePendingCandidate() = runBlocking<Unit> {
        seed("live")

        assertThat(repository.erase(listOf("live"))).isEqualTo(0)
        assertThat(ids()).contains("live")
    }

    /**
     * There is no "empty the queue".
     *
     * `PENDING` is the queue Law 1 is about; a bulk erase over work the user has
     * not looked at yet is not an operation this app offers, and the repository
     * answers zero rather than falling through to a status match.
     */
    @Test
    fun purgeAll_pending_erasesNothing() = runBlocking<Unit> {
        seed("live-1")
        seed("live-2")

        assertThat(repository.eraseAll(InboxFilter.PENDING)).isEqualTo(0)
        assertThat(ids()).containsExactly("live-1", "live-2")
    }

    // ── What survives it ────────────────────────────────────────────────────

    /**
     * **The captured message stays** (owner decision, CHANGE#1).
     *
     * Only the candidate goes. The body expires on D-09's 90-day retention as
     * it always would, and until then it is still the fixture material a future
     * rule is written against — which is the thing that cannot be recovered
     * without making another real payment.
     */
    @Test
    fun purge_leavesTheCapturedMessageOnDisk() = runBlocking<Unit> {
        val database = vault.session.requireDatabase()
        database.smsRawDao().insert(
            SmsRawEntity(
                id = "raw-1",
                sender = "VM-HDFCBK",
                body = "Sent Rs.69.00 From HDFC Bank A/C *1234 To SWIGGY",
                bodyHash = "hash-1",
                receivedAt = vault.now,
                simSlot = null,
                parseStatus = RawParseStatus.PARSED,
                matchedRuleId = null,
                retentionExpiresAt = vault.now + 1_000_000L,
            ),
        )
        seed("discarded", status = PendingStatus.DISCARDED, rawRefId = "raw-1")

        assertThat(repository.erase(listOf("discarded"))).isEqualTo(1)

        val raw = database.smsRawDao().byId("raw-1")
        assertThat(raw).isNotNull()
        assertThat(raw?.body).contains("SWIGGY")
    }

    /**
     * **Erasing a candidate does not make the worker rebuild it.**
     *
     * The queue is "raw rows still at `CAPTURED`", so a row that already carries
     * a verdict is never handed back to the parser. Asserted rather than reasoned
     * about, because the failure would be quiet and cyclical: erase a duplicate,
     * and the next captured message brings it straight back.
     */
    @Test
    fun purge_doesNotReturnTheRawRowToTheParseQueue() = runBlocking<Unit> {
        val database = vault.session.requireDatabase()
        database.smsRawDao().insert(
            SmsRawEntity(
                id = "raw-1",
                sender = "VM-HDFCBK",
                body = "Sent Rs.69.00 To SWIGGY",
                bodyHash = "hash-1",
                receivedAt = vault.now,
                simSlot = null,
                parseStatus = RawParseStatus.PARSED,
                matchedRuleId = "rule-1",
                retentionExpiresAt = vault.now + 1_000_000L,
            ),
        )
        seed("discarded", status = PendingStatus.DISCARDED, rawRefId = "raw-1")

        repository.erase(listOf("discarded"))

        val queued = database.smsRawDao().withStatus(RawParseStatus.CAPTURED, limit = 100)
        assertThat(queued).isEmpty()
    }

    /**
     * A dangling `suppressed_by_id` is left dangling, not cascaded.
     *
     * The entity says so explicitly: the winner can be erased and a cascade
     * would then delete the evidence that a duplicate was ever suppressed. A
     * dangling id reads as "suppressed, winner gone", which is true.
     */
    @Test
    fun purge_erasingAWinner_leavesItsLoserAlone() = runBlocking<Unit> {
        seed("winner", status = PendingStatus.DISCARDED)
        seed("loser", suppressedById = "winner")

        assertThat(repository.erase(listOf("winner"))).isEqualTo(1)

        val loser = vault.session.requireDatabase().pendingTransactionDao().byId("loser")
        assertThat(loser).isNotNull()
        assertThat(loser?.suppressedById).isEqualTo("winner")
    }
}
