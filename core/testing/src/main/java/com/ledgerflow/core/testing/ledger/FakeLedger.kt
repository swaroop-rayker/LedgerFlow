package com.ledgerflow.core.testing.ledger

import com.ledgerflow.core.domain.ledger.ApprovalRequest
import com.ledgerflow.core.domain.ledger.DraftRepository
import com.ledgerflow.core.domain.ledger.DraftSlot
import com.ledgerflow.core.domain.ledger.EntryCombo
import com.ledgerflow.core.domain.ledger.EntryDraft
import com.ledgerflow.core.domain.ledger.LedgerRepository
import com.ledgerflow.core.domain.ledger.LedgerResult
import com.ledgerflow.core.model.EntryOrigin
import com.ledgerflow.core.model.LedgerEntry
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

    private val revision = MutableStateFlow(0)

    override suspend fun approve(request: ApprovalRequest): LedgerResult<LedgerEntry> {
        approved += request
        return approveResult ?: LedgerResult.Success(request.toEntry())
    }

    override fun observeRecentCombos(ledger: LedgerType, limit: Int): Flow<List<EntryCombo>> =
        revision.map { combos[ledger].orEmpty().take(limit) }

    public fun emitCombos(ledger: LedgerType, value: List<EntryCombo>) {
        combos[ledger] = value
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
 * An in-memory [DraftRepository] keyed by slot.
 *
 * This one *does* model its rule, because the rule is the whole point: one row
 * per slot. A fake that let two drafts share a slot would let a ViewModel test
 * pass while the real unique index rejected the same sequence on device.
 */
public class FakeDraftRepository : DraftRepository {

    private val rows = mutableMapOf<DraftSlot, EntryDraft>()

    /** Every payload written, in order — the debounce is asserted against this. */
    public val saves: MutableList<String> = mutableListOf()

    public val discarded: MutableList<DraftSlot> = mutableListOf()
    public var purgeCalls: Int = 0

    /** Advanced by the test so `updated_at` assertions never race a real clock. */
    public var now: Long = 1_000L

    override suspend fun find(slot: DraftSlot): EntryDraft? = rows[slot]

    override suspend fun save(
        slot: DraftSlot,
        payloadJson: String,
        payloadVersion: Int,
    ): EntryDraft {
        saves += payloadJson
        val existing = rows[slot]
        val draft = EntryDraft(
            id = existing?.id ?: "draft-${rows.size + 1}",
            slot = slot,
            payloadJson = payloadJson,
            payloadVersion = payloadVersion,
            createdAt = existing?.createdAt ?: now,
            updatedAt = now,
        )
        rows[slot] = draft
        return draft
    }

    override suspend fun discard(slot: DraftSlot) {
        discarded += slot
        rows.remove(slot)
    }

    override suspend fun purgeAbandoned(): Int {
        purgeCalls += 1
        return 0
    }

    /** Seeds a draft as though a previous process had written it. */
    public fun seed(slot: DraftSlot, payloadJson: String, payloadVersion: Int = 1) {
        rows[slot] = EntryDraft(
            id = "seeded",
            slot = slot,
            payloadJson = payloadJson,
            payloadVersion = payloadVersion,
            createdAt = now,
            updatedAt = now,
        )
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
