package com.ledgerflow.feature.dashboard

import com.google.common.truth.Truth.assertThat
import com.ledgerflow.core.common.time.Clock
import com.ledgerflow.core.common.time.ProcessUptime
import com.ledgerflow.core.domain.analytics.MonthToDate
import com.ledgerflow.core.domain.backup.ObserveLastBackupUseCase
import com.ledgerflow.core.domain.diagnostics.DismissCrashReportsUseCase
import com.ledgerflow.core.domain.diagnostics.ObserveCrashReportsUseCase
import com.ledgerflow.core.domain.diagnostics.SaveCrashReportsUseCase
import com.ledgerflow.core.domain.ingest.IngestSourceStatus
import com.ledgerflow.core.domain.ingest.IngestSourceType
import com.ledgerflow.core.domain.ingest.ListenerHealthRecord
import com.ledgerflow.core.domain.ingest.ListenerHealthStore
import com.ledgerflow.core.domain.ingest.TransactionIngestSource
import com.ledgerflow.core.domain.usecase.AcknowledgeCaptureGapUseCase
import com.ledgerflow.core.domain.usecase.GetHomeSummaryUseCase
import com.ledgerflow.core.domain.usecase.GetIngestSourceStatusUseCase
import com.ledgerflow.core.domain.usecase.GetNotificationCaptureHealthUseCase
import com.ledgerflow.core.domain.usecase.ObserveCaptureGapUseCase
import com.ledgerflow.core.domain.usecase.ObservePendingCountUseCase
import com.ledgerflow.core.model.LedgerType
import com.ledgerflow.core.model.Money
import com.ledgerflow.core.testing.analytics.FakeAnalyticsRepository
import com.ledgerflow.core.testing.backup.FakeBackupRepository
import com.ledgerflow.core.testing.diagnostics.FakeCrashReportRepository
import com.ledgerflow.core.testing.inbox.FakePendingRepository
import com.ledgerflow.core.testing.ingest.FakeCaptureGapStore
import com.ledgerflow.core.testing.ledger.FakeLedgerRepository
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test

