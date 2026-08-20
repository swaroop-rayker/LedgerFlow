package com.ledgerflow.core.data.ledger

import com.ledgerflow.core.database.LedgerFlowDatabase
import com.ledgerflow.core.domain.ledger.ApprovalRequest
import com.ledgerflow.core.domain.ledger.LedgerError

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
}
