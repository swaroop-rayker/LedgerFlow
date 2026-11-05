package com.ledgerflow.core.data.ledger

import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import androidx.paging.map
import com.ledgerflow.core.data.vault.VaultSession
import com.ledgerflow.core.domain.analytics.MerchantCategoryBreakdown
import com.ledgerflow.core.domain.ledger.MerchantEntriesRepository
import com.ledgerflow.core.domain.ledger.MerchantTotal
import com.ledgerflow.core.model.LedgerListItem
import com.ledgerflow.core.model.LedgerType
import com.ledgerflow.core.model.Money
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map

/**
 * The merchant entries page's reads (V2-6), the Ledger list's shape over one
 * merchant and window. A fresh `PagingSource` per invalidation, for the reason
 * [DefaultLedgerRepository.observeEntries] gives (BUG10), so an entry deleted
 * in the Ledger leaves this list too.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@Singleton
public class DefaultMerchantEntriesRepository @Inject constructor(
    private val session: VaultSession,
) : MerchantEntriesRepository {

    override fun observeEntries(
        ledger: LedgerType,
        merchantId: String,
        from: Int,
        to: Int,
    ): Flow<PagingData<LedgerListItem>> =
        session.whenUnlocked().flatMapLatest { database ->
            val dao = database?.merchantEntriesDao() ?: return@flatMapLatest flowOf(PagingData.empty())
            Pager(
                config = PagingConfig(pageSize = PAGE_SIZE, enablePlaceholders = false),
                pagingSourceFactory = {
                    when (ledger) {
                        LedgerType.DEBIT -> dao.pagingMerchantDebits(merchantId, from, to)
                        LedgerType.CREDIT -> dao.pagingMerchantCredits(merchantId, from, to)
                    }
                },
            ).flow.map { page -> page.map { row -> row.toDomain(ledger) } }
        }

    override fun observeTotal(ledger: LedgerType, merchantId: String, from: Int, to: Int): Flow<MerchantTotal> =
        session.whenUnlocked().flatMapLatest { database ->
            val dao = database?.merchantEntriesDao() ?: return@flatMapLatest flowOf(MerchantTotal.None)
            when (ledger) {
                LedgerType.DEBIT -> dao.observeMerchantDebitTotal(merchantId, from, to)
                LedgerType.CREDIT -> dao.observeMerchantCreditTotal(merchantId, from, to)
            }.map { MerchantTotal(Money(it.totalMinor), it.entries) }
        }

    override fun observeEntriesInMerchantCategories(
        ledger: LedgerType,
        merchantCategoryIds: Set<String>,
        since: Int,
    ): Flow<PagingData<LedgerListItem>> {
        val uncategorised = if (MerchantCategoryBreakdown.UNCATEGORISED_ID in merchantCategoryIds) 1 else 0
        // Never an empty list (Room cannot bind one): with only Uncategorised
        // chosen, an id no category has keeps the IN clause matching nothing.
        val ids = (merchantCategoryIds - MerchantCategoryBreakdown.UNCATEGORISED_ID).toList()
            .ifEmpty { listOf(NO_SUCH_CATEGORY) }
        return session.whenUnlocked().flatMapLatest { database ->
            val dao = database?.merchantEntriesDao() ?: return@flatMapLatest flowOf(PagingData.empty())
            Pager(
                config = PagingConfig(pageSize = PAGE_SIZE, enablePlaceholders = false),
                pagingSourceFactory = {
                    when (ledger) {
                        LedgerType.DEBIT -> dao.pagingDebitsInMerchantCategories(ids, uncategorised, since)
                        LedgerType.CREDIT -> dao.pagingCreditsInMerchantCategories(ids, uncategorised, since)
                    }
                },
            ).flow.map { page -> page.map { row -> row.toDomain(ledger) } }
        }
    }

    private companion object {
        /** The Ledger's page size: the same rows, scrolled the same way. */
        const val PAGE_SIZE = 30

        /** An id no merchant category has: theirs are UUIDs. */
        const val NO_SUCH_CATEGORY = "no-such-merchant-category"
    }
}
