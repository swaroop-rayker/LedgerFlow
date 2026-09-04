package com.ledgerflow.feature.analytics

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ledgerflow.core.common.time.Clock
import com.ledgerflow.core.common.time.LocalDates
import com.ledgerflow.core.designsystem.chart.LfViewportGesture
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
            is AnalyticsEvent.RangeSelected -> selectRange(event.range)

            is AnalyticsEvent.Surface -> showSurface(event)

            AnalyticsEvent.Resumed -> load()

            is AnalyticsEvent.CustomPeriodChanged ->
                changePeriodField(event.unit, event.text)

            AnalyticsEvent.CustomPeriodApplied -> applyTypedPeriod()

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

            is AnalyticsEvent.ViewportMoved -> moveViewport(event.gesture)

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
            AnalyticsEvent.TreemapToggled -> {
                // Presentational, like the expand below: the same totals drawn
                // a second way, so no query.
                _state.update { it.copy(treemapShown = !it.treemapShown) }
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

    /**
     * The three "is this dialog open" events, which change no data.
     *
     * Together rather than as three arms of `onEvent`, which detekt was right
     * to flag: a handler whose bulk is visibility toggles hides the four
     * branches that actually re-query.
     */
    private fun showSurface(event: AnalyticsEvent.Surface) {
        _state.update { current ->
            when (event) {
                // Closing the sheet closes any picker over it, or reopening the
                // sheet would come back with a dialog the user had dismissed.
                is AnalyticsEvent.FilterSheetShown ->
                    current.copy(showFilterSheet = event.visible, openFilterField = null)

                is AnalyticsEvent.FilterFieldOpened ->
                    current.copy(openFilterField = event.field)

                is AnalyticsEvent.RangePickerShown ->
                    current.copy(showRangePicker = event.visible)

                // Opening the date picker closes the sheet behind it: they are
                // two answers to one question, and leaving the sheet stacked
                // under the picker means cancelling lands back on a form the
                // user has already moved past.
                is AnalyticsEvent.CustomSheetShown ->
                    current.copy(showCustomSheet = event.visible)
            }
        }
    }

    /**
     * A pan or a pinch, resolved into a new window and a fresh query.
     *
     * **This is the whole of what a gesture does** (ADR-0005). The chart holds
     * no series to transform — §11 forbids it having one — so the gesture moves
     * the window and the next frame is a re-query at the new resolution, which
     * is exactly the ownership a charting library would have taken.
     */
    private fun moveViewport(gesture: LfViewportGesture) {
        val window = currentWindow(_state.value)
        val moved = when (gesture) {
            is LfViewportGesture.Pan -> window.pannedBy(gesture.fractionOfSpan)
            is LfViewportGesture.Zoom -> window.zoomedBy(gesture.scale)
        }
        // A gesture that rounds to no movement, or one that hit a zoom limit,
        // must not re-issue the identical query -- it would flicker the chart
        // to arrive back where it already was.
        if (moved.from == window.from && moved.to == window.to) return

        _state.update {
            // A panned or zoomed window is no longer "Month", so the chip
            // follows the data rather than claiming a range the screen is not
            // showing.
            it.copy(
                range = AnalyticsRange.CUSTOM,
                customFrom = moved.from,
                customTo = moved.to,
            )
        }
        load()
    }

    /**
     * A range chip was tapped.
     *
     * Its own function because `onEvent` had grown past the point where the
     * branches that *re-query* are visible among the ones that only open
     * something — the same reason `showSurface` exists.
     */
    private fun selectRange(range: AnalyticsRange) {
        // **Custom is a question, not a range.** Selecting it cannot set a
        // window, because the dates are the user's to choose — so the chip
        // opens the Custom sheet instead. It did not, and the result was a
        // control that looked selectable and did nothing visible: `range`
        // became CUSTOM with no dates, `currentWindow` fell through to
        // `endingOn(today, CUSTOM)`, and `CUSTOM.days` is the placeholder 30
        // that the enum's own KDoc calls a bug to read. Tapping "Custom"
        // silently showed a Month (BUG21).
        //
        // **Before the equality check, deliberately.** Tapping Custom while a
        // custom range is already active is how someone changes the period
        // they typed; an early return there would make the chip dead exactly
        // when it is the selected one.
        if (range == AnalyticsRange.CUSTOM) {
            _state.update { it.copy(showCustomSheet = true) }
            return
        }
        if (range == _state.value.range) return

        _state.update {
            // Leaving a custom range discards its dates: keeping them would
            // make "Custom" silently reselect a window the user may have set
            // weeks ago.
            it.copy(range = range, customFrom = null, customTo = null)
        }
        load()
    }

    /**
     * One of the three period fields.
     *
     * **Digits only, capped in length.** A field that accepts "12abc" and
     * rejects it on apply makes the user hunt for the mistake; one that never
     * takes the letter leaves nothing to hunt for.
     */
    private fun changePeriodField(unit: PeriodUnit, text: String) {
        val cleaned = text.filter { it.isDigit() }.take(MAX_PERIOD_DIGITS)
        _state.update { current ->
            when (unit) {
                PeriodUnit.YEARS -> current.copy(customYears = cleaned)
                PeriodUnit.MONTHS -> current.copy(customMonths = cleaned)
                PeriodUnit.DAYS -> current.copy(customDays = cleaned)
            }
        }
    }

    /**
     * Turn the three typed fields into the window (§5.6).
     *
     * **Silently does nothing when the form is empty**, which is what
     * `lastPeriod` returning null means — and the sheet keeps its Apply button
     * disabled in that state, so this is the second half of a rule rather than
     * the only one. A zero-length window would render as an empty chart, which
     * reads as "you spent nothing" rather than "you have not typed anything".
     */
    private fun applyTypedPeriod() {
        val current = _state.value
        val window = AnalyticsWindow.lastPeriod(
            today = LocalDates.of(clock.nowMillis()),
            years = current.customYears.toIntOrNull() ?: 0,
            months = current.customMonths.toIntOrNull() ?: 0,
            days = current.customDays.toIntOrNull() ?: 0,
        ) ?: return

        _state.update {
            it.copy(
                range = AnalyticsRange.CUSTOM,
                customFrom = window.from,
                customTo = window.to,
                showCustomSheet = false,
            )
        }
        load()
    }

    /**
     * The window the current state describes.
     *
     * One resolver, because the gesture handler and the loader must agree about
     * what is on screen: a pan computed from a different window than the one
     * displayed moves the chart somewhere the user did not point.
     */
    private fun currentWindow(state: AnalyticsUiState): AnalyticsWindow {
        val from = state.customFrom
        val to = state.customTo
        return if (state.range == AnalyticsRange.CUSTOM && from != null && to != null) {
            AnalyticsWindow.custom(from, to)
        } else {
            AnalyticsWindow.endingOn(LocalDates.of(clock.nowMillis()), state.range)
        }
    }

    private fun load() {
        loadJob?.cancel()
        _state.update { it.copy(isLoading = true) }
        loadJob = viewModelScope.launch {
            val current = _state.value
            val window = currentWindow(current)
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

/** Three digits is 999 years; nothing legible needs four. */
private const val MAX_PERIOD_DIGITS = 3
