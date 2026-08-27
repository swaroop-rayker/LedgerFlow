package com.ledgerflow.feature.inbox

import com.google.common.truth.Truth.assertThat
import com.ledgerflow.core.domain.inbox.InboxError
import com.ledgerflow.core.domain.usecase.ApprovalEdits
import com.ledgerflow.core.domain.usecase.ApprovePendingUseCase
import com.ledgerflow.core.domain.usecase.ApproveTransactionUseCase
import com.ledgerflow.core.domain.usecase.InboxException
import com.ledgerflow.core.domain.inbox.PendingTransaction
import com.ledgerflow.core.domain.ingest.ExtractedDirection
import com.ledgerflow.core.domain.ingest.ExtractedTransaction
import com.ledgerflow.core.model.EntrySource
import com.ledgerflow.core.model.LedgerType
import com.ledgerflow.core.model.Money
import com.ledgerflow.core.model.PendingStatus
import com.ledgerflow.core.testing.inbox.FakePendingRepository
import com.ledgerflow.core.testing.ledger.FakeLedgerRepository
import com.ledgerflow.core.testing.taxonomy.FakeMerchantRepository
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * The door from the Inbox to the ledger (SPEC.md §5.1). P2-6.
 *
 * **Law 1's subject.** These are about what may and may not cross that door: an
 * automated source cannot, a human tap can, and one tap must not become two
 * entries.
 *
 * Tested from `:feature:inbox` rather than beside the use case in `:core:domain`,
 * because the fakes live in `:core:testing` and `:core:testing` already depends
 * on `:core:domain` -- a test-scope dependency the other way would close that
 * loop. The feature that drives the door is a reasonable place to prove it only
 * opens once.
 */
class ApprovePendingUseCaseTest {

    private val pending = FakePendingRepository()
    private val merchants = FakeMerchantRepository()
    private val ledger = FakeLedgerRepository()
    private val approve = ApprovePendingUseCase(
        repository = pending,
        merchants = merchants,
        approveTransaction = ApproveTransactionUseCase(ledger),
    )

    private fun candidate(
        id: String = "p1",
        amountMinor: Long? = 200L,
        direction: ExtractedDirection = ExtractedDirection.DEBIT,
        merchantRaw: String? = "RAMESH KUMAR",
        status: PendingStatus = PendingStatus.PENDING,
    ) = PendingTransaction(
        id = id,
        source = EntrySource.SMS,
        extracted = ExtractedTransaction(
            amount = amountMinor?.let(::Money),
            direction = direction,
            merchantRaw = merchantRaw,
            confidence = 0.9,
        ),
        confidence = 0.9,
        status = status,
        needsManualFill = false,
        suppressedById = null,
        createdAt = 1_787_810_214_627L,
        reviewedAt = null,
        approvedEntryId = null,
    )

    @Test
    fun approve_commitsTheEntryAndMarksTheCandidate() = runTest {
        pending.put(candidate())

        val result = approve("p1")

        assertThat(result.isSuccess).isTrue()
        val row = pending.get("p1")
        assertThat(row?.status).isEqualTo(PendingStatus.APPROVED)
        assertThat(row?.approvedEntryId).isEqualTo(result.getOrNull())
    }

    /**
     * **§5.1's create-the-merchant rule, at the moment P2-4 decided it happens.**
     *
     * The first time a shop appears there is by definition no row for it, and
     * treating that as a failure would make the pipeline reject exactly the
     * transactions it exists to capture.
     */
    @Test
    fun approve_createsTheMerchantItHasNeverSeen() = runTest {
        pending.put(candidate(merchantRaw = "A SHOP THAT DID NOT EXIST"))

        assertThat(approve("p1").isSuccess).isTrue()

        assertThat(merchants.created)
            .contains("A SHOP THAT DID NOT EXIST")
    }

    /**
     * ...and creates it **only now**, not at parse time (P2-4's decision).
     *
     * A candidate carries a raw name until someone agrees it is real. Otherwise
     * every discarded candidate and every garbled extraction would leave a
     * permanent taxonomy row behind.
     */
    @Test
    fun aCandidateThatIsNeverApproved_leavesNoMerchantBehind() = runTest {
        pending.put(candidate(merchantRaw = "GARBLED*XX9982"))

        assertThat(merchants.created).isEmpty()
    }

