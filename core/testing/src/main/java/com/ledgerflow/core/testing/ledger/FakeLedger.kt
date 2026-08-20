package com.ledgerflow.core.testing.ledger

import androidx.paging.PagingData
import com.ledgerflow.core.domain.ledger.ApprovalRequest
import com.ledgerflow.core.domain.ledger.DraftRepository
import com.ledgerflow.core.domain.ledger.DraftSummary
import com.ledgerflow.core.domain.ledger.DraftSummaryFields
import com.ledgerflow.core.domain.ledger.DraftWrite
import com.ledgerflow.core.domain.ledger.EntryCombo
import com.ledgerflow.core.domain.ledger.EntryDraft
import com.ledgerflow.core.domain.ledger.LedgerError
import com.ledgerflow.core.domain.ledger.LedgerRepository
import com.ledgerflow.core.domain.ledger.LedgerResult
import com.ledgerflow.core.model.EntryOrigin
import com.ledgerflow.core.model.DeletedEntry
import com.ledgerflow.core.model.LedgerEntry
import com.ledgerflow.core.model.LedgerListItem
import com.ledgerflow.core.model.LedgerType
import com.ledgerflow.core.model.Money
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map

/**
 * A recording [LedgerRepository].
 *
 * Deliberately shallow on the *rules*. Every refusal
 * `ApproveTransactionUseCase` enforces is a statement about rows in other
 * tables, and it is verified against a real SQLCipher database in
 * `LedgerRepositoryInstrumentedTest`. Reimplementing that logic here would
 * produce a fake that passes tests the real repository fails -- the worst kind.
 *
 * What it does faithfully is record what was asked and return what it was told
 * to. That is what a ViewModel test needs: whether the amount reached the
 * request intact, whether the right book was used, whether a refusal became a
 * message the user can read.
 */
public class FakeLedgerRepository : LedgerRepository {

    /** Every approval attempt, in order. */
    public val approved: MutableList<ApprovalRequest> = mutableListOf()

    /** Scripted outcome. Null means "accept, and echo back a plausible entry". */
    public var approveResult: LedgerResult<LedgerEntry>? = null

    /** Per-ledger, so a test can prove the form really switched books. */
    public val combos: MutableMap<LedgerType, List<EntryCombo>> = mutableMapOf()

    /**
     * Per-ledger list contents.
     *
     * Keyed by book rather than held as one list with a filter, so a test that
     * seeds only DEBIT and reads CREDIT gets an empty page -- a fake that
     * filtered a shared list would pass even if the real query had lost its
     * ledger predicate, which is exactly the failure Law 2 is about.
     */
    public val entries: MutableMap<LedgerType, List<LedgerListItem>> = mutableMapOf()

    /** Null models a vault whose §7.4 gate never completed. */
    public var installBaseCurrency: String? = BASE_CURRENCY

    private val revision = MutableStateFlow(0)

    override suspend fun approve(request: ApprovalRequest): LedgerResult<LedgerEntry> {
        approved += request
        return approveResult ?: LedgerResult.Success(request.toEntry())
    }

    /** Every `since` this fake was asked for, so a test can prove the window. */
    public val windows: MutableList<Int> = mutableListOf()

    override fun observeEntries(
        ledger: LedgerType,
        since: Int,
    ): Flow<PagingData<LedgerListItem>> {
        windows += since
        // Filtered, not ignored: a fake that returned everything would let a
        // ViewModel test pass with the window wired to the wrong value.
        return revision.map { _ ->
            PagingData.from(entries[ledger].orEmpty().filter { it.localDate >= since })
        }
    }

    /**
     * Overridden per book when a test needs "has entries, but none in the
     * window". Null means "derive it from [entries]", which is what every
     * other test wants.
     */
    public val hasEntriesOverride: MutableMap<LedgerType, Boolean> = mutableMapOf()

    /** Every (book, id) this fake was asked to delete, in order. */
    public val deleted: MutableList<Pair<LedgerType, String>> = mutableListOf()

    /** Scripted refusal. Null means "accept and remove it from [entries]". */
    public var deleteResult: LedgerResult<Unit>? = null

