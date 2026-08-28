package com.ledgerflow.core.domain.usecase

import com.ledgerflow.core.domain.inbox.InboxError
import com.ledgerflow.core.domain.inbox.InboxFilter
import com.ledgerflow.core.domain.inbox.PendingRepository
import com.ledgerflow.core.domain.inbox.PendingTransaction
import com.ledgerflow.core.domain.ledger.ApprovalRequest
import com.ledgerflow.core.domain.ledger.LedgerResult
import com.ledgerflow.core.domain.ledger.NewLineItem
import com.ledgerflow.core.domain.taxonomy.MerchantRepository
import com.ledgerflow.core.domain.taxonomy.TaxonomyResult
import com.ledgerflow.core.model.EntryAssignment
import com.ledgerflow.core.model.EntryOrigin
import com.ledgerflow.core.model.LedgerType
import com.ledgerflow.core.model.Money
import com.ledgerflow.core.model.PendingStatus
import javax.inject.Inject
import kotlinx.coroutines.flow.Flow

/** The Inbox list for one filter (SPEC.md §5.1). */
public class ObservePendingUseCase @Inject constructor(
    private val repository: PendingRepository,
) {
    public operator fun invoke(filter: InboxFilter): Flow<List<PendingTransaction>> =
        repository.observe(filter)
}

/** The `Inbox (n)` badge on §9.3's speed dial. Pending only — see the port. */
public class ObservePendingCountUseCase @Inject constructor(
    private val repository: PendingRepository,
) {
    public operator fun invoke(): Flow<Int> = repository.observePendingCount()
}

/** One candidate, for the review screen and for a deep link (§5.1). */
public class GetPendingUseCase @Inject constructor(
    private val repository: PendingRepository,
) {
    public suspend operator fun invoke(id: String): PendingTransaction? = repository.find(id)
}

/**
 * §5.1's Discard: sets `DISCARDED` and keeps the row.
 *
 * Auditable and restorable for 30 days. A user rejecting a candidate is
 * information — it is what a future precision measurement and §5.1's rule test
 * bench are made of — so this is a state, not a delete.
 */
public class DiscardPendingUseCase @Inject constructor(
    private val repository: PendingRepository,
) {
    public suspend operator fun invoke(id: String): Boolean = repository.discard(id)
}

/** Undo, for the swipe snackbar and the Discarded filter. */
public class RestorePendingUseCase @Inject constructor(
    private val repository: PendingRepository,
) {
    public suspend operator fun invoke(id: String): Boolean = repository.restore(id)
}

/**
 * **Erases candidates for good.** The Inbox's only irreversible operation.
 *
 * The counterpart to `PurgeDeletedEntriesUseCase` for the approval queue, and
 * deliberately smaller: that one destroys committed money and compacts the file
 * afterwards, while this destroys rows the user has already rejected, already
 * seen suppressed, or that never produced a candidate worth keeping.
 *
 * **What it cannot touch is enforced in SQL, not here.** An approved candidate
 * is a `ledger_entry`'s only record of where it came from, and a live `PENDING`
 * one is the queue Law 1 exists to protect; both are excluded by the statement
 * itself, so no future caller can bypass the rule by not knowing about it.
 *
 * **Never reachable without a `Warning` confirmation naming the count** — the
 * same rule the bin's purge follows, for the same reason: it cannot be undone
 * and it is one tap away in a list of small controls.
 *
 * The raw messages stay (owner decision, CHANGE#1). Only candidates go.
 */
public class PurgePendingUseCase @Inject constructor(
    private val repository: PendingRepository,
) {

    /** Just the ones the user ticked. Returns how many were actually erased. */
    public suspend fun selected(ids: List<String>): Int = repository.purge(ids)

    /**
     * Everything the given filter lists.
     *
     * [InboxFilter.PENDING] erases nothing — see the port. An "erase all" on the
     * queue of things the user has not looked at yet is not an operation this
     * app offers.
     */
    public suspend fun all(filter: InboxFilter): Int = repository.purgeAll(filter)
}