/**
 * Home v1's ViewModel (V2-PLAN BUG-C): which book each card reads, and when
 * the figures are read again.
 *
 * The fakes answer per book, so a card wired to the wrong ledger shows the
 * other book's rows or figure here rather than a plausible one (Law 2).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DashboardHomeTest {

    private val dispatcher = StandardTestDispatcher()
    private val ledger = FakeLedgerRepository()
    private val analytics = FakeAnalyticsRepository()
    private var now = millisOn(2026, 10, 16)

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        analytics.totals[LedgerType.DEBIT] = { window -> Money(DEBIT_BASE + window.from) }
        analytics.totals[LedgerType.CREDIT] = { window -> Money(CREDIT_BASE + window.from) }
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun recentLists_areOneBookEach() = runTest(dispatcher) {
        ledger.emitEntries(LedgerType.DEBIT, HomeFixtures.spending)
        ledger.emitEntries(LedgerType.CREDIT, HomeFixtures.income)

        val vm = viewModel()
        advanceUntilIdle()

        assertThat(vm.state.value.recentSpending?.map { it.ledger }?.distinct()).containsExactly(LedgerType.DEBIT)
        assertThat(vm.state.value.recentIncome?.map { it.ledger }?.distinct()).containsExactly(LedgerType.CREDIT)
        assertThat(ledger.recentReads).containsExactly(LedgerType.DEBIT to 5, LedgerType.CREDIT to 5)
    }

    @Test
    fun recentSpending_isTheNewestFive() = runTest(dispatcher) {
        val six = HomeFixtures.spending + HomeFixtures.entry(
            "old", LedgerType.DEBIT, 1_00L, HomeFixtures.TODAY - 20, 9, "Old Shop", null,
        )
        ledger.emitEntries(LedgerType.DEBIT, six.shuffled(java.util.Random(7)))

        val vm = viewModel()
        advanceUntilIdle()

        assertThat(vm.state.value.recentSpending?.map { it.id })
            .containsExactly("d1", "d2", "d3", "d4", "d5").inOrder()
    }

    /**
     * The month is the clock's month. Spending and income each come from their
     * own book: the fake answers a different base per ledger.
     */
    @Test
    fun theSummary_isTodaysMonth_perBook() = runTest(dispatcher) {
        val vm = viewModel()
        advanceUntilIdle()

        val today = LocalDate.of(2026, 10, 16).toEpochDay().toInt()
        val summary = requireNotNull(vm.state.value.summary)
        assertThat(summary.today).isEqualTo(today)
        assertThat(summary.window).isEqualTo(MonthToDate.window(today))
        assertThat(summary.income.total).isEqualTo(Money(CREDIT_BASE + summary.window.from))
        assertThat(summary.spending.previousTotal).isEqualTo(Money(DEBIT_BASE + summary.previousWindow.from))
    }

    /**
     * An approval made while Home is open (from a notification, say) reaches
     * the figures with no resume: the book's re-emission asks again.
     */
    @Test
    fun aChangeToEitherBook_readsTheFiguresAgain() = runTest(dispatcher) {
        val vm = viewModel()
        advanceUntilIdle()
        val before = analytics.snapshotReads.size

        analytics.totals[LedgerType.CREDIT] = { Money(1L) }
        ledger.emitEntries(LedgerType.CREDIT, HomeFixtures.income)
        advanceUntilIdle()

        assertThat(analytics.snapshotReads.size).isGreaterThan(before)
        assertThat(vm.state.value.summary?.income?.total).isEqualTo(Money(1L))
    }

    /**
     * A change arriving while the figures are being read waits for that read
     * rather than cancelling it.
     *
     * At launch three requests land together (the first read, the first
     * resume, both books' first emission). Cancelling the read in progress on
     * each threw that work away; now it finishes and shows, and one follow-up
     * read covers whatever arrived meanwhile. (It did not change LF Bench's
     * time to full display, ~2.65 s before and after; that cost is elsewhere.)
     */
    @Test
    fun aChangeDuringARead_doesNotRestartIt() = runTest(dispatcher) {
        val gate = kotlinx.coroutines.CompletableDeferred<Unit>()
        var completed = 0
        val slow = object : com.ledgerflow.core.domain.analytics.AnalyticsRepository by analytics {
            override suspend fun snapshot(
                ledger: LedgerType,
                window: com.ledgerflow.core.domain.analytics.AnalyticsWindow,
                comparePrevious: Boolean,
                filters: com.ledgerflow.core.domain.analytics.AnalyticsFilters,
            ): com.ledgerflow.core.domain.analytics.AnalyticsSnapshot {
                gate.await()
                return analytics.snapshot(ledger, window, comparePrevious, filters).also { completed++ }
            }
        }
        val vm = viewModel(GetHomeSummaryUseCase(slow))
        advanceUntilIdle()

        // A burst while the first read is held: an approval, then a resume.
        ledger.emitEntries(LedgerType.DEBIT, HomeFixtures.spending)
        vm.refresh()
        advanceUntilIdle()
        gate.complete(Unit)
        advanceUntilIdle()

        assertThat(vm.state.value.summary).isNotNull()
        // The held read finished, and one more covered the burst: two, not one.
        assertThat(completed).isEqualTo(2)
    }

    /** Opened on the 31st, resumed on the 1st: "this month" moves with it. */
    @Test
    fun aResumeInANewMonth_readsTheNewMonth() = runTest(dispatcher) {
        now = millisOn(2026, 10, 31)
        val vm = viewModel()
        advanceUntilIdle()

        now = millisOn(2026, 11, 1)
        vm.refresh()
        advanceUntilIdle()

        assertThat(vm.state.value.summary?.window?.from)
            .isEqualTo(LocalDate.of(2026, 11, 1).toEpochDay().toInt())
    }

    @Test
    fun theBaseCurrency_isTheInstalls() = runTest(dispatcher) {
        ledger.installBaseCurrency = "USD"

        val vm = viewModel()
        advanceUntilIdle()

        assertThat(vm.state.value.baseCurrency).isEqualTo("USD")
    }

    // ── Card rules, on the state ─────────────────────────────────────────────

    @Test
    fun eachCard_hidesWhenItHasNothingToSay() {
        val quiet = HomeFixtures.populated.copy(
            pendingCount = 0,
            summary = HomeFixtures.summary.copy(
                budgets = emptyList(),
                runway = emptyList(),
                captureCoverage = com.ledgerflow.core.domain.analytics.CaptureCoverage.Empty,
            ),
            recentIncome = emptyList(),
        )

        assertThat(quiet.showsReview).isFalse()
        assertThat(quiet.showsBudgets).isFalse()
        assertThat(quiet.showsRunway).isFalse()
        assertThat(quiet.showsCoverage).isFalse()
        assertThat(quiet.showsRecentIncome).isFalse()
        // Spending alone is still a vault with something in it.
        assertThat(quiet.showsSummary).isTrue()
        assertThat(quiet.showsEmptyState).isFalse()
    }

    @Test
    fun everyCard_showsWhenItHasSomethingToSay() {
        val full = HomeFixtures.populated

        assertThat(full.showsReview).isTrue()
        assertThat(full.showsBudgets).isTrue()
        assertThat(full.showsRunway).isTrue()
        assertThat(full.showsCoverage).isTrue()
        assertThat(full.showsRecentSpending).isTrue()
        assertThat(full.showsRecentIncome).isTrue()
    }

    // ── Wording ──────────────────────────────────────────────────────────────

    @Test
    fun theComparison_isInWords_andHandlesAnEmptyLastMonth() {
        assertThat(comparisonSentence(42_310_00L, 37_000_00L, "INR", "1–16 Sep"))
            .isEqualTo("₹5,310.00 more than 1–16 Sep")
        assertThat(comparisonSentence(37_000_00L, 42_310_00L, "INR", "1–16 Sep"))
            .isEqualTo("₹5,310.00 less than 1–16 Sep")
        assertThat(comparisonSentence(500_00L, 500_00L, "INR", "1–16 Sep")).isEqualTo("Same as 1–16 Sep")
        // Nothing last month is an amount more, not an infinite percentage.
        assertThat(comparisonSentence(500_00L, 0L, "INR", "1–16 Sep")).isEqualTo("₹500.00 more than 1–16 Sep")
    }

    /**
     * What TalkBack hears names the amount in words, not the ₹ glyph (§9.6).
     * The figure was spoken and its comparison was not; seen on LF Bench.
     */
    @Test
    fun theSpokenComparison_saysTheAmountInWords() {
        val spoken = comparisonSentence(37_000_00L, 42_310_00L, "INR", "1–16 Sep", spoken = true)

        assertThat(spoken).isEqualTo("5,310.00 rupees less than 1–16 Sep")
        assertThat(spoken).doesNotContain("₹")
    }

    @Test
    fun periodLabels() {
        val oct1 = LocalDate.of(2026, 10, 1).toEpochDay().toInt()
        val oct16 = LocalDate.of(2026, 10, 16).toEpochDay().toInt()
        assertThat(periodLabel(oct1, oct16)).isEqualTo("1–16 Oct")
        assertThat(periodLabel(oct1, oct1)).isEqualTo("1 Oct")
        assertThat(dayLabel(LocalDate.of(2026, 10, 31).toEpochDay().toInt())).isEqualTo("31 Oct")
        assertThat(runwayDetail(listOf("Rent"))).isEqualTo("1 recurring charge: Rent")
        assertThat(runwayDetail(listOf("Gym", "Rent"))).isEqualTo("2 recurring charges: Gym, Rent")
    }

    // ── Fixtures ─────────────────────────────────────────────────────────────

    private class HealthyStore : ListenerHealthStore {
        override val record: Flow<ListenerHealthRecord> = MutableStateFlow(ListenerHealthRecord())
        override suspend fun current(): ListenerHealthRecord = ListenerHealthRecord()
        override suspend fun recordConnected(atMillis: Long) = Unit
        override suspend fun recordDisconnected(atMillis: Long) = Unit
        override suspend fun recordGrantObserved(atMillis: Long) = Unit
    }

    private class Source : TransactionIngestSource {
        override val sourceType: IngestSourceType = IngestSourceType.NOTIFICATION
        override suspend fun status(): IngestSourceStatus = IngestSourceStatus.READY
    }

    private fun viewModel(summary: GetHomeSummaryUseCase = GetHomeSummaryUseCase(analytics)): DashboardViewModel {
        val clock = Clock { now }
        val gaps = FakeCaptureGapStore()
        val crashes = FakeCrashReportRepository()
        return DashboardViewModel(
            GetNotificationCaptureHealthUseCase(
                getIngestSourceStatus = GetIngestSourceStatusUseCase(setOf(Source())),
                healthStore = HealthyStore(),
                clock = clock,
                processUptime = ProcessUptime { 0L },
            ),
            ObserveLastBackupUseCase(FakeBackupRepository(), clock),
            ObserveCaptureGapUseCase(gaps),
            AcknowledgeCaptureGapUseCase(gaps),
            ObserveCrashReportsUseCase(crashes),
            SaveCrashReportsUseCase(crashes),
            DismissCrashReportsUseCase(crashes),
            HomeReads(
                ObservePendingCountUseCase(FakePendingRepository()),
                summary,
                ledger,
                clock,
            ),
        )
    }

    private companion object {
        const val DEBIT_BASE = 1_000_000_00L
        const val CREDIT_BASE = 2_000_000_00L

        /** Noon in the JVM's zone, so `LocalDates.of` lands on that date wherever this runs. */
        fun millisOn(year: Int, month: Int, day: Int): Long =
            LocalDate.of(year, month, day).atTime(12, 0).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
    }
}
