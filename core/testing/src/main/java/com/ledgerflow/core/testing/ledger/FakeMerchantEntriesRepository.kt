package com.ledgerflow.core.testing.ledger

import androidx.paging.LoadState
import androidx.paging.LoadStates
import androidx.paging.PagingData
import com.ledgerflow.core.domain.ledger.MerchantEntriesRepository
import com.ledgerflow.core.domain.ledger.MerchantTotal
import com.ledgerflow.core.model.LedgerListItem
import com.ledgerflow.core.model.LedgerType
import com.ledgerflow.core.model.Money
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map

/**
 * The merchant entries page's reads over in-memory rows (V2-6). Keyed by book
 * then merchant id, so a test that seeds only one book and reads the other
 * gets nothing: a fake that filtered a shared list would pass even if the
 * real statement had lost its book (Law 2). Filtered by window, not ignoring it.
 */
public class FakeMerchantEntriesRepository : MerchantEntriesRepository {

    public val entries: MutableStateFlow<Map<LedgerType, Map<String, List<LedgerListItem>>>> =
        MutableStateFlow(emptyMap())

    override fun observeEntries(
        ledger: LedgerType,
        merchantId: String,
        from: Int,
        to: Int,
    ): Flow<PagingData<LedgerListItem>> = entries.map {
        // Finished load states, stated: a static PagingData without them reports
        // refresh = Loading forever, and asSnapshot waits on it.
        PagingData.from(inWindow(it, ledger, merchantId, from, to), sourceLoadStates = LoadStates(DONE, DONE, DONE))
    }

    override fun observeTotal(ledger: LedgerType, merchantId: String, from: Int, to: Int): Flow<MerchantTotal> =
        entries.map { all ->
            val rows = inWindow(all, ledger, merchantId, from, to)
            MerchantTotal(Money.sum(rows.map { it.amount }), rows.size)
        }

    private fun inWindow(
        all: Map<LedgerType, Map<String, List<LedgerListItem>>>,
        ledger: LedgerType,
        merchantId: String,
        from: Int,
        to: Int,
    ) = all[ledger]?.get(merchantId).orEmpty().filter { it.localDate in from..to }
}

private val DONE = LoadState.NotLoading(endOfPaginationReached = true)
