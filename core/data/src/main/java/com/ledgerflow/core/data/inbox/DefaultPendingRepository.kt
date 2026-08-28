package com.ledgerflow.core.data.inbox

import com.ledgerflow.core.common.di.IoDispatcher
import com.ledgerflow.core.common.time.Clock
import com.ledgerflow.core.data.ingest.ExtractedTransactionJson
import com.ledgerflow.core.data.vault.VaultSession
import com.ledgerflow.core.database.entity.PendingTransactionEntity
import com.ledgerflow.core.domain.inbox.InboxFilter
import com.ledgerflow.core.domain.inbox.PendingRepository
import com.ledgerflow.core.domain.inbox.PendingTransaction
import com.ledgerflow.core.domain.inbox.ReviewEdits
import com.ledgerflow.core.domain.ingest.ExtractedTransaction
import com.ledgerflow.core.model.LedgerType
import com.ledgerflow.core.model.PendingStatus
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
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
 *
 * **The one-shot calls open the vault themselves** (BUG13). They used to take
 * `requireDatabase()`, which is right for a screen and wrong for everything
 * else that reaches this class: §5.1's `[Review] [Discard]` notification
 * actions run from a `BroadcastReceiver` with no Activity alive, and every one
 * of them threw into a `runCatching` and came back as a clean `false`. See
 * [openVault], and `Bug13_ShadeActionOnClosedVaultTest`.
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
            // Merchant names come from ONE query for the whole list, not a
            // lookup per row. A candidate the user edited holds a merchant *id*
            // and every list shows a *name*, so somebody has to resolve it;
            // §6.1 rejects doing that per row, and the taxonomy is small enough
            // to hold. Combined rather than joined so a merchant renamed while
            // the Inbox is open re-renders the rows that use it.
            combine(
                rows,
                database.merchantDao().observeLive(),
                database.categoryDao().observeLiveInBothBooks(),
            ) { list, merchants, categories ->
                val merchantNames = merchants.associate { it.id to it.canonicalName }
                val categoryNames = categories.associate { it.id to it.name }
                list.map { row -> row.toDomain(merchantNames, categoryNames) }
            }
        }

    @OptIn(ExperimentalCoroutinesApi::class)
    override fun observePendingCount(): Flow<Int> =
        session.whenUnlocked().flatMapLatest { database ->
            database?.pendingTransactionDao()?.observePendingCount() ?: flowOf(0)
        }

    @OptIn(ExperimentalCoroutinesApi::class)
    override fun observeCounts(): Flow<Map<InboxFilter, Int>> =
        session.whenUnlocked().flatMapLatest { database ->
            val dao = database?.pendingTransactionDao()
                ?: return@flatMapLatest flowOf(InboxFilter.entries.associateWith { 0 })
            combine(
                dao.observePendingCount(),
                dao.observeSuppressedCount(),
                dao.observeCountWithStatus(PendingStatus.DISCARDED),
                dao.observeCountWithStatus(PendingStatus.FAILED),
            ) { pending, suppressed, discarded, failed ->
                mapOf(
                    InboxFilter.PENDING to pending,
                    InboxFilter.SUPPRESSED to suppressed,
                    InboxFilter.DISCARDED to discarded,
                    InboxFilter.FAILED to failed,
                )
            }
        }

    /**
     * The vault, opened if the UI has not already done it (BUG13).
     *
     * **Every one-shot below can be reached with no Activity alive.** §5.1's
     * notification actions run from a `BroadcastReceiver`, and before this they
     * asked for a database that only `AppViewModel` ever opened. `requireDatabase()`
     * threw, the surrounding `runCatching` swallowed it, and a `[Discard]` the
     * user tapped returned `false` while the row stayed `PENDING` — §2.4's
     * silent drop, arriving through the Inbox instead of through capture.
     *
     * The same call `DefaultRawIngestRepository` makes and the same one the UI
     * makes: **no new wrap and no new key material.** §7 forbids
     * `setUserAuthenticationRequired(true)` on the DEK-wrapping key precisely so
     * a Keystore unwrap needs no user present.
     *
     * Null is a real answer and never a reason to invent one: the vault cannot
     * be opened, so the caller is told no rather than told nothing.
     */
    private suspend fun openVault() = session.openForBackgroundWork()

    override suspend fun find(id: String): PendingTransaction? = withContext(io) {
        val database = openVault() ?: return@withContext null
        runCatching {
            val row = database.pendingTransactionDao().byId(id) ?: return@runCatching null
            val edits = ReviewEditsJson.decode(row.reviewDraftJson)
            // One row, so one lookup rather than loading the whole taxonomy.
            val editedMerchant = edits?.merchantId
                ?.let { database.merchantDao().byId(it)?.canonicalName }
            val editedCategory = edits?.categoryId
                ?.let { database.categoryDao().byId(it)?.name }
            row.toDomain(edits, editedMerchant, editedCategory)
        }.getOrNull()
    }

    override suspend fun discard(id: String): Boolean = withContext(io) {
        val database = openVault() ?: return@withContext false
        runCatching {
            database.pendingTransactionDao().discard(id, clock.nowMillis()) > 0
        }.getOrDefault(false)
    }

    override suspend fun restore(id: String): Boolean = withContext(io) {
        val database = openVault() ?: return@withContext false
        runCatching {
            database.pendingTransactionDao().restore(id) > 0
        }.getOrDefault(false)
    }

    override suspend fun saveReviewDraft(id: String, edits: ReviewEdits?): Boolean =
        withContext(io) {
            val database = openVault() ?: return@withContext false
            runCatching {
                val json = edits?.let(ReviewEditsJson::encode)
                database.pendingTransactionDao().saveReviewDraft(id, json) > 0
            }.getOrDefault(false)
        }

    override suspend fun erase(ids: List<String>): Int = withContext(io) {
        if (ids.isEmpty()) return@withContext 0
        val database = openVault() ?: return@withContext 0
        runCatching { database.pendingTransactionDao().erase(ids) }.getOrDefault(0)
    }

    /**
     * **No `VACUUM` here, unlike the ledger bin.**
     *
     * `PurgeDeletedEntriesUseCase` compacts because it erases `ledger_entry`
     * rows with their line items, which can be a large share of the file.
     * Candidates are small and few — a body-less row plus a short JSON payload —
     * and `VACUUM` rewrites the *whole* encrypted database outside a
     * transaction. CLAUDE.md §7 is explicit that a mistake there does not fail
     * loudly; it surfaces as an unreadable vault on the next launch. That is not
     * a risk worth taking to reclaim a few kilobytes, and the raw bodies these
     * rows came from are still on disk anyway until D-09 clears them.
     */
    override suspend fun eraseAll(filter: InboxFilter): Int = withContext(io) {
        val database = openVault() ?: return@withContext 0
        runCatching {
            val dao = database.pendingTransactionDao()
            when (filter) {
                InboxFilter.DISCARDED -> dao.eraseWithStatus(PendingStatus.DISCARDED)
                InboxFilter.FAILED -> dao.eraseWithStatus(PendingStatus.FAILED)
                InboxFilter.SUPPRESSED -> dao.eraseSuppressed()
                // The queue Law 1 is about. Not emptiable in bulk, by design.
                InboxFilter.PENDING -> 0
            }
        }.getOrDefault(0)
    }

    override suspend fun markApproved(id: String, entryId: String): Boolean = withContext(io) {
        val database = openVault() ?: return@withContext false
        runCatching {
            database.pendingTransactionDao()
                .markApproved(id, entryId, clock.nowMillis()) > 0
        }.getOrDefault(false)
    }

    /**
     * Both books, and it **must** be able to answer, or it is worse than absent.
     *
     * Both, because the caller may be recovering an approval whose chosen book
     * it no longer knows — and because §3.1's key already guarantees a credit
     * and a debit are never the same candidate, so at most one can match. Two
     * statements rather than one unfiltered query: ADR-0002 requires every
     * statement naming `ledger_entry` to bind `:ledger`, and asking each book
     * separately keeps that true here (Law 2). Nothing is summed across them.
     *
     * It is the idempotency guard across an approval's two writes, and null
     * means "no entry yet" — which is exactly what a second approval of an
     * already-approved candidate needs to hear in order to write a **second
     * `ledger_entry` for one payment**. On a locked vault it threw and was
     * swallowed into that same null, so giving [find] a background unlock
     * without giving one to this would have turned BUG13 from an action that
     * did nothing into an action that doubled a ledger row — the precise
     * duplicate the whole ingest pipeline exists to prevent.
     */
    override suspend fun findApprovedEntryId(pendingId: String): String? = withContext(io) {
        val database = openVault() ?: return@withContext null
        runCatching {
            val dao = database.ledgerEntryDao()
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
    /**
     * A list row, with the merchant name taken from the batch already loaded.
     */
    private fun PendingTransactionEntity.toDomain(
        merchantNames: Map<String, String>,
        categoryNames: Map<String, String>,
    ): PendingTransaction {
        val edits = ReviewEditsJson.decode(reviewDraftJson)
        return toDomain(
            edits = edits,
            editedMerchantName = edits?.merchantId?.let(merchantNames::get),
            editedCategoryName = edits?.categoryId?.let(categoryNames::get),
        )
    }

    /** A single row, where one lookup is cheaper than loading the taxonomy. */
    private fun PendingTransactionEntity.toDomain(
        edits: ReviewEdits?,
        editedMerchantName: String?,
        editedCategoryName: String? = null,
    ) = PendingTransaction(
        id = id,
        source = source,
        extracted = ExtractedTransactionJson.decode(extractedJson) ?: ExtractedTransaction(),
        confidence = confidence,
        status = status,
        needsManualFill = needsManualFill,
        suppressedById = suppressedById,
        createdAt = createdAt,
        reviewedAt = reviewedAt,
        approvedEntryId = approvedEntryId,
        edits = edits,
        editedMerchantName = editedMerchantName,
        editedCategoryName = editedCategoryName,
    )
}
