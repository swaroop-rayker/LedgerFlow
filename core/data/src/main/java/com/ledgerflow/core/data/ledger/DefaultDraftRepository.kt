package com.ledgerflow.core.data.ledger

import com.ledgerflow.core.common.di.IoDispatcher
import com.ledgerflow.core.common.id.Uuid7Generator
import com.ledgerflow.core.common.time.Clock
import com.ledgerflow.core.data.vault.VaultSession
import com.ledgerflow.core.database.entity.DraftEntryEntity
import com.ledgerflow.core.domain.ledger.DraftRepository
import com.ledgerflow.core.domain.ledger.DraftSlot
import com.ledgerflow.core.domain.ledger.EntryDraft
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext

/**
 * `draft_entry` over Room (SPEC.md §6.1.2, D-06) -- BUG6's countermeasure.
 *
 * The write path is one `@Upsert` on one row and nothing else. The entry form
 * calls [save] on every field change behind a 300 ms debounce, so this is the
 * hottest write in the app; a typed model with a `draft_line_item` child table
 * would make each tick a multi-row delete-and-reinsert, which is exactly the
 * shape that trips StrictMode's `penaltyDeath` in debug (§11).
 *
 * The row's identity is its **slot**, not its id. A second call for the same
 * (ledger, editing entry) reuses the existing row rather than inserting a
 * sibling, which is what makes `UNIQUE(ledger, editing_entry_key)` a
 * constraint the code cooperates with instead of one it collides against.
 */
@Singleton
public class DefaultDraftRepository @Inject constructor(
    private val session: VaultSession,
    private val ids: Uuid7Generator,
    private val clock: Clock,
    @param:IoDispatcher private val io: CoroutineDispatcher,
) : DraftRepository {

    override suspend fun find(slot: DraftSlot): EntryDraft? = withContext(io) {
        session.requireDatabase().draftEntryDao()
            .bySlot(slot.ledger, editingEntryKeyOf(slot.editingEntryId))
            ?.toDomain()
    }

    override suspend fun save(
        slot: DraftSlot,
        payloadJson: String,
        payloadVersion: Int,
    ): EntryDraft = withContext(io) {
        val dao = session.requireDatabase().draftEntryDao()
        val key = editingEntryKeyOf(slot.editingEntryId)
        val now = clock.nowMillis()

        // Read-then-upsert rather than a blind insert: reusing the existing row
        // preserves `created_at`, which is what "started this entry an hour ago"
        // is measured from, and keeps the id stable so a concurrent debounce
        // tick cannot produce two rows racing for one slot.
        val existing = dao.bySlot(slot.ledger, key)
        val entity = DraftEntryEntity(
            id = existing?.id ?: ids.generate(),
            ledger = slot.ledger,
            editingEntryId = slot.editingEntryId,
            editingEntryKey = key,
            payloadJson = payloadJson,
            payloadVersion = payloadVersion,
            createdAt = existing?.createdAt ?: now,
            updatedAt = now,
        )
        dao.upsert(entity)
        entity.toDomain()
    }

    override suspend fun discard(slot: DraftSlot): Unit = withContext(io) {
        session.requireDatabase().draftEntryDao()
            .deleteSlot(slot.ledger, editingEntryKeyOf(slot.editingEntryId))
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
 * entry form's field names in `:core:data` and would mean a payload written by
 * a newer build gets parsed against an older schema on the way past -- which is
 * the failure §6.1.2 asks us to avoid by *retaining* the row rather than
 * touching it.
 */
private fun DraftEntryEntity.toDomain(): EntryDraft = EntryDraft(
    id = id,
    slot = DraftSlot(ledger = ledger, editingEntryId = editingEntryId),
    payloadJson = payloadJson,
    payloadVersion = payloadVersion,
    createdAt = createdAt,
    updatedAt = updatedAt,
)
