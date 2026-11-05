package com.ledgerflow.core.domain.ledger

import androidx.paging.PagingData
import com.ledgerflow.core.model.LedgerListItem
import com.ledgerflow.core.model.LedgerType
import com.ledgerflow.core.model.Money
import kotlinx.coroutines.flow.Flow

/**
 * One merchant's entries in one book and window: the page Analytics opens from
 * a merchant (V2-6, V2-PLAN §5.4's last drill step). Reads only.
 *
 * Bound to a [LedgerType] in every call, with no variant that reads both books
 * (Law 2). [from] and [to] are days since epoch, inclusive: Analytics' window,
 * so the page adds up to the figure that was tapped.
 */
public interface MerchantEntriesRepository {

    /** Newest first, paged as the Ledger is (ADR-0014). */
    public fun observeEntries(
        ledger: LedgerType,
        merchantId: String,
        from: Int,
        to: Int,
    ): Flow<PagingData<LedgerListItem>>

    /** What [observeEntries] lists, summed and counted, kept current as entries change. */
    public fun observeTotal(ledger: LedgerType, merchantId: String, from: Int, to: Int): Flow<MerchantTotal>

    /**
     * The Ledger list narrowed to merchant categories (V2-6, V2-PLAN §5.3):
     * [ledger]'s entries since [since] whose merchant is filed under one of
     * [merchantCategoryIds] now, `MerchantCategoryBreakdown.UNCATEGORISED_ID`
     * meaning the merchants with no live category. Entries with no merchant
     * never match. Kept current as merchants are re-filed.
     */
    public fun observeEntriesInMerchantCategories(
        ledger: LedgerType,
        merchantCategoryIds: Set<String>,
        since: Int,
    ): Flow<PagingData<LedgerListItem>>
}

/** One merchant's entries in one book and window: their sum (Law 3, `Long` underneath) and how many. */
public data class MerchantTotal(val total: Money, val entries: Int) {
    public companion object {
        public val None: MerchantTotal = MerchantTotal(Money(0L), 0)
    }
}
