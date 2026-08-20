package com.ledgerflow.core.data.ledger

import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import androidx.paging.map
import androidx.room.withTransaction
import com.ledgerflow.core.common.di.IoDispatcher
import com.ledgerflow.core.common.id.Uuid7Generator
import com.ledgerflow.core.common.time.Clock
import com.ledgerflow.core.common.time.LocalDates
import com.ledgerflow.core.data.vault.VaultSession
import com.ledgerflow.core.database.LedgerFlowDatabase
import com.ledgerflow.core.database.entity.AppMetaEntity
import com.ledgerflow.core.database.entity.LedgerEntryEntity
import com.ledgerflow.core.database.entity.LineItemEntity
import com.ledgerflow.core.domain.ledger.ApprovalRequest
import com.ledgerflow.core.domain.ledger.EntryCombo
import com.ledgerflow.core.domain.ledger.ItemNameNormalizer
import com.ledgerflow.core.domain.ledger.LedgerError
import com.ledgerflow.core.domain.ledger.LedgerRepository
import com.ledgerflow.core.domain.ledger.LedgerResult
import com.ledgerflow.core.domain.ledger.NewLineItem
import com.ledgerflow.core.model.DeletedEntry
import com.ledgerflow.core.model.LedgerEntry
import com.ledgerflow.core.model.LedgerListItem
import com.ledgerflow.core.model.LedgerType
import com.ledgerflow.core.model.LineItem
import com.ledgerflow.core.model.LineItemKind
import com.ledgerflow.core.model.Money
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
 * The ledger's single write path (Law 1), over Room.
 *
 * **Everything happens inside one transaction, validation included.** That is
 * not defensive style: SPEC.md §6.1.1's invariants are statements about rows in
 * other tables -- a category's `ledger_scope`, a subcategory's `parent_id` --
 * and a check that runs before the transaction opens can be invalidated by a
 * soft-delete landing between the check and the insert. The entry would then
 * point at a row no picker will ever offer again, and nothing would report it.
 *
 * Refusals are returned, not thrown. A category that vanished mid-form is a
 * sentence the user reads, not a stack trace (CLAUDE.md §5).
 */