    override suspend fun softDeleteEntry(ledger: LedgerType, id: String): LedgerResult<Unit> {
        deleted += ledger to id
        deleteResult?.let { return it }
        // Removed from that book only. A fake that dropped the id from every
        // book would pass a ViewModel test even if the real statement had lost
        // its ledger predicate, which is the failure Law 2 is about.
        entries[ledger] = entries[ledger].orEmpty().filterNot { it.id == id }
        revision.value += 1
        return LedgerResult.Success(Unit)
    }

    /** Soft-deleted rows this fake is holding, per book. */
    public val deletedCounts: MutableMap<LedgerType, Int> = mutableMapOf()

    /** Books purged, in order, so a test can prove both were swept. */
    public val purged: MutableList<LedgerType> = mutableListOf()

    public var compactions: Int = 0
        private set

    /** The bin, per book. Seeded by a test; mutated by restore and purge. */
    public val binned: MutableMap<LedgerType, List<DeletedEntry>> = mutableMapOf()

    /** Every (book, id) restored, in order. */
    public val restored: MutableList<Pair<LedgerType, String>> = mutableListOf()

    /** Every (book, id) destroyed from the bin, in order. */
    public val purgedEntries: MutableList<Pair<LedgerType, String>> = mutableListOf()

    override fun observeDeleted(ledger: LedgerType): Flow<List<DeletedEntry>> =
        revision.map { binned[ledger].orEmpty() }

    override suspend fun restoreEntry(ledger: LedgerType, id: String): LedgerResult<Unit> {
        restored += ledger to id
        // Removed from that book's bin only. A fake that searched every book
        // would pass even if the real statement had lost its ledger predicate,
        // which is the failure Law 2 is about.
        val before = binned[ledger].orEmpty()
        binned[ledger] = before.filterNot { it.id == id }
        revision.value += 1
        return if (before.any { it.id == id }) {
            LedgerResult.Success(Unit)
        } else {
            LedgerResult.Failure(LedgerError.EntryNotFound(id))
        }
    }

    override suspend fun purgeDeletedEntry(ledger: LedgerType, id: String): Int {
        purgedEntries += ledger to id
        val before = binned[ledger].orEmpty()
        binned[ledger] = before.filterNot { it.id == id }
        revision.value += 1
        return if (before.any { it.id == id }) 1 else 0
    }

    public fun emitBinned(ledger: LedgerType, value: List<DeletedEntry>) {
        binned[ledger] = value
        revision.value += 1
    }

    override fun observeDeletedCount(ledger: LedgerType): Flow<Int> =
        revision.map { deletedCounts[ledger] ?: 0 }

    override suspend fun purgeDeletedEntries(ledger: LedgerType): Int {
        purged += ledger
        val count = deletedCounts.remove(ledger) ?: 0
        revision.value += 1
        return count
    }

    override suspend fun compactStorage() {
        compactions += 1
    }

    public fun emitDeletedCount(ledger: LedgerType, value: Int) {
        deletedCounts[ledger] = value
        revision.value += 1
    }

    override fun observeHasEntries(ledger: LedgerType): Flow<Boolean> =
        revision.map { hasEntriesOverride[ledger] ?: entries[ledger].orEmpty().isNotEmpty() }

    override fun observeRecentCombos(ledger: LedgerType, limit: Int): Flow<List<EntryCombo>> =
        revision.map { combos[ledger].orEmpty().take(limit) }

    override suspend fun baseCurrency(): String? = installBaseCurrency

    public fun emitCombos(ledger: LedgerType, value: List<EntryCombo>) {
        combos[ledger] = value
        revision.value += 1
    }

    public fun emitEntries(ledger: LedgerType, value: List<LedgerListItem>) {
        entries[ledger] = value
        revision.value += 1
    }

    private fun ApprovalRequest.toEntry(): LedgerEntry = LedgerEntry(
        id = "entry-${approved.size}",
        ledger = ledger,
        amount = amount,
        currency = BASE_CURRENCY,
        occurredAt = occurredAt,
        localDate = 0,
        assignment = assignment,
        note = note,
        origin = origin,
        foreign = foreign,
        isRecurring = isRecurring,
        lineItems = emptyList(),
    )

    private companion object {
        private const val BASE_CURRENCY = "INR"
    }
}

/**
 * An in-memory [DraftRepository].
 *
 * Models the one rule that matters: a draft's identity is its id, so repeated
 * saves with the same id update one row while a save with a null id makes a new
 * one. A fake that collapsed every save onto a single draft would let a
 * ViewModel test pass while the real table accumulated a row per keystroke.
 */
