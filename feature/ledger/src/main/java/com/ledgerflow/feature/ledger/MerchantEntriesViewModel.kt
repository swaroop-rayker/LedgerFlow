package com.ledgerflow.feature.ledger

import androidx.compose.runtime.Immutable
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.paging.PagingData
import androidx.paging.cachedIn
import androidx.paging.map
import com.ledgerflow.core.domain.ledger.LedgerRepository
import com.ledgerflow.core.domain.ledger.MerchantEntriesRepository
import com.ledgerflow.core.domain.ledger.MerchantTotal
import com.ledgerflow.core.model.LedgerListItem
import com.ledgerflow.core.model.LedgerType
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

/**
 * One merchant's entries, opened from Analytics (V2-6, V2-PLAN §5.4's last
 * drill step). Everything that names the page comes from the route: the book,
 * the merchant, the window Analytics was showing, and whether its filters were
 * on, so the page lists exactly what the tapped figure added up.
 */
@Immutable
public data class MerchantEntriesUiState(
    val ledger: LedgerType,
    val merchantName: String,
    val from: Int,
    val to: Int,
    /** Analytics had filters on; this page does not apply them, and says so. */
    val analyticsFiltered: Boolean,
    /** Null until the first read lands, so the header never flashes ₹0.00. */
    val total: MerchantTotal? = null,
    val currency: String = DEFAULT_CURRENCY,
) {
    internal companion object {
        const val DEFAULT_CURRENCY = "INR"
    }
}

@HiltViewModel
public class MerchantEntriesViewModel @Inject constructor(
    merchantEntries: MerchantEntriesRepository,
    ledgerRepository: LedgerRepository,
    savedStateHandle: SavedStateHandle,
) : ViewModel() {

    private val initial = MerchantEntriesUiState(
        ledger = savedStateHandle.get<String>(LEDGER_ARG)
            ?.let { name -> LedgerType.entries.firstOrNull { it.name == name } }
            ?: LedgerType.DEBIT,
        merchantName = savedStateHandle.get<String>(MERCHANT_NAME_ARG).orEmpty(),
        from = savedStateHandle.get<Int>(FROM_ARG) ?: 0,
        to = savedStateHandle.get<Int>(TO_ARG) ?: 0,
        analyticsFiltered = savedStateHandle.get<Boolean>(FILTERED_ARG) ?: false,
    )
    private val merchantId: String = savedStateHandle.get<String>(MERCHANT_ID_ARG).orEmpty()

    /**
     * Paged and cached in the ViewModel, as the Ledger's list is: a rotation
     * keeps its pages. Each row's merchant is dropped: the page's title names
     * it, and "Swiggy ·" on every row said it again five times. A row without
     * a merchant reads as its category, as it does in the Ledger.
     */
    public val entries: Flow<PagingData<LedgerListItem>> = merchantEntries
        .observeEntries(initial.ledger, merchantId, initial.from, initial.to)
        .map { page -> page.map { row -> row.copy(merchantName = null) } }
        .cachedIn(viewModelScope)

    public val state: StateFlow<MerchantEntriesUiState> = combine(
        merchantEntries.observeTotal(initial.ledger, merchantId, initial.from, initial.to),
        flow { emit(ledgerRepository.baseCurrency() ?: MerchantEntriesUiState.DEFAULT_CURRENCY) },
    ) { total, currency ->
        initial.copy(total = total, currency = currency)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), initial)

    public companion object {
        /**
         * The route's argument names, read by string because `:feature:ledger`
         * cannot see `Destination.MerchantEntries` in `:app`.
         * `MerchantEntriesArgumentTest` holds the two halves together.
         */
        public const val LEDGER_ARG: String = "ledger"
        public const val MERCHANT_ID_ARG: String = "merchantId"
        public const val MERCHANT_NAME_ARG: String = "merchantName"
        public const val FROM_ARG: String = "from"
        public const val TO_ARG: String = "to"
        public const val FILTERED_ARG: String = "analyticsFiltered"

        private const val STOP_TIMEOUT_MS = 5_000L
    }
}
