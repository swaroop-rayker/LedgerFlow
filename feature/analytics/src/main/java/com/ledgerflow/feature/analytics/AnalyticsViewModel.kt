package com.ledgerflow.feature.analytics

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ledgerflow.core.common.time.Clock
import com.ledgerflow.core.common.time.LocalDates
import com.ledgerflow.core.domain.analytics.AnalyticsFilters
import com.ledgerflow.core.domain.analytics.AnalyticsRange
import com.ledgerflow.core.domain.analytics.AnalyticsWindow
import com.ledgerflow.core.domain.ledger.LedgerRepository
import com.ledgerflow.core.domain.taxonomy.CategoryRepository
import com.ledgerflow.core.domain.taxonomy.MerchantRepository
import com.ledgerflow.core.domain.usecase.GetAnalyticsSnapshotUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * A1–A5's state (`SPEC.md` §5.6).
 *
 * **A change of window is a re-query, not a filter over held data.** That is the
 * arrangement ADR-0005 committed to: the viewport lives here, beside the query,
 * because §11 forbids handing a chart more points than it has horizontal pixels
 * and the binning therefore has to happen in SQL. Every range change cancels the
 * in-flight load and issues a new one at the new resolution.
 *
 * **Cancelling matters.** Tapping through Day → Week → Month → 5Y quickly would
 * otherwise leave four loads racing, and the one that finishes last wins rather
 * than the one the user asked for — a chart that settles on the wrong range and
 * looks like a bug in the data.
 */
@HiltViewModel
public class AnalyticsViewModel @Inject constructor(
    private val getSnapshot: GetAnalyticsSnapshotUseCase,
    private val ledgerRepository: LedgerRepository,
    private val categories: CategoryRepository,
    private val merchants: MerchantRepository,
    private val clock: Clock,
) : ViewModel() {

    private val _state = MutableStateFlow(AnalyticsUiState())
    public val state: StateFlow<AnalyticsUiState> = _state.asStateFlow()

    private var loadJob: Job? = null

    init {
        load()
    }

    public fun onEvent(event: AnalyticsEvent) {
        when (event) {
            is AnalyticsEvent.RangeSelected -> {
                if (event.range == _state.value.range) return
                _state.update {
                    // Leaving a custom range discards its dates: keeping them
                    // would make "Custom" silently reselect a window the user
                    // may have set weeks ago.
                    it.copy(range = event.range, customFrom = null, customTo = null)
                }
                load()
            }

            AnalyticsEvent.FiltersClicked -> _state.update { it.copy(showFilterSheet = true) }
            AnalyticsEvent.FiltersDismissed -> _state.update { it.copy(showFilterSheet = false) }

            is AnalyticsEvent.FiltersChanged -> {
                _state.update { it.copy(filters = event.filters) }
                load()
            }

            AnalyticsEvent.FiltersCleared -> {
                _state.update {
                    it.copy(filters = AnalyticsFilters.None, showFilterSheet = false)
                }
                load()
            }

            AnalyticsEvent.CustomRangeClicked -> _state.update {
                it.copy(showRangePicker = true)
            }

            AnalyticsEvent.CustomRangeDismissed -> _state.update {
                it.copy(showRangePicker = false)
            }

            is AnalyticsEvent.CustomRangePicked -> {
                _state.update {
                    it.copy(
                        range = AnalyticsRange.CUSTOM,
                        customFrom = event.from,
                        customTo = event.to,
                        showRangePicker = false,
                    )
                }
                load()
            }
            AnalyticsEvent.ComparisonToggled -> {
                _state.update { it.copy(comparePrevious = !it.comparePrevious) }
                load()
            }
            is AnalyticsEvent.CategoryExpanded -> {
                // Purely presentational: the subcategories are already in the
                // snapshot, so expanding one is not a query.
                _state.update { current ->
                    val next =
                        if (current.expandedCategoryId == event.categoryId) {
                            null
                        } else {
                            event.categoryId
                        }
                    current.copy(expandedCategoryId = next)
                }
            }
        }
    }

    private fun load() {
        loadJob?.cancel()
        _state.update { it.copy(isLoading = true) }
        loadJob = viewModelScope.launch {
            val current = _state.value
            val today = LocalDates.of(clock.nowMillis())
            val from = current.customFrom
            val to = current.customTo
            val window = if (current.range == AnalyticsRange.CUSTOM && from != null && to != null) {
                AnalyticsWindow.custom(from, to)
            } else {
                AnalyticsWindow.endingOn(today, current.range)
            }
            val currency = ledgerRepository.baseCurrency() ?: DEFAULT_CURRENCY
            val snapshot = getSnapshot(
                ledger = current.ledger,
                window = window,
                comparePrevious = current.comparePrevious,
                filters = current.filters,
            )
            _state.update {
                it.copy(
                    isLoading = false,
                    snapshot = snapshot,
                    baseCurrency = currency,
                    allCategories = categories.observe(current.ledger).first(),
                    allMerchants = merchants.observeAll().first(),
                )
            }
        }
    }

    private companion object {
        /** Matches `EntryUiState.DEFAULT_CURRENCY`; onboarding always sets one. */
        const val DEFAULT_CURRENCY = "INR"
    }
}