public class FakeDraftRepository : DraftRepository {

    private val rows = LinkedHashMap<String, EntryDraft>()

    /** Every payload written, in order — the debounce is asserted against this. */
    public val saves: MutableList<String> = mutableListOf()

    public val discarded: MutableList<String> = mutableListOf()
    public var purgeCalls: Int = 0

    /** Advanced by the test so `updated_at` assertions never race a real clock. */
    public var now: Long = 1_000L

    private var nextId: Int = 1

    override fun observe(ledger: LedgerType): Flow<List<EntryDraft>> =
        revision.map { _ ->
            rows.values.filter { it.ledger == ledger }.sortedByDescending { it.updatedAt }
        }

    private val revision = MutableStateFlow(0)

    override suspend fun find(id: String): EntryDraft? = rows[id]

    override suspend fun findForEntry(ledger: LedgerType, editingEntryId: String): EntryDraft? =
        rows.values.firstOrNull { it.ledger == ledger && it.editingEntryId == editingEntryId }

    override suspend fun save(draft: DraftWrite): EntryDraft {
        saves += draft.payloadJson
        val existing = draft.id?.let { rows[it] }
        val stored = EntryDraft(
            id = existing?.id ?: draft.id ?: "draft-${nextId++}",
            ledger = draft.ledger,
            editingEntryId = draft.editingEntryId,
            payloadJson = draft.payloadJson,
            payloadVersion = draft.payloadVersion,
            summary = draft.summary,
            createdAt = existing?.createdAt ?: now,
            updatedAt = now,
        )
        rows[stored.id] = stored
        revision.value += 1
        return stored
    }

    override suspend fun discard(id: String) {
        discarded += id
        rows.remove(id)
        revision.value += 1
    }

    override suspend fun purgeAbandoned(): Int {
        purgeCalls += 1
        return 0
    }

    /**
     * Built from the same rows [observe] serves, with names left null.
     *
     * The real query resolves them with a join; a fake that invented names
     * would be asserting its own lookup rather than the screen's rendering.
     */
    override fun observeSummaries(ledger: LedgerType): Flow<List<DraftSummary>> =
        revision.map { _ ->
            rows.values
                .filter { it.ledger == ledger }
                .sortedByDescending { it.updatedAt }
                .map { draft ->
                    DraftSummary(
                        id = draft.id,
                        ledger = draft.ledger,
                        amount = Money(draft.summary.amountMinor),
                        categoryName = null,
                        categoryColorArgb = null,
                        merchantName = null,
                        updatedAt = draft.updatedAt,
                        datedAt = draft.summary.occurredAt.takeIf { it > 0L }
                            ?: draft.updatedAt,
                    )
                }
        }

    /** Seeds a draft as though a previous process had written it. */
    public fun seed(
        id: String,
        ledger: LedgerType,
        payloadJson: String,
        payloadVersion: Int = 1,
        updatedAt: Long = now,
        summary: DraftSummaryFields = DraftSummaryFields(),
    ): EntryDraft {
        val draft = EntryDraft(
            id = id,
            ledger = ledger,
            editingEntryId = null,
            payloadJson = payloadJson,
            payloadVersion = payloadVersion,
            summary = summary,
            createdAt = updatedAt,
            updatedAt = updatedAt,
        )
        rows[id] = draft
        revision.value += 1
        return draft
    }
}

/** A combo fixture, so tests do not repeat six named arguments. */
public fun entryCombo(
    categoryId: String,
    subcategoryId: String? = null,
    merchantId: String? = null,
    paymentMethodId: String? = null,
    uses: Int = 1,
    lastUsedAt: Long = 0L,
): EntryCombo = EntryCombo(
    categoryId = categoryId,
    subcategoryId = subcategoryId,
    merchantId = merchantId,
    paymentMethodId = paymentMethodId,
    uses = uses,
    lastUsedAt = lastUsedAt,
)

/** Manual origin plus a round amount — the shape most entry-form tests want. */
public fun manualDebit(minor: Long): ApprovalRequest = ApprovalRequest(
    ledger = LedgerType.DEBIT,
    amount = Money(minor),
    occurredAt = 0L,
    origin = EntryOrigin.Manual,
)
