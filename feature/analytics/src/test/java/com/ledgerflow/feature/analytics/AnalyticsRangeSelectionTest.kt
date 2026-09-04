package com.ledgerflow.feature.analytics

import com.google.common.truth.Truth.assertThat
import com.ledgerflow.core.common.time.Clock
import com.ledgerflow.core.common.time.LocalDates
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
     * **Tapping Custom asks how far back**, rather than selecting a window.
     *
     * It used to select a range with none. `range` became CUSTOM with null
     * dates, the window resolver fell through to `endingOn(today, CUSTOM)`, and
     * `CUSTOM.days` is a placeholder its own KDoc calls a bug to read — 30. So
     * the chip appeared selected and silently showed a Month, which is a
     * control that looks like it worked and did not.
     */
    @Test
    fun tappingCustom_opensTheCustomSheet_ratherThanSelectingAWindow() = runTest {
        val viewModel = viewModel()
        advanceUntilIdle()

        viewModel.onEvent(AnalyticsEvent.RangeSelected(AnalyticsRange.CUSTOM))
        advanceUntilIdle()

        assertThat(viewModel.state.value.showCustomSheet).isTrue()
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
    fun tappingCustom_whileAlreadyCustom_reopensTheSheet() = runTest {
        val viewModel = viewModel()
        advanceUntilIdle()
        viewModel.onEvent(AnalyticsEvent.CustomRangePicked(from = 20_000, to = 20_030))
        advanceUntilIdle()
        assertThat(viewModel.state.value.range).isEqualTo(AnalyticsRange.CUSTOM)

        viewModel.onEvent(AnalyticsEvent.RangeSelected(AnalyticsRange.CUSTOM))
        advanceUntilIdle()

        assertThat(viewModel.state.value.showCustomSheet).isTrue()
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
        assertThat(viewModel.state.value.showCustomSheet).isFalse()
    }

    /**
     * A typed period becomes the window (§5.6).
     *
     * "The last three months" is the way people describe a range; expressing it
     * as two calendar dates is arithmetic they should not have to do.
     */
    @Test
    fun atypedPeriodBecomesTheWindow_andClosesTheSheet() = runTest {
        val viewModel = viewModel()
        advanceUntilIdle()
        viewModel.onEvent(AnalyticsEvent.RangeSelected(AnalyticsRange.CUSTOM))

        viewModel.onEvent(AnalyticsEvent.CustomPeriodChanged(PeriodUnit.MONTHS, "3"))
        viewModel.onEvent(AnalyticsEvent.CustomPeriodApplied)
        advanceUntilIdle()

        val state = viewModel.state.value
        assertThat(state.range).isEqualTo(AnalyticsRange.CUSTOM)
        assertThat(state.showCustomSheet).isFalse()
        // Three calendar months back from the fixed clock, inclusive of today.
        val expected = requireNotNull(
            AnalyticsWindow.lastPeriod(LocalDates.of(FIXED_NOW), months = 3),
        )
        assertThat(state.customFrom).isEqualTo(expected.from)
        assertThat(state.customTo).isEqualTo(expected.to)
    }

    /** The three units combine into one window. */
    @Test
    fun theThreeUnitsCombine() = runTest {
        val viewModel = viewModel()
        advanceUntilIdle()

        viewModel.onEvent(AnalyticsEvent.CustomPeriodChanged(PeriodUnit.YEARS, "1"))
        viewModel.onEvent(AnalyticsEvent.CustomPeriodChanged(PeriodUnit.DAYS, "10"))
        viewModel.onEvent(AnalyticsEvent.CustomPeriodApplied)
        advanceUntilIdle()

        val expected = requireNotNull(
            AnalyticsWindow.lastPeriod(LocalDates.of(FIXED_NOW), years = 1, days = 10),
        )
        assertThat(viewModel.state.value.customFrom).isEqualTo(expected.from)
    }

    /**
     * **An empty form applies nothing**, rather than selecting a window of zero
     * days that would render as "you spent nothing".
     */
    @Test
    fun applyingAnEmptyPeriodChangesNothing() = runTest {
        val viewModel = viewModel()
        advanceUntilIdle()
        viewModel.onEvent(AnalyticsEvent.RangeSelected(AnalyticsRange.CUSTOM))

        viewModel.onEvent(AnalyticsEvent.CustomPeriodApplied)
        advanceUntilIdle()

        assertThat(viewModel.state.value.range).isEqualTo(AnalyticsRange.MONTH)
        assertThat(viewModel.state.value.showCustomSheet).isTrue()
    }

    /**
     * The fields take digits and nothing else.
     *
     * A field that accepts "12abc" and rejects it on apply makes the user hunt
     * for the mistake; one that never takes the letter leaves nothing to hunt.
     */
    @Test
    fun theFieldsRefuseAnythingButDigits() = runTest {
        val viewModel = viewModel()
        advanceUntilIdle()

        viewModel.onEvent(AnalyticsEvent.CustomPeriodChanged(PeriodUnit.MONTHS, "1a2b"))
        advanceUntilIdle()

        assertThat(viewModel.state.value.customMonths).isEqualTo("12")
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
