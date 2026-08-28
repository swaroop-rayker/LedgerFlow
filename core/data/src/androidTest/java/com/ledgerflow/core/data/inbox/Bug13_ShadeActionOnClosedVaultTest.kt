package com.ledgerflow.core.data.inbox

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.ledgerflow.core.data.ingest.ExtractedTransactionJson
import com.ledgerflow.core.data.ledger.LedgerTestVault
import com.ledgerflow.core.database.entity.PendingTransactionEntity
import com.ledgerflow.core.domain.ingest.ExtractedDirection
import com.ledgerflow.core.domain.ingest.ExtractedTransaction
import com.ledgerflow.core.domain.ledger.ApprovalRequest
import com.ledgerflow.core.domain.ledger.LedgerResult
import com.ledgerflow.core.model.EntryOrigin
import com.ledgerflow.core.model.EntrySource
import com.ledgerflow.core.model.LedgerType
import com.ledgerflow.core.model.Money
import com.ledgerflow.core.model.PendingStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * **BUG13 — a notification action found the vault shut and said nothing.**
 *
 * §2.4's bug, one layer up. `d88ca85` gave `DefaultRawIngestRepository` a
 * background unlock so an SMS arriving with no Activity alive still reached
 * disk. `DefaultPendingRepository` — the repository every *shade action* goes
 * through — was not touched, and its writes call `requireDatabase()` inside a
 * `runCatching { }.getOrDefault(false)`. With the app closed:
 *
 * - `discard(id)` threw, was swallowed, and returned `false`. The row stayed
 *   `PENDING`, the notification was dismissed by the system anyway, and nothing
 *   anywhere recorded that the user's tap had been dropped on the floor.
 * - `find(id)` returned null, so `ApprovePendingUseCase` refused with
 *   `InboxError.NotFound` — "no such candidate" about a candidate sitting in
 *   the table.
 *
 * **[findApprovedEntryId] is the dangerous one, and is why this covers all five
 * doors rather than the two the shade calls directly.** It is the idempotency
 * guard across the approval's two writes, and on a locked vault it returned
 * null — which is indistinguishable from "this candidate has no entry yet".
 * Fixing `find` alone would have converted a bug that did nothing into a bug
 * that writes a **second `ledger_entry` for one payment**, which is the exact
 * duplicate the entire ingest pipeline exists to prevent.
 *
 * §7 permits the unlock and does not merely tolerate it: the DEK-wrapping key
 * is forbidden `setUserAuthenticationRequired(true)` precisely so a Keystore
 * unwrap needs no user present. No new wrap and no new key material — it is the
 * same `openOnLaunch()` the UI calls.
 *
 * Its own database and its own Keystore alias, never the app's (BUG1(e)).
 */
@RunWith(AndroidJUnit4::class)
class Bug13_ShadeActionOnClosedVaultTest {

    private companion object {
        const val CANDIDATE_ID = "pending-bug13"
    }

    private val vault = LedgerTestVault("lf_bug13_test")
    private lateinit var repository: DefaultPendingRepository

    @Before
    fun setUp() = runBlocking<Unit> {
        vault.open()
        repository = DefaultPendingRepository(vault.session, vault.clock, Dispatchers.IO)
        seedCandidate(CANDIDATE_ID)
    }

    @After
    fun tearDown() = vault.close()

    /**
     * The state the whole bug lives in.
     *
     * `close()` is not a wipe — the database file and both key wraps are
     * untouched. It is exactly what a notification action wakes up into once
     * the user has swiped the app away.
     */
    private suspend fun closeTheVault() = vault.session.close()

    private suspend fun seedCandidate(id: String) {
        vault.session.requireDatabase().pendingTransactionDao().insert(
            PendingTransactionEntity(
                id = id,
                source = EntrySource.SMS,
                dedupeKey = "6900|DEBIT",
                suppressedById = null,
                rawRefId = null,
                extractedJson = ExtractedTransactionJson.encode(
                    ExtractedTransaction(
                        amount = Money(6_900L),
                        currency = "INR",
                        direction = ExtractedDirection.DEBIT,
                        merchantRaw = "SWIGGY",
                        confidence = 0.9,
                    ),
                ),
                confidence = 0.9,
                status = PendingStatus.PENDING,
                needsManualFill = false,
                createdAt = vault.now,
                reviewedAt = null,
                approvedEntryId = null,
            ),
        )
    }

    // ── The bug ─────────────────────────────────────────────────────────────

    @Test
    fun find_withVaultClosed_returnsTheCandidateRatherThanNull() = runBlocking<Unit> {
        closeTheVault()

        val found = repository.find(CANDIDATE_ID)

        assertThat(found).isNotNull()
        assertThat(found?.id).isEqualTo(CANDIDATE_ID)
        assertThat(found?.extracted?.amount).isEqualTo(Money(6_900L))
    }

    @Test
    fun discard_withVaultClosed_discardsRatherThanSilentlyFailing() = runBlocking<Unit> {
        closeTheVault()

        val discarded = repository.discard(CANDIDATE_ID)

        assertThat(discarded).isTrue()
        // That the write actually landed, rather than the boolean merely being
        // true: the defect was a swallowed exception that read as a clean false.
        assertThat(repository.find(CANDIDATE_ID)?.status).isEqualTo(PendingStatus.DISCARDED)
    }

    @Test
    fun restore_withVaultClosed_restoresRatherThanSilentlyFailing() = runBlocking<Unit> {
        closeTheVault()
        repository.discard(CANDIDATE_ID)

        val restored = repository.restore(CANDIDATE_ID)

        assertThat(restored).isTrue()
        assertThat(repository.find(CANDIDATE_ID)?.status).isEqualTo(PendingStatus.PENDING)
    }

    @Test
    fun markApproved_withVaultClosed_recordsTheEntryRatherThanSilentlyFailing() =
        runBlocking<Unit> {
            closeTheVault()

            val marked = repository.markApproved(CANDIDATE_ID, "entry-bug13")

            assertThat(marked).isTrue()
            val found = repository.find(CANDIDATE_ID)
            assertThat(found?.status).isEqualTo(PendingStatus.APPROVED)
            assertThat(found?.approvedEntryId).isEqualTo("entry-bug13")
        }

    /**
     * The idempotency guard has to be able to answer, or it is worse than absent.
     *
     * A real committed entry is written **while the vault is open**, carrying
     * this candidate as its `source_ref_id`, and only then is the vault closed.
     * So the correct answer afterwards is that entry's id, and null — the value
     * the swallowed throw produced — is now distinguishable from it. Without a
     * committed entry to find, this test would pass on the broken code, because
     * "threw before asking" and "asked and was told no" are the same null.
     */
    @Test
    fun findApprovedEntryId_withVaultClosed_findsTheCommittedEntry() = runBlocking<Unit> {
        val entry = vault.ledger.approve(
            ApprovalRequest(
                ledger = LedgerType.DEBIT,
                amount = Money(6_900L),
                occurredAt = vault.now,
                origin = EntryOrigin(EntrySource.SMS, refId = CANDIDATE_ID),
            ),
        )
        val entryId = (entry as LedgerResult.Success).value.id

        closeTheVault()

        assertThat(repository.findApprovedEntryId(CANDIDATE_ID)).isEqualTo(entryId)
    }
}
