package com.ledgerflow.core.domain.ledger

import com.ledgerflow.core.model.EntryAssignment
import com.ledgerflow.core.model.EntryOrigin
import com.ledgerflow.core.model.ForeignAmount
import com.ledgerflow.core.model.LedgerEntry
import com.ledgerflow.core.model.LedgerType
import com.ledgerflow.core.model.LineItemKind
import com.ledgerflow.core.model.Money
import kotlinx.coroutines.flow.Flow

/**
 * The ledger's write path and its narrow reads (SPEC.md §6.1, ADR-0002).
 *
 * **[approve] is the only way a row reaches `ledger_entry`** (Law 1), and the
 * only sanctioned caller of it is `ApproveTransactionUseCase`. That is not a
 * convention a reviewer has to notice: `LedgerSingleWriterTest` scans every
 * module's sources and fails the build on any other call site.
 *
 * Everything [approve] validates is checked *inside* the transaction that
 * writes. Checking first and inserting afterwards would leave a window in which
 * a category is soft-deleted between the two, and the entry lands pointing at a
 * row no picker will ever show again.
 */
public interface LedgerRepository {

    /**
     * Validates and commits one entry with its line items, atomically.
     *
     * A partial write here is an entry whose items are missing -- a total that
     * is silently wrong rather than visibly absent, which is the harder bug to
     * ever notice.
     */
    public suspend fun approve(request: ApprovalRequest): LedgerResult<LedgerEntry>

    /**
     * The combinations this ledger has actually seen, most-used first.
     *
     * Feeds the repeat-expense chips that make §5.4's ≤4-tap target reachable.
     * Takes a [LedgerType] and reads that book's view alone; there is
     * deliberately no variant returning both (Law 2).
     */
    public fun observeRecentCombos(ledger: LedgerType, limit: Int): Flow<List<EntryCombo>>
}

/**
 * An entry a human has decided to commit.
 *
 * `currency` is absent on purpose: `amount_minor` is always the base currency
 * (§5.8), so letting a caller name one would create a value the repository
 * would have to either trust or override. It reads `app_meta.baseCurrency`
 * instead.
 *
 * `localDate` is absent for the same reason -- it is `occurredAt` in the
 * capture device's timezone, and two fields that must agree are two fields that
 * can disagree.
 */
public data class ApprovalRequest(
    val ledger: LedgerType,
    /** Positive, base currency, minor units (Law 3). */
    val amount: Money,
    val occurredAt: Long,
    val assignment: EntryAssignment = EntryAssignment(),
    val note: String? = null,
    val origin: EntryOrigin = EntryOrigin.Manual,
    val foreign: ForeignAmount? = null,
    val isRecurring: Boolean = false,
    val lineItems: List<NewLineItem> = emptyList(),
)

/**
 * A line the user typed, before it has an id or a position.
 *
 * When the lines do not sum to the entry's amount, the approval writes the
 * difference as an [LineItemKind.UNALLOCATED] row rather than refusing the save
 * or quietly letting the total drift -- the same rule §5.3 sets for an
 * unbalanced receipt, applied here so manual and OCR entries cannot disagree
 * about what an unbalanced bill means.
 */
public data class NewLineItem(
    val name: String,
    val total: Money,
    val kind: LineItemKind = LineItemKind.ITEM,
    val quantityMilli: Long = com.ledgerflow.core.model.LineItem.UNIT_QUANTITY_MILLI,
    val unitPrice: Money? = null,
    val categoryId: String? = null,
    val subcategoryId: String? = null,
)

/**
 * A category/merchant/instrument combination the user has filed before.
 *
 * Ranked by use count with recency as the tiebreak, so a combination used every
 * week outranks one used twice yesterday -- "frequent" and "recent" in §5.4 are
 * one ordering, not two lists.
 */
public data class EntryCombo(
    val categoryId: String,
    val subcategoryId: String?,
    val merchantId: String?,
    val paymentMethodId: String?,
    val uses: Int,
    val lastUsedAt: Long,
)