/**
 * What the user decided on the review screen, over what the parser extracted.
 *
 * [merchantId] and [merchantName] are the two halves of §5.1's merchant rule.
 * An id means the user picked an existing merchant and nothing needs creating;
 * a name means the message carried a payee that matches nothing yet, and
 * `createOrGet` makes it real at approval. An id wins when both are present.
 */
public data class ApprovalEdits(
    val ledger: LedgerType? = null,
    val amount: Money? = null,
    val occurredAt: Long? = null,
    val merchantId: String? = null,
    val merchantName: String? = null,
    val categoryId: String? = null,
    val subcategoryId: String? = null,
    val paymentMethodId: String? = null,
    val note: String? = null,
    /**
     * Itemised lines (ADR-0018). Empty for a single-item candidate.
     *
     * An itemised entry files at line grain and carries no category of its own,
     * which is why [categoryId] and these are not both meaningful at once — the
     * review screen clears one when the user chooses the other.
     */
    val lineItems: List<NewLineItem> = emptyList(),
)

/**
 * Turns a reviewed candidate into a ledger entry (SPEC.md §5.1). P2-6.
 *
 * **This is the human act Law 1 exists to require**, and it is the *only* path
 * from `pending_transaction` to `ledger_entry`. It does not write to the ledger
 * itself: it composes [ApproveTransactionUseCase], which is Law 1's single
 * writer and stays so. `LedgerSingleWriterTest` guards that door and this
 * deliberately is not a second one.
 *
 * ## The merchant is created here, not at parse time
 *
 * §5.1 requires ingest to resolve `merchantRaw` through
 * [MerchantRepository.createOrGet] and forbids it failing for a merchant that
 * does not exist yet. *When* was the open question, settled at P2-4: here, at
 * approval. Resolving at parse time would create a permanent taxonomy row for
 * every candidate the user later discards and for every garbled merchant string
 * a rule ever mis-extracted. A candidate carries only a raw name until someone
 * has agreed it is real.
 *
 * **A merchant that cannot be created does not fail the approval.** §5.1 is
 * emphatic that no ingest path may refuse for a missing merchant; losing the
 * merchant on an entry the user asked for is strictly better than losing the
 * entry.
 *
 * ## Two writes, and the guard between them
 *
 * Committing the entry and marking the candidate approved live in different
 * repositories, so they cannot share a transaction without this reaching into
 * `ledger_entry`. If the process dies between them the entry exists and the row
 * is still `PENDING` — and approving again would write a *second* entry for one
 * payment, exactly the duplicate the ingest pipeline exists to prevent. So the
 * first thing an approval does is ask whether this candidate already produced an
 * entry; if it did, the half-finished state is completed rather than doubled.
 */
