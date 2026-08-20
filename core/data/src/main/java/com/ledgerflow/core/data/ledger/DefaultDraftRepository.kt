package com.ledgerflow.core.data.ledger

import com.ledgerflow.core.common.di.IoDispatcher
import com.ledgerflow.core.common.id.Uuid7Generator
import com.ledgerflow.core.common.time.Clock
import com.ledgerflow.core.data.vault.VaultSession
import com.ledgerflow.core.database.dao.DraftSummaryRow
import com.ledgerflow.core.database.entity.DraftEntryEntity
import com.ledgerflow.core.domain.ledger.DraftRepository
import com.ledgerflow.core.domain.ledger.DraftSummary
import com.ledgerflow.core.domain.ledger.DraftSummaryFields
import com.ledgerflow.core.domain.ledger.DraftWrite
import com.ledgerflow.core.domain.ledger.EntryDraft
import com.ledgerflow.core.model.LedgerType
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

/**
 * `draft_entry` over Room (SPEC.md §6.1.2) -- BUG6's countermeasure.
 *
 * The write path is one `@Upsert` on one row and nothing else. The entry form
 * calls [save] on every field change behind a 300 ms debounce, so this is the
 * hottest write in the app; a typed model with a `draft_line_item` child table
 * would make each tick a multi-row delete-and-reinsert, which is exactly the
 * shape that trips StrictMode's `penaltyDeath` in debug (§11).
 *
 * A draft's identity is now its **id**, not its slot (ADR-0013). Passing that
 * id back on subsequent saves is what makes a form's debounce update one row
 * rather than deposit a fresh draft every 300 ms.
 */
@Singleton
public class DefaultDraftRepository @Inject constructor(
    private val session: VaultSession,
    private val ids: Uuid7Generator,
    private val clock: Clock,
    @param:IoDispatcher private val io: CoroutineDispatcher,
) : DraftRepository {

    @OptIn(ExperimentalCoroutinesApi::class)
    override fun observe(ledger: LedgerType): Flow<List<EntryDraft>> =
        session.whenUnlocked().flatMapLatest { database ->
            database?.draftEntryDao()?.observeForLedger(ledger)
                ?.map { rows -> rows.map { it.toDomain() } }
                ?: flowOf(emptyList())
        }

    @OptIn(ExperimentalCoroutinesApi::class)
    override fun observeSummaries(ledger: LedgerType): Flow<List<DraftSummary>> =
        session.whenUnlocked().flatMapLatest { database ->
            database?.draftEntryDao()?.observeSummariesForLedger(ledger)
                ?.map { rows -> rows.map { it.toDomain() } }
                ?: flowOf(emptyList())
        }

    override suspend fun find(id: String): EntryDraft? = withContext(io) {
        session.requireDatabase().draftEntryDao().byId(id)?.toDomain()
    }

    override suspend fun findForEntry(
        ledger: LedgerType,
        editingEntryId: String,
    ): EntryDraft? = withContext(io) {
        session.requireDatabase().draftEntryDao()
            .byEditingEntry(ledger, editingEntryId)
            ?.toDomain()
    }

    override suspend fun save(draft: DraftWrite): EntryDraft = withContext(io) {
        val dao = session.requireDatabase().draftEntryDao()
        val now = clock.nowMillis()

        // Reuse rather than blind-insert: it preserves `created_at`, which is
        // what "started this an hour ago" is measured from, and keeps the id
        // stable so a concurrent debounce tick cannot produce a second row for
        // a form the user thinks is one entry.
        val existing = draft.id?.let { dao.byId(it) }
        val entity = DraftEntryEntity(
            id = existing?.id ?: draft.id ?: ids.generate(),
            ledger = draft.ledger,
            editingEntryId = draft.editingEntryId,
            editingEntryKey = editingEntryKeyOf(draft.editingEntryId),
            payloadJson = draft.payloadJson,
            payloadVersion = draft.payloadVersion,
            // Written from what the caller lifted out of the payload, every
            // save. Never derived here: parsing the payload in :core:data would
            // put the entry form's field names in a layer that is not allowed
            // to know them.
            amountMinor = draft.summary.amountMinor,
            categoryId = draft.summary.categoryId,
            merchantId = draft.summary.merchantId,
            occurredAt = draft.summary.occurredAt,
            createdAt = existing?.createdAt ?: now,
            updatedAt = now,
        )
        dao.upsert(entity)
        entity.toDomain()
    }

    override suspend fun discard(id: String): Unit = withContext(io) {
        session.requireDatabase().draftEntryDao().delete(id)
    }

    override suspend fun purgeAbandoned(): Int = withContext(io) {
        val cutoff = clock.nowMillis() - DraftRepository.RETENTION_MILLIS
        session.requireDatabase().draftEntryDao().purgeOlderThan(cutoff)
    }
}

/**
 * Row -> domain.
 *
 * `payload_json` is carried across unread. Deserializing here would put the
 * entry form's field names in `:core:data`, and would mean a payload written by
 * a newer build gets parsed against an older schema on the way past -- which is
 * the failure §6.1.2 asks us to avoid by *retaining* the row rather than
 * touching it.
 */
private fun DraftEntryEntity.toDomain(): EntryDraft = EntryDraft(
    id = id,
    ledger = ledger,
    editingEntryId = editingEntryId,
    payloadJson = payloadJson,
    payloadVersion = payloadVersion,
    summary = DraftSummaryFields(
        amountMinor = amountMinor,
        categoryId = categoryId,
        merchantId = merchantId,
        occurredAt = occurredAt,
    ),
    createdAt = createdAt,
    updatedAt = updatedAt,
)

/**
 * Summary row -> domain.
 *
 * Names arrive already resolved by the query's joins, and stay null when the
 * category or merchant they pointed at has been deleted -- which is the honest
 * rendering, and why those columns carry no foreign key.
 */
private fun DraftSummaryRow.toDomain(): DraftSummary = DraftSummary(
    id = id,
    ledger = ledger,
    amount = amountMinor,
    categoryName = categoryName,
    categoryColorArgb = categoryColorArgb,
    merchantName = merchantName,
    updatedAt = updatedAt,
    datedAt = datedAt,
)
