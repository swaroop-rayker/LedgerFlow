package com.ledgerflow.core.data.ledger

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
import com.ledgerflow.core.model.LedgerEntry
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

    private suspend fun commit(
        database: LedgerFlowDatabase,
        request: ApprovalRequest,
        baseCurrency: String,
    ): LedgerResult<LedgerEntry> {
        val rejection = reject(database, request, baseCurrency)
        if (rejection != null) return LedgerResult.Failure(rejection)

        val entity = entityOf(request, baseCurrency)
        val lineItems = lineItemsOf(entity.id, request)
        database.ledgerEntryDao().insertEntryWithLineItems(entity, lineItems)
        return LedgerResult.Success(entity.toDomain(lineItems))
    }

    /**
     * The first refusal, or null.
     *
     * Ordered cheapest-first so a malformed amount never costs a database read,
     * and short-circuiting so the user is shown one problem at a time rather
     * than a list to work through in an order the form does not suggest.
     */
    private suspend fun reject(
        database: LedgerFlowDatabase,
        request: ApprovalRequest,
        baseCurrency: String,
    ): LedgerError? =
        rejectAmount(request)
            ?: rejectForeign(request, baseCurrency)
            ?: rejectLineItems(request)
            ?: rejectCategory(database, request)
            ?: rejectSubcategory(database, request)
            ?: rejectReferences(database, request)

    private fun rejectAmount(request: ApprovalRequest): LedgerError? =
        LedgerError.AmountNotPositive.takeUnless { request.amount.isPositive }

    private fun rejectForeign(request: ApprovalRequest, baseCurrency: String): LedgerError? {
        val foreign = request.foreign ?: return null
        return when {
            foreign.currency.equals(baseCurrency, ignoreCase = true) ->
                LedgerError.ForeignCurrencyIsBase(foreign.currency)

            foreign.fxRateMicro <= 0L -> LedgerError.ForeignRateNotPositive
            else -> null
        }
    }

    private fun rejectLineItems(request: ApprovalRequest): LedgerError? =
        request.lineItems.withIndex()
            .firstOrNull { (_, item) -> item.name.isBlank() }
            ?.let { LedgerError.LineItemNameBlank(it.index) }

    private suspend fun rejectCategory(
        database: LedgerFlowDatabase,
        request: ApprovalRequest,
    ): LedgerError? {
        val categoryId = request.assignment.categoryId ?: return null
        val category = database.categoryDao().byId(categoryId)
        return when {
            category == null || category.deletedAt != 0L -> LedgerError.UnknownCategory(categoryId)

            // Law 2: the trees are disjoint, so this is not a mis-typed id --
            // it is a debit filed under "Salary". No constraint catches it,
            // because both rows are individually valid.
            category.ledgerScope != request.ledger ->
                LedgerError.CategoryNotInLedger(categoryId, request.ledger)

            else -> null
        }
    }

    /** SPEC.md §6.1.1: the subcategory's parent must equal the recorded category. */
    private suspend fun rejectSubcategory(
        database: LedgerFlowDatabase,
        request: ApprovalRequest,
    ): LedgerError? {
        val subcategoryId = request.assignment.subcategoryId ?: return null
        val categoryId = request.assignment.categoryId
            ?: return LedgerError.SubcategoryWithoutCategory

        val subcategory = database.categoryDao().byId(subcategoryId)
        return when {
            subcategory == null || subcategory.deletedAt != 0L ->
                LedgerError.UnknownCategory(subcategoryId)

            subcategory.parentId != categoryId ->
                LedgerError.SubcategoryNotUnderCategory(subcategoryId, categoryId)

            else -> null
        }
    }

    private suspend fun rejectReferences(
        database: LedgerFlowDatabase,
        request: ApprovalRequest,
    ): LedgerError? {
        val merchantId = request.assignment.merchantId
        val merchant = merchantId?.let { database.merchantDao().byId(it) }
        if (merchantId != null && (merchant == null || merchant.deletedAt != 0L)) {
            return LedgerError.UnknownMerchant(merchantId)
        }

        val methodId = request.assignment.paymentMethodId
        val method = methodId?.let { database.paymentMethodDao().byId(it) }
        return when {
            methodId != null && (method == null || method.deletedAt != 0L) ->
                LedgerError.UnknownPaymentMethod(methodId)

            else -> null
        }
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
    }
}
