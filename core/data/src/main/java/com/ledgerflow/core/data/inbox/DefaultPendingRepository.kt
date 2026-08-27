package com.ledgerflow.core.data.inbox

import com.ledgerflow.core.common.di.IoDispatcher
import com.ledgerflow.core.common.time.Clock
import com.ledgerflow.core.data.ingest.ExtractedTransactionJson
import com.ledgerflow.core.data.vault.VaultSession
import com.ledgerflow.core.database.entity.PendingTransactionEntity
import com.ledgerflow.core.domain.inbox.InboxFilter
import com.ledgerflow.core.domain.inbox.PendingRepository
import com.ledgerflow.core.domain.inbox.PendingTransaction
import com.ledgerflow.core.domain.ingest.ExtractedTransaction
import com.ledgerflow.core.model.LedgerType
import com.ledgerflow.core.model.PendingStatus
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
 * `pending_transaction` as the Inbox reads it (SPEC.md §5.1, §6.1). P2-6.
 *
 * **The decode lives here**, beside `ExtractedTransactionJson.encode`, which is
 * what keeps the two halves of the payload format from drifting. `:feature:inbox`
 * receives a typed [ExtractedTransaction] and never learns that the column is
 * JSON — the same split `draft_entry` refuses to make, and for the opposite
 * reason: a draft's payload belongs to one screen, while these are §5.1's
 * extraction targets and are spec-level.
 *
 * Reads follow `whenUnlocked()` rather than `requireDatabase()`: the Inbox is a
 * screen, it can be on-screen when the vault closes, and a `Flow` that threw at
 * that moment would crash the app rather than empty the list.
 */
@Singleton
public class DefaultPendingRepository @Inject constructor(
    private val session: VaultSession,
    private val clock: Clock,
    @param:IoDispatcher private val io: CoroutineDispatcher,
) : PendingRepository {

    @OptIn(ExperimentalCoroutinesApi::class)
    override fun observe(filter: InboxFilter): Flow<List<PendingTransaction>> =
        session.whenUnlocked().flatMapLatest { database ->
            val dao = database?.pendingTransactionDao() ?: return@flatMapLatest flowOf(emptyList())
            val rows = when (filter) {
                InboxFilter.PENDING -> dao.observePending()
                InboxFilter.SUPPRESSED -> dao.observeSuppressed()
                InboxFilter.DISCARDED -> dao.observeWithStatus(PendingStatus.DISCARDED)
                InboxFilter.FAILED -> dao.observeWithStatus(PendingStatus.FAILED)
            }
            rows.map { list -> list.map(::toDomain) }
        }

    @OptIn(ExperimentalCoroutinesApi::class)
    override fun observePendingCount(): Flow<Int> =
        session.whenUnlocked().flatMapLatest { database ->
            database?.pendingTransactionDao()?.observePendingCount() ?: flowOf(0)
        }

    override suspend fun find(id: String): PendingTransaction? = withContext(io) {
        runCatching {
            session.requireDatabase().pendingTransactionDao().byId(id)?.let(::toDomain)
        }.getOrNull()
    }

    override suspend fun discard(id: String): Boolean = withContext(io) {
        runCatching {
            session.requireDatabase().pendingTransactionDao().discard(id, clock.nowMillis()) > 0
        }.getOrDefault(false)
    }

    override suspend fun restore(id: String): Boolean = withContext(io) {
        runCatching {
            session.requireDatabase().pendingTransactionDao().restore(id) > 0
        }.getOrDefault(false)
    }

    override suspend fun markApproved(id: String, entryId: String): Boolean = withContext(io) {
        runCatching {
            session.requireDatabase().pendingTransactionDao()
                .markApproved(id, entryId, clock.nowMillis()) > 0
        }.getOrDefault(false)
    }

    /**
     * Both books, because the caller may be recovering an approval whose chosen
     * book it no longer knows — and because §3.1's key already guarantees a
     * credit and a debit are never the same candidate, so at most one can match.
     *
     * Two statements rather than one unfiltered query: ADR-0002 requires every
     * statement naming `ledger_entry` to bind `:ledger`, and asking each book
     * separately keeps that true here (Law 2). Nothing is summed across them.
     */
    override suspend fun findApprovedEntryId(pendingId: String): String? = withContext(io) {
        runCatching {
            val dao = session.requireDatabase().ledgerEntryDao()
            LedgerType.entries.firstNotNullOfOrNull { book ->
                dao.entryIdForSourceRef(book, pendingId)
            }
        }.getOrNull()
    }

    /**
     * Row -> domain, with the payload decoded.
     *
     * An unreadable payload degrades to an empty extraction rather than
     * dropping the row. §5.1's never-drop rule is about the pipeline, but the
     * same reasoning applies at the last step: a candidate the Inbox refuses to
     * render is a message the user cannot act on, and one they can see as
     * "needs filling in" is strictly better.
     */
    private fun toDomain(row: PendingTransactionEntity) = PendingTransaction(
        id = row.id,
        source = row.source,
        extracted = ExtractedTransactionJson.decode(row.extractedJson) ?: ExtractedTransaction(),
        confidence = row.confidence,
        status = row.status,
        needsManualFill = row.needsManualFill,
        suppressedById = row.suppressedById,
        createdAt = row.createdAt,
        reviewedAt = row.reviewedAt,
        approvedEntryId = row.approvedEntryId,
    )
}