    /**
     * **The double-approve guard.**
     *
     * Committing the entry and marking the candidate are two writes in two
     * repositories and cannot share a transaction. If the process dies between
     * them the entry exists and the row is still `PENDING` — and a second
     * approval must complete that state rather than write a second entry for one
     * payment, which is the duplicate the whole pipeline exists to prevent.
     */
    @Test
    fun approve_whenAnEntryAlreadyExistsForTheCandidate_doesNotWriteASecond() = runTest {
        pending.put(candidate())
        pending.approvedEntries["p1"] = "entry-from-a-half-finished-approval"

        val result = approve("p1")

        assertThat(result.getOrNull()).isEqualTo("entry-from-a-half-finished-approval")
        // The ledger was never asked. One payment, one entry.
        assertThat(ledger.approved).isEmpty()
        assertThat(pending.get("p1")?.status).isEqualTo(PendingStatus.APPROVED)
    }

    @Test
    fun approve_refusesACandidateSomeoneAlreadyReviewed() = runTest {
        pending.put(candidate(status = PendingStatus.DISCARDED))

        val error = (approve("p1").exceptionOrNull() as InboxException).error

        assertThat(error).isEqualTo(InboxError.AlreadyReviewed(PendingStatus.DISCARDED))
        assertThat(ledger.approved).isEmpty()
    }

    /**
     * **Law 2: the book is never guessed.**
     *
     * A rule that matched an amount but no verb leaves the direction UNKNOWN.
     * Filing that as spend on a coin flip would put income in the wrong ledger,
     * and the two never meet to correct it.
     */
    @Test
    fun approve_refusesWhenTheParserCouldNotReadTheDirection() = runTest {
        pending.put(candidate(direction = ExtractedDirection.UNKNOWN))

        val error = (approve("p1").exceptionOrNull() as InboxException).error

        assertThat(error).isEqualTo(InboxError.NotReviewable)
        assertThat(ledger.approved).isEmpty()
    }

    /** ...but the user choosing one on the review screen is exactly what unblocks it. */
    @Test
    fun approve_acceptsABookTheUserChose() = runTest {
        pending.put(candidate(direction = ExtractedDirection.UNKNOWN))

        val result = approve("p1", ApprovalEdits(ledger = LedgerType.CREDIT))

        assertThat(result.isSuccess).isTrue()
        assertThat(ledger.approved.single().ledger).isEqualTo(LedgerType.CREDIT)
    }

    @Test
    fun approve_refusesACandidateWithNoAmount() = runTest {
        pending.put(candidate(amountMinor = null))

        val error = (approve("p1").exceptionOrNull() as InboxException).error

        assertThat(error).isEqualTo(InboxError.NotReviewable)
    }

    @Test
    fun approve_refusesAnIdThatNamesNothing() = runTest {
        val error = (approve("nope").exceptionOrNull() as InboxException).error

        assertThat(error).isEqualTo(InboxError.NotFound)
    }

    /**
     * The entry carries its way back to the message that produced it.
     *
     * `source_ref_id` is not decoration: it is what `findApprovedEntryId` looks
     * the entry up by, so it is the other half of the guard above.
     */
    @Test
    fun approve_recordsTheCandidateAsTheEntrysOrigin() = runTest {
        pending.put(candidate())

        approve("p1")

        val origin = ledger.approved.single().origin
        assertThat(origin.source).isEqualTo(EntrySource.SMS)
        assertThat(origin.refId).isEqualTo("p1")
    }

    /** The user's edits win over what the parser read. */
    @Test
    fun approve_prefersTheUsersEditsToTheExtraction() = runTest {
        pending.put(candidate(amountMinor = 200L))

        approve("p1", ApprovalEdits(amount = Money(50_000L), note = "corrected"))

        val request = ledger.approved.single()
        assertThat(request.amount).isEqualTo(Money(50_000L))
        assertThat(request.note).isEqualTo("corrected")
    }
}
