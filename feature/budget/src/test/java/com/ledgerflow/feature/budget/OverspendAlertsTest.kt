package com.ledgerflow.feature.budget

import com.google.common.truth.Truth.assertThat
import com.ledgerflow.core.designsystem.format.DisplayConventions
import com.ledgerflow.core.domain.analytics.Budget
import com.ledgerflow.core.domain.analytics.BudgetPeriods
import com.ledgerflow.core.domain.analytics.BudgetProgress
import com.ledgerflow.core.domain.analytics.BudgetRepository
import com.ledgerflow.core.domain.analytics.BudgetResult
import com.ledgerflow.core.domain.analytics.BudgetSettings
import com.ledgerflow.core.domain.analytics.NewBudget
import com.ledgerflow.core.domain.analytics.OverspendAnnouncements
import com.ledgerflow.core.domain.usecase.BudgetAlert
import com.ledgerflow.core.domain.usecase.EvaluateBudgetAlertsUseCase
import com.ledgerflow.core.model.BudgetPeriod
import com.ledgerflow.core.model.DateOrder
import com.ledgerflow.core.model.LedgerType
import com.ledgerflow.core.model.Money
import com.ledgerflow.core.model.RegionConventions
import com.ledgerflow.core.testing.analytics.FakeAnalyticsRepository
import com.ledgerflow.core.testing.analytics.emptySnapshot
import com.ledgerflow.feature.budget.notify.BudgetNotifications
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import java.time.LocalDate
import kotlinx.coroutines.runBlocking
import org.junit.Test

/**
 * Overspending alerts end to end, short of Android (V2-PLAN §3.4): the use case
 * announces once per period and records before it returns, a threshold
 * crossing takes the budget's one notification instead, and the notification
 * says where the pace ends, by when, against the limit.
 */
class OverspendAlertsTest {

    private val analytics = FakeAnalyticsRepository()
    private val budgets = RecordingBudgets()
    private val announcements = MemoryAnnouncements()
    private val evaluate = EvaluateBudgetAlertsUseCase(analytics, budgets, announcements)

    @Test
    fun onCourse_isAnnouncedOnce_andRecordedFirst() = runBlocking {
        show(progress(spent = 4_000_00))

        val first = evaluate(TODAY)
        assertThat(first).containsExactly(BudgetAlert.OnCourseToOverrun(progress(spent = 4_000_00)))
        assertThat(announcements.recorded).containsExactly("b1" to PERIOD_START)

        assertThat(evaluate(TODAY)).isEmpty()
    }

    @Test
    fun aThresholdCrossing_takesTheNotification_andTheWarningWaits() = runBlocking {
        // 85% by day 25 of 30: crosses 80% and projects to ₹10,200.
        show(progress(spent = 8_500_00, day = 25))

        val alerts = evaluate(TODAY)
        assertThat(alerts).containsExactly(BudgetAlert.ThresholdCrossed(progress(spent = 8_500_00, day = 25), 80))
        assertThat(budgets.alerts).containsExactly(Triple("b1", 80, PERIOD_START))
        assertThat(announcements.recorded).isEmpty()
    }

    @Test
    fun theNotification_saysWhereThePaceEnds_andWhen() {
        val host = DisplayConventions.current
        DisplayConventions.current = RegionConventions(dateOrder = DateOrder.DAY_MONTH_YEAR)
        try {
            val (title, text) = BudgetNotifications.wordsFor(
                BudgetAlert.OnCourseToOverrun(progress(spent = 4_000_00)),
                "INR",
            )
            assertThat(title).isEqualTo("Groceries on course to go over")
            // ₹4,000 by day 10 of 30 is ₹12,000 by the period's last day.
            assertThat(text).isEqualTo("At this pace, ₹12,000.00 by 30 Oct. Limit ₹10,000.00.")
        } finally {
            DisplayConventions.current = host
        }
    }

    @Test
    fun aThresholdNotification_isUnchanged() {
        val (title, text) = BudgetNotifications.wordsFor(
            BudgetAlert.ThresholdCrossed(progress(spent = 10_000_00, day = 20), 100),
            "INR",
        )
        assertThat(title).isEqualTo("Groceries budget used up")
        assertThat(text).isEqualTo("₹10,000.00 of ₹10,000.00")
    }

    private fun show(vararg progress: BudgetProgress) {
        analytics.snapshots[LedgerType.DEBIT] = { window ->
            emptySnapshot(LedgerType.DEBIT, window).copy(budgets = progress.toList())
        }
    }

    private fun progress(spent: Long, day: Int = 10): BudgetProgress {
        val length = BudgetPeriods.lengthInDays(BudgetPeriod.MONTHLY)
        return BudgetProgress(
            budget = Budget(
                id = "b1",
                categoryId = "groceries",
                subcategoryId = null,
                period = BudgetPeriod.MONTHLY,
                amount = Money(10_000_00L),
                startDate = PERIOD_START,
                rolloverEnabled = false,
                alertThresholds = listOf(80, 100),
            ),
            categoryName = "Groceries",
            categoryColorArgb = null,
            spent = Money(spent),
            periodStart = PERIOD_START,
            periodEnd = PERIOD_START + length - 1,
            daysElapsed = day,
            projectedSpend = BudgetPeriods.project(Money(spent), day, length),
        )
    }

    private class RecordingBudgets : BudgetRepository {
        val alerts = mutableListOf<Triple<String, Int, Int>>()
        override fun observeAll(): Flow<List<Budget>> = emptyFlow()
        override suspend fun create(request: NewBudget): BudgetResult<Budget> = error("not used")
        override suspend fun update(id: String, settings: BudgetSettings): BudgetResult<Unit> = error("not used")
        override suspend fun delete(id: String): BudgetResult<Unit> = error("not used")
        override suspend fun recordAlert(id: String, threshold: Int, periodStart: Int) {
            alerts += Triple(id, threshold, periodStart)
        }
    }

    private class MemoryAnnouncements : OverspendAnnouncements {
        val recorded = mutableListOf<Pair<String, Int>>()
        override suspend fun wasAnnounced(budgetId: String, periodStart: Int) = (budgetId to periodStart) in recorded
        override suspend fun record(budgetId: String, periodStart: Int) {
            recorded += budgetId to periodStart
        }
    }

    private companion object {
        /** 1 Oct 2026, so the 30-day period ends on 30 Oct. */
        val PERIOD_START: Int = LocalDate.of(2026, 10, 1).toEpochDay().toInt()
        val TODAY: Int = PERIOD_START + 9
    }
}