@Singleton
public class DefaultLedgerRepository @Inject constructor(
    private val session: VaultSession,
    private val ids: Uuid7Generator,
    private val clock: Clock,
    @param:IoDispatcher private val io: CoroutineDispatcher,
) : LedgerRepository {

    override suspend fun approve(request: ApprovalRequest): LedgerResult<LedgerEntry> =
        withContext(io) {
            val database = session.requireDatabase()
            database.withTransaction {
                val baseCurrency = database.appMetaDao().value(AppMetaEntity.KEY_BASE_CURRENCY)
                if (baseCurrency == null) {
                    LedgerResult.Failure(LedgerError.BaseCurrencyMissing)
                } else {
                    commit(database, request, baseCurrency)
                }
            }
        }

    @OptIn(ExperimentalCoroutinesApi::class)
    override fun observeEntries(
        ledger: LedgerType,
        since: Int,
    ): Flow<PagingData<LedgerListItem>> =
        session.whenUnlocked().flatMapLatest { database ->
            val dao = database?.ledgerEntryDao()
                ?: return@flatMapLatest flowOf(PagingData.empty())

            // The factory is called again on every invalidation, which is what
            // makes an approval show up without anyone telling the list. Room
            // invalidates the PagingSource when `ledger_entry` is written, the
            // Pager builds a fresh one from this lambda, and the list refreshes.
            // Hoisting the PagingSource out to a val instead would produce a
            // list that loads once and then never changes again -- which is
            // BUG10 wearing a different hat.
            Pager(
                config = PagingConfig(
                    pageSize = PAGE_SIZE,
                    // No placeholders: with them the list has to know its total
                    // count up front, which costs a COUNT(*) over the book on
                    // every load, and the day headers would have to be derived
                    // from rows that are null. Nothing here needs a scrollbar
                    // proportional to a ledger the user cannot see the end of.
                    enablePlaceholders = false,
                ),
                pagingSourceFactory = {
                    // One statement per book. Neither can be pointed at the
                    // other by passing a different argument -- the predicate is
                    // inside the view (ADR-0002).
                    when (ledger) {
                        LedgerType.DEBIT -> dao.pagingDebits(since)
                        LedgerType.CREDIT -> dao.pagingCredits(since)
                    }
                },
            ).flow.map { page -> page.map { row -> row.toDomain(ledger) } }
        }

    override suspend fun softDeleteEntry(
        ledger: LedgerType,
        id: String,
    ): LedgerResult<Unit> = withContext(io) {
        val affected = session.requireDatabase().ledgerEntryDao()
            .softDeleteEntry(ledger, id, clock.nowMillis())
        // Zero rows is the honest answer to both "already deleted" and "wrong
        // book", and the statement cannot tell them apart -- which is the point.
        if (affected == 0) {
            LedgerResult.Failure(LedgerError.EntryNotFound(id))
        } else {
            LedgerResult.Success(Unit)
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    override fun observeDeleted(ledger: LedgerType): Flow<List<DeletedEntry>> =
        session.whenUnlocked().flatMapLatest { database ->
            val dao = database?.ledgerEntryDao() ?: return@flatMapLatest flowOf(emptyList())
            dao.observeDeleted(ledger).map { rows -> rows.map { it.toDomain() } }
        }

    override suspend fun restoreEntry(
        ledger: LedgerType,
        id: String,
    ): LedgerResult<Unit> = withContext(io) {
        val affected = session.requireDatabase().ledgerEntryDao()
            .restoreEntry(ledger, id, clock.nowMillis())
        if (affected == 0) {
            LedgerResult.Failure(LedgerError.EntryNotFound(id))
        } else {
            LedgerResult.Success(Unit)
        }
    }

    override suspend fun purgeDeletedEntry(ledger: LedgerType, id: String): Int = withContext(io) {
        session.requireDatabase().ledgerEntryDao().purgeDeletedEntry(ledger, id)
    }

    override suspend fun purgeDeletedEntries(ledger: LedgerType): Int = withContext(io) {
        session.requireDatabase().ledgerEntryDao().purgeDeletedEntries(ledger)
    }

    /**
     * `VACUUM`, with the write-ahead log flushed first.
     *
     * Deliberately **not** inside `withTransaction`: SQLite refuses to VACUUM
     * inside one, and Room's transaction wrapper would silently put us there.
     *
     * The checkpoint ahead of it is the same `TRUNCATE` the app already runs
     * when it backgrounds (`WalCheckpointObserver`, BUG2). Without it the
     * deletes may still be sitting in `-wal`, and VACUUM would rewrite a main
     * database that does not yet contain them -- reclaiming nothing and leaving
     * the freed pages exactly where they were.
     */
    override suspend fun compactStorage(): Unit = withContext(io) {
        val database = session.requireDatabase().openHelper.writableDatabase
        database.query("PRAGMA wal_checkpoint(TRUNCATE);").use { it.moveToFirst() }
        database.execSQL("VACUUM")
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    override fun observeDeletedCount(ledger: LedgerType): Flow<Int> =
        session.whenUnlocked().flatMapLatest { database ->
            val dao = database?.ledgerEntryDao() ?: return@flatMapLatest flowOf(0)
            dao.observeDeletedCount(ledger)
        }

    @OptIn(ExperimentalCoroutinesApi::class)
    override fun observeHasEntries(ledger: LedgerType): Flow<Boolean> =
        session.whenUnlocked().flatMapLatest { database ->
            val dao = database?.ledgerEntryDao() ?: return@flatMapLatest flowOf(false)
            when (ledger) {
                LedgerType.DEBIT -> dao.observeHasDebits()
                LedgerType.CREDIT -> dao.observeHasCredits()
            }
        }

    @OptIn(ExperimentalCoroutinesApi::class)
    override fun observeRecentCombos(ledger: LedgerType, limit: Int): Flow<List<EntryCombo>> =
        session.whenUnlocked().flatMapLatest { database ->
            val dao = database?.ledgerEntryDao() ?: return@flatMapLatest flowOf(emptyList())
            // One statement per book. There is no query here that could be
            // pointed at the other ledger by passing a different argument
            // (ADR-0002) -- the predicate is inside the view.
            val rows = when (ledger) {
                LedgerType.DEBIT -> dao.observeDebitCombos(limit)
                LedgerType.CREDIT -> dao.observeCreditCombos(limit)
            }
            rows.map { list -> list.map { it.toDomain() } }
        }

    override suspend fun baseCurrency(): String? = withContext(io) {
        session.requireDatabase().appMetaDao().value(AppMetaEntity.KEY_BASE_CURRENCY)
    }

    private suspend fun commit(
        database: LedgerFlowDatabase,
        request: ApprovalRequest,
        baseCurrency: String,
    ): LedgerResult<LedgerEntry> {
        val rejection = LedgerApprovalRules.firstRefusal(database, request, baseCurrency)
        if (rejection != null) return LedgerResult.Failure(rejection)

        val entity = entityOf(request, baseCurrency)
        val lineItems = lineItemsOf(entity.id, request)
        database.ledgerEntryDao().insertEntryWithLineItems(entity, lineItems)
        return LedgerResult.Success(entity.toDomain(lineItems))
    }

    private fun entityOf(request: ApprovalRequest, baseCurrency: String): LedgerEntryEntity {
        val now = clock.nowMillis()
        return LedgerEntryEntity(
            id = ids.generate(),
            ledger = request.ledger,
            amountMinor = request.amount,
            currency = baseCurrency,
            originalAmountMinor = request.foreign?.amountMinor,
            originalCurrency = request.foreign?.currency,
            fxRateMicro = request.foreign?.fxRateMicro,
            occurredAt = request.occurredAt,
            // Derived here, never taken from the caller: two fields that must
            // agree are two fields that can disagree, and the disagreement
            // shows up as an entry missing from the day it was filed under.
            localDate = LocalDates.of(request.occurredAt),
            merchantId = request.assignment.merchantId,
            categoryId = request.assignment.categoryId,
            subcategoryId = request.assignment.subcategoryId,
            paymentMethodId = request.assignment.paymentMethodId,
            note = request.note?.trim()?.ifEmpty { null },
            source = request.origin.source,
            sourceRefId = request.origin.refId,
            isRecurring = request.isRecurring,
            createdAt = now,
            updatedAt = now,
        )
    }

    /**
     * The entered lines, plus an `UNALLOCATED` row for any shortfall.
     *
     * §5.3's rule for an unbalanced receipt, applied to manual entry so the two
     * cannot disagree about what an unbalanced bill means: the user may save
     * it, and the difference is stored explicitly rather than letting the sum of
     * the parts quietly differ from the total it is supposed to explain.
     */
    private fun lineItemsOf(entryId: String, request: ApprovalRequest): List<LineItemEntity> {
        if (request.lineItems.isEmpty()) return emptyList()

        val entered = request.lineItems.mapIndexed { index, item -> item.toEntity(entryId, index) }
        val delta = request.amount - Money.sum(request.lineItems.map { it.total })
        return if (delta.isZero) entered else entered + unallocated(entryId, entered.size, delta)
    }

    private fun NewLineItem.toEntity(entryId: String, position: Int): LineItemEntity =
        LineItemEntity(
            id = ids.generate(),
            entryId = entryId,
            position = position,
            name = name.trim(),
            normalizedName = ItemNameNormalizer.normalize(name),
            quantityMilli = quantityMilli,
            unitPriceMinor = unitPrice?.minor,
            totalMinor = total,
            kind = kind,
            categoryId = categoryId,
            subcategoryId = subcategoryId,
        )

    private fun unallocated(entryId: String, position: Int, delta: Money): LineItemEntity =
        LineItemEntity(
            id = ids.generate(),
            entryId = entryId,
            position = position,
            name = UNALLOCATED_NAME,
            normalizedName = ItemNameNormalizer.normalize(UNALLOCATED_NAME),
            quantityMilli = LineItem.UNIT_QUANTITY_MILLI,
            unitPriceMinor = null,
            totalMinor = delta,
            kind = LineItemKind.UNALLOCATED,
            categoryId = null,
            subcategoryId = null,
        )

    private companion object {
        /** Shown verbatim in the line-item editor, so it reads as a sentence. */
        private const val UNALLOCATED_NAME = "Unallocated"

        /**
         * Rows per page.
         *
         * Comfortably more than one screenful at font scale 1.0 -- a page
         * smaller than the viewport makes Paging fetch twice before the first
         * frame -- and small enough that the join behind it stays cheap. Room's
         * default prefetch distance is the page size, so the next page starts
         * loading a screen ahead of the scroll.
         */
        private const val PAGE_SIZE = 30
    }
}
