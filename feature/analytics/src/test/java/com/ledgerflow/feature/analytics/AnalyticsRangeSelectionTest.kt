package com.ledgerflow.feature.analytics

import com.google.common.truth.Truth.assertThat
import com.ledgerflow.core.common.time.Clock
import com.ledgerflow.core.domain.analytics.AnalyticsFilters
import com.ledgerflow.core.domain.analytics.AnalyticsRange
import com.ledgerflow.core.domain.analytics.AnalyticsRepository
import com.ledgerflow.core.domain.analytics.AnalyticsSnapshot
import com.ledgerflow.core.domain.analytics.AnalyticsWindow
import com.ledgerflow.core.domain.usecase.GetAnalyticsSnapshotUseCase
import com.ledgerflow.core.model.LedgerType
import com.ledgerflow.core.model.Money
import com.ledgerflow.core.testing.ledger.FakeLedgerRepository
import com.ledgerflow.core.testing.taxonomy.FakeCategoryRepository
import com.ledgerflow.core.testing.taxonomy.FakeMerchantRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test

/**
 * The range chips, and the one of them that is not a range.
 *
 * "Custom" sits in the same row as Day, Week and 5Y and looks like a seventh
 * choice. It is not: the other six *are* windows, and this one is a question —
 * the dates belong to the user. Treating it as a range is what broke it, so
 * these tests pin the distinction rather than the implementation.
 */
class AnalyticsRangeSelectionTest {

    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() = Dispatchers.resetMain()

    /**
     * **Tapping Custom asks for dates.**
     *
     * It used to select a range with none. `range` became CUSTOM with null
     * dates, the window resolver fell through to `endingOn(today, CUSTOM)`, and
     * `CUSTOM.days` is a placeholder its own KDoc calls a bug to read — 30. So
     * the chip appeared selected and silently showed a Month, which is a
     * control that looks like it worked and did not.
     */
    @Test
    fun tappingCustom_opensTheDatePicker_ratherThanSelectingAWindow() = runTest {
        val viewModel = viewModel()
        advanceUntilIdle()

        viewModel.onEvent(AnalyticsEvent.RangeSelected(AnalyticsRange.CUSTOM))
        advanceUntilIdle()

        assertThat(viewModel.state.value.showRangePicker).isTrue()
        // And it has *not* silently moved the window it is showing.
        assertThat(viewModel.state.value.range).isEqualTo(AnalyticsRange.MONTH)
    }

    /**
     * **Still asks when a custom range is already active.**
     *
     * That is how someone changes the dates they picked. The handler's early
     * "same range, do nothing" return would otherwise make the chip dead
     * exactly when it is the selected one.
     */
    @Test
    fun tappingCustom_whileAlreadyCustom_reopensThePicker() = runTest {
        val viewModel = viewModel()
        advanceUntilIdle()
        viewModel.onEvent(AnalyticsEvent.CustomRangePicked(from = 20_000, to = 20_030))
        advanceUntilIdle()
        assertThat(viewModel.state.value.range).isEqualTo(AnalyticsRange.CUSTOM)

        viewModel.onEvent(AnalyticsEvent.RangeSelected(AnalyticsRange.CUSTOM))
        advanceUntilIdle()

        assertThat(viewModel.state.value.showRangePicker).isTrue()
        // The dates already chosen survive the reopen; cancelling must not
        // silently discard the window the screen is showing.
        assertThat(viewModel.state.value.customFrom).isEqualTo(20_000)
        assertThat(viewModel.state.value.customTo).isEqualTo(20_030)
    }

    /** The other six chips still select a window and reload, unchanged. */
    @Test
    fun tappingArealRange_selectsItWithoutOpeningAnything() = runTest {
        val viewModel = viewModel()
        advanceUntilIdle()

        viewModel.onEvent(AnalyticsEvent.RangeSelected(AnalyticsRange.YEAR))
        advanceUntilIdle()

        assertThat(viewModel.state.value.range).isEqualTo(AnalyticsRange.YEAR)
        assertThat(viewModel.state.value.showRangePicker).isFalse()
    }

    private fun viewModel() = AnalyticsViewModel(
        getSnapshot = GetAnalyticsSnapshotUseCase(EmptyAnalytics),
        ledgerRepository = FakeLedgerRepository(),
        categories = FakeCategoryRepository(),
        merchants = FakeMerchantRepository(),
        clock = Clock { FIXED_NOW },
    )

    private object EmptyAnalytics : AnalyticsRepository {
        override suspend fun snapshot(
            ledger: LedgerType,
            window: AnalyticsWindow,
            comparePrevious: Boolean,
            filters: AnalyticsFilters,
        ): AnalyticsSnapshot = AnalyticsSnapshot(
            ledger = ledger,
            window = window,
            total = Money(0L),
            previousTotal = null,
            transactionCount = 0,
            timeBuckets = emptyList(),
            categories = emptyList(),
            subcategories = emptyMap(),
            merchants = emptyList(),
            paymentMethods = emptyList(),
        )
    }

    private companion object {
        /** 2026-09-04, the day the chip was reported broken. */
        const val FIXED_NOW = 1_788_480_000_000L
    }
}
