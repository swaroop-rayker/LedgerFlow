package com.ledgerflow.core.data.ledger

import com.ledgerflow.core.database.LedgerFlowDatabase
import com.ledgerflow.core.database.entity.CategoryEntity
import com.ledgerflow.core.domain.ledger.ApprovalRequest
import com.ledgerflow.core.domain.ledger.LedgerError
import com.ledgerflow.core.domain.ledger.NewLineItem
import com.ledgerflow.core.model.LedgerType

/**
 * Everything that can refuse an approval (SPEC.md §6.1.1).
 *
 * Lifted out of `DefaultLedgerRepository` when that class crossed twenty
 * functions -- seven of them were this one concern, and a repository is a class
 * with behaviour rather than a namespace of statements, so the count was a real
 * signal rather than a counter to argue with.
 *
 * **These still run inside the approval's transaction.** That is the whole
 * point of §6.1.1 and it is unchanged by the move: `firstRefusal` is called
 * from `commit`, which `approve` invokes inside `withTransaction`. Every rule
 * here is a statement about rows in *other* tables -- a category's
 * `ledger_scope`, a subcategory's `parent_id` -- and a check that ran before
 * the transaction opened could be invalidated by a soft-delete landing between
 * the check and the insert.
 *
 * An object rather than a class: it holds no state, and giving it a constructor
 * would invite someone to cache the database in it, which is exactly how the
 * "runs inside the transaction" property would get lost.
 */
internal object LedgerApprovalRules {

    /**
     * The first refusal, or null.
     *
     * Ordered cheapest-first so a malformed amount never costs a database read,
     * and short-circuiting so the user is shown one problem at a time rather
     * than a list to work through in an order the form does not suggest.
     */
    suspend fun firstRefusal(
        database: LedgerFlowDatabase,
        request: ApprovalRequest,
        baseCurrency: String,
    ): LedgerError? =
        rejectAmount(request)
            ?: rejectForeign(request, baseCurrency)
            ?: rejectLineItems(request)
            ?: rejectCategory(database, request)
            ?: rejectSubcategory(database, request)
            ?: rejectLineItemFiling(database, request)
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

    /**
     * The same filing rules as the entry's own, applied to every line
     * (ADR-0018).
     *
     * This is not belt-and-braces. An itemised entry files nothing at the entry
     * level -- `ledger_entry.category_id` is null and the truth lives on these
     * rows -- so these are the only checks standing between a mis-filed line
     * and spend that analytics attributes to a category from the other book, or
     * to one that no longer exists. The schema cannot help: `line_item` carries
     * no foreign key to `category` at all.
     *
     * Categories are read once each rather than once per line. A grocery bill
     * is a dozen lines across three categories, all inside the approval's
     * transaction, and the repeated reads would be the transaction's whole cost.
     */
    private suspend fun rejectLineItemFiling(
        database: LedgerFlowDatabase,
        request: ApprovalRequest,
    ): LedgerError? {
        if (request.lineItems.none { it.categoryId != null || it.subcategoryId != null }) {
            return null
        }

        // Read once per distinct category, not once per line. A grocery bill is
        // a dozen lines across three categories, and every one of these reads
        // happens inside the approval's transaction.
        val seen = mutableMapOf<String, CategoryEntity?>()
        for ((position, item) in request.lineItems.withIndex()) {
            val refusal = refuseLineFiling(database, seen, position, item, request.ledger)
            if (refusal != null) return refusal
        }
        return null
    }

    /**
     * One line's filing, checked against the same rules as the entry's own.
     *
     * The subcategory is looked up before the category has been cleared, which
     * costs one memoised read on a path that is about to fail anyway. That buys
     * a single `when` over every outcome instead of a ladder of early returns,
     * and on the path that matters -- a valid line -- both reads were needed.
     */
    private suspend fun refuseLineFiling(
        database: LedgerFlowDatabase,
        seen: MutableMap<String, CategoryEntity?>,
        position: Int,
        item: NewLineItem,
        ledger: LedgerType,
    ): LedgerError? {
        val categoryId = item.categoryId
        if (categoryId == null) {
            // A subcategory with no category has no parent to compare against
            // -- the same hole §6.1.1 closes at the entry level, reachable here
            // by clearing a line's category after choosing its subcategory.
            return LedgerError.LineItemSubcategoryWithoutCategory(position)
                .takeIf { item.subcategoryId != null }
        }

        val category = liveCategory(database, seen, categoryId)
        val subcategoryId = item.subcategoryId
        val subcategory = subcategoryId?.let { liveCategory(database, seen, it) }

        return when {
            category == null -> LedgerError.LineItemUnknownCategory(position, categoryId)

            // Law 2, one level down: a debit line filed under "Salary" is two
            // individually valid rows pointing at each other, and `line_item`
            // carries no foreign key to `category` to catch it.
            category.ledgerScope != ledger ->
                LedgerError.LineItemCategoryNotInLedger(position, categoryId, ledger)

            subcategoryId == null -> null

            subcategory == null -> LedgerError.LineItemUnknownCategory(position, subcategoryId)

            subcategory.parentId != categoryId ->
                LedgerError.LineItemSubcategoryNotUnderCategory(position, subcategoryId, categoryId)

            else -> null
        }
    }

    /** Memoised, and soft-deleted rows read as absent -- as they do at the entry level. */
    private suspend fun liveCategory(
        database: LedgerFlowDatabase,
        seen: MutableMap<String, CategoryEntity?>,
        id: String,
    ): CategoryEntity? {
        if (id !in seen) seen[id] = database.categoryDao().byId(id)
        return seen[id]?.takeIf { it.deletedAt == 0L }
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
}