public class ApprovePendingUseCase @Inject constructor(
    private val repository: PendingRepository,
    private val merchants: MerchantRepository,
    private val approveTransaction: ApproveTransactionUseCase,
) {

    public suspend operator fun invoke(
        pendingId: String,
        edits: ApprovalEdits = ApprovalEdits(),
    ): Result<String> {
        val candidate = repository.find(pendingId)
            ?: return Result.failure(InboxException(InboxError.NotFound))

        return when (val preflight = preflight(candidate, edits)) {
            // A previous approval got half-way. Finish it rather than repeat it.
            is Preflight.Recovered -> {
                repository.markApproved(pendingId, preflight.entryId)
                Result.success(preflight.entryId)
            }

            is Preflight.Refused -> Result.failure(InboxException(preflight.error))
            is Preflight.Ready -> commit(pendingId, preflight.request)
        }
    }

    /**
     * Everything that has to be true before the ledger is asked, as one answer.
     *
     * The three outcomes are genuinely different and were worth naming: the
     * candidate already has an entry, the candidate cannot be approved, or here
     * is the request. Written as a `when` over conditions rather than a run of
     * early returns because the *order* matters — recovery is checked before
     * status, so a candidate left `PENDING` by a half-finished approval is
     * completed instead of being refused as unreviewable.
     */
    private suspend fun preflight(
        candidate: PendingTransaction,
        edits: ApprovalEdits,
    ): Preflight {
        val existing = repository.findApprovedEntryId(candidate.id)
        return when {
            existing != null -> Preflight.Recovered(existing)

            candidate.status != PendingStatus.PENDING ->
                Preflight.Refused(InboxError.AlreadyReviewed(candidate.status))

            else -> buildRequest(candidate, edits)
                ?.let(Preflight::Ready)
                ?: Preflight.Refused(InboxError.NotReviewable)
        }
    }

    /**
     * The user's edits over the parser's extraction, or null if the ledger
     * cannot be given what is left.
     *
     * A book and an amount are the two it cannot do without. **Neither is ever
     * guessed**: Law 2 keeps the ledgers apart precisely because nothing
     * reconciles them afterwards, so a direction the parser could not read has
     * to come from the person, not from a default.
     */
    private suspend fun buildRequest(
        candidate: PendingTransaction,
        edits: ApprovalEdits,
    ): ApprovalRequest? {
        val ledger = edits.ledger ?: candidate.extracted.direction.toLedgerOrNull() ?: return null
        val amount = edits.amount ?: candidate.extracted.amount ?: return null

        return ApprovalRequest(
            ledger = ledger,
            amount = amount,
            // The message's own time when it stated one, the capture time
            // otherwise. `created_at` is when the candidate was written, which
            // for a live capture is within seconds of the payment -- and unlike
            // a guessed date it is a fact about something that happened.
            occurredAt = edits.occurredAt ?: candidate.extracted.occurredAt ?: candidate.createdAt,
            assignment = EntryAssignment(
                categoryId = edits.categoryId,
                subcategoryId = edits.subcategoryId,
                merchantId = resolveMerchant(candidate, edits),
                paymentMethodId = edits.paymentMethodId,
            ),
            note = edits.note,
            lineItems = edits.lineItems,
            // The audit trail back to the message. `source_ref_id` is what
            // `findApprovedEntryId` looks the entry up by, so this is not
            // decoration -- it is the idempotency guard's other half.
            origin = EntryOrigin(candidate.source, refId = candidate.id),
        )
    }

    /**
     * §5.1's create-the-merchant rule, at the moment P2-4 decided it happens.
     *
     * **A merchant that cannot be created does not fail the approval.** §5.1 is
     * emphatic that no ingest path may refuse for a missing merchant, and losing
     * the merchant on an entry the user asked for is strictly better than losing
     * the entry.
     */
    private suspend fun resolveMerchant(
        candidate: PendingTransaction,
        edits: ApprovalEdits,
    ): String? {
        // A merchant the user picked needs no resolving -- it already exists,
        // and running it through `createOrGet` would look it up by name to find
        // the row we were handed the id of.
        edits.merchantId?.let { return it }

        val name = (edits.merchantName ?: candidate.extracted.merchantRaw)
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?: return null

        return when (val result = merchants.createOrGet(name, edits.categoryId)) {
            is TaxonomyResult.Success -> result.value.id
            is TaxonomyResult.Failure -> null
        }
    }

    /** The ledger write, and the record that it happened. */
    private suspend fun commit(pendingId: String, request: ApprovalRequest): Result<String> =
        when (val result = approveTransaction(request)) {
            is LedgerResult.Success -> {
                repository.markApproved(pendingId, result.value.id)
                Result.success(result.value.id)
            }

            is LedgerResult.Failure ->
                Result.failure(InboxException(InboxError.Rejected(result.error.toString())))
        }

    /** The three things [preflight] can conclude. */
    private sealed interface Preflight {
        data class Recovered(val entryId: String) : Preflight
        data class Refused(val error: InboxError) : Preflight
        data class Ready(val request: ApprovalRequest) : Preflight
    }
}

/**
 * Carries an [InboxError] through Kotlin's [Result].
 *
 * `Result<T>` at the boundary keeps the call site's `onSuccess`/`onFailure`
 * shape, and this preserves the typed reason inside it — CLAUDE.md §5 bans
 * exceptions as *control flow*, not as a payload that is never thrown.
 */
public class InboxException(public val error: InboxError) : Exception(error.toString())
