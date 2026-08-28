package com.ledgerflow.core.data.inbox

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.ledgerflow.core.data.ingest.ExtractedTransactionJson
import com.ledgerflow.core.data.ledger.LedgerTestVault
import com.ledgerflow.core.database.entity.PendingTransactionEntity
import com.ledgerflow.core.domain.inbox.InboxFilter
import com.ledgerflow.core.domain.ingest.ExtractedDirection
import com.ledgerflow.core.domain.ingest.ExtractedTransaction
import com.ledgerflow.core.model.EntrySource
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
 * **Every filter's count equals its list.** The Inbox chip row depends on it.
 *
 * A chip is drawn when its count is non-zero, so the two disagreeing has two
 * failure modes and one of them is serious: a count that reads low hides a
 * filter that *has* rows, which is §5.1's silent drop arriving through the
 * filter bar rather than through the parser. The other is a chip that opens an
 * empty screen.
 *
 * They are separate SQL statements — `observePending` excludes suppressed rows
 * and `observeWithStatus` deliberately does not — so "they look alike" is not
 * evidence. This runs both against a seeded database and compares.
 *
 * Instrumented for the reason the rest of this package is: the asymmetry lives
 * in SQL, and a fake would agree with whichever reading the test author had in
 * mind. The seeded rows below are chosen to break a naive implementation:
 * **a row that is both suppressed and discarded**, which belongs in *both* of
 * those filters and in neither `PENDING`.
 */
@RunWith(AndroidJUnit4::class)
class InboxFilterCountsMatchTest {

    private val vault = LedgerTestVault("lf_inbox_counts_test")
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
    ) {
        vault.session.requireDatabase().pendingTransactionDao().insert(
            PendingTransactionEntity(
                id = id,
                source = EntrySource.SMS,
                dedupeKey = "6900|DEBIT|$id",
                suppressedById = suppressedById,
                rawRefId = null,
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
                approvedEntryId = null,
            ),
        )
    }

    /** The population every assertion below runs against. */
    private suspend fun seedEveryShape() {
        seed("live-1")
        seed("live-2")
        seed("winner")
        seed("suppressed", suppressedById = "winner")
        // Both suppressed AND discarded -- in two filters at once, in PENDING
        // never. The row a naive count gets wrong.
        seed("suppressed-and-discarded", PendingStatus.DISCARDED, suppressedById = "winner")
        seed("discarded", PendingStatus.DISCARDED)
        seed("approved", PendingStatus.APPROVED)
        seed("failed", PendingStatus.FAILED)
    }

    @Test
    fun everyFiltersCount_equalsTheSizeOfItsOwnList() = runBlocking<Unit> {
        seedEveryShape()

        val counts = repository.observeCounts().first()

        InboxFilter.entries.forEach { filter ->
            val listed = repository.observe(filter).first().size
            assertThat(counts[filter]).isEqualTo(listed)
        }
    }

    /**
     * ...and the population really does exercise each one.
     *
     * Without this, the test above passes on an empty database — every count
     * zero, every list empty, nothing compared. That is the shape of guard that
     * looks green and has never been asked a question.
     */
    @Test
    fun theSeededRows_reachEveryFilter() = runBlocking<Unit> {
        seedEveryShape()

        val counts = repository.observeCounts().first()

        // Three live and un-suppressed: live-1, live-2, winner.
        assertThat(counts[InboxFilter.PENDING]).isEqualTo(3)
        // Both suppressed rows, whatever their status.
        assertThat(counts[InboxFilter.SUPPRESSED]).isEqualTo(2)
        // Both discarded rows, including the one that is also suppressed.
        assertThat(counts[InboxFilter.DISCARDED]).isEqualTo(2)
        assertThat(counts[InboxFilter.FAILED]).isEqualTo(1)
    }

    /**
     * An empty queue reports zero for everything, not an absent key.
     *
     * The chip row reads `counts[filter] ?: 0`, so a missing entry would behave
     * like a zero today — but it would also make "no chip" and "no answer yet"
     * the same state, and the row would flicker on first load.
     */
    @Test
    fun anEmptyQueue_reportsZeroForEveryFilter() = runBlocking<Unit> {
        val counts = repository.observeCounts().first()

        assertThat(counts.keys).containsExactlyElementsIn(InboxFilter.entries)
        assertThat(counts.values.toSet()).containsExactly(0)
    }

    /**
     * A locked vault answers zero rather than throwing.
     *
     * The Inbox is a screen and can be composed while the vault closes; a chip
     * row that threw there would crash the app rather than empty itself. Reads
     * follow `whenUnlocked()` for exactly this, and the count flow is a read.
     */
    @Test
    fun withTheVaultClosed_reportsZeroRatherThanThrowing() = runBlocking<Unit> {
        seedEveryShape()
        vault.session.close()

        val counts = repository.observeCounts().first()

        assertThat(counts.values.toSet()).containsExactly(0)
    }
}
