package com.ledgerflow.feature.dashboard

import com.ledgerflow.core.domain.analytics.BookMonth
import com.ledgerflow.core.domain.analytics.Budget
import com.ledgerflow.core.domain.analytics.BudgetProgress
import com.ledgerflow.core.domain.analytics.CaptureCoverage
import com.ledgerflow.core.domain.analytics.CaptureShare
import com.ledgerflow.core.domain.analytics.HomeSummary
import com.ledgerflow.core.domain.analytics.MonthToDate
import com.ledgerflow.core.domain.analytics.RecurringMerchant
import com.ledgerflow.core.domain.ingest.NotificationCaptureHealth
import com.ledgerflow.core.model.BudgetPeriod
import com.ledgerflow.core.model.LedgerListItem
import com.ledgerflow.core.model.LedgerType
import com.ledgerflow.core.model.Money
import java.time.LocalDate
import java.time.ZoneOffset

/**
 * Home v1's test data (V2-PLAN BUG-C): one month, both books, generic names.
 *
 * 16 October 2026, in UTC, the screenshot harness's zone. Made-up merchants
 * only, as for the store screenshots: no real brand appears in a golden.
 */
internal object HomeFixtures {

    // First, so they are set before the lists below read them (object
    // properties initialise in declaration order).
    private val GREEN = 0xFF2E7D32.toInt()
    private val BLUE = 0xFF1565C0.toInt()
    private val ORANGE = 0xFFEF6C00.toInt()
    private val PURPLE = 0xFF6A1B9A.toInt()
    private val TEAL = 0xFF00796B.toInt()

    val TODAY: Int = LocalDate.of(2026, 10, 16).toEpochDay().toInt()

    fun at(day: Int, hour: Int, minute: Int = 0): Long =
        LocalDate.ofEpochDay(day.toLong()).atTime(hour, minute).toInstant(ZoneOffset.UTC).toEpochMilli()

    // One argument per column a list row shows, named at every call site.
    @Suppress("LongParameterList")
    fun entry(
        id: String,
        ledger: LedgerType,
        minor: Long,
        day: Int,
        hour: Int,
        merchant: String?,
        category: String?,
        color: Int? = null,
        note: String? = null,
    ) = LedgerListItem(
        id = id,
        ledger = ledger,
        amount = Money(minor),
        currency = "INR",
        occurredAt = at(day, hour),
        localDate = day,
        categoryName = category,
        categoryColorArgb = color,
        merchantName = merchant,
        note = note,
    )

    val spending: List<LedgerListItem> = listOf(
        entry("d1", LedgerType.DEBIT, 1_240_00L, TODAY, 18, "Corner Grocer", "Groceries", GREEN),
        entry("d2", LedgerType.DEBIT, 60_00L, TODAY, 9, "City Metro", "Transport", BLUE),
        entry("d3", LedgerType.DEBIT, 450_00L, TODAY - 1, 20, "Green Leaf Café", "Eating out", ORANGE, "Dinner"),
        entry("d4", LedgerType.DEBIT, 18_000_00L, TODAY - 2, 10, "Lakeview Apartments", "Rent", PURPLE),
        entry("d5", LedgerType.DEBIT, 299_00L, TODAY - 3, 13, null, null),
    )

    val income: List<LedgerListItem> = listOf(
        entry("c1", LedgerType.CREDIT, 85_000_00L, TODAY - 15, 9, null, "Salary", TEAL),
        entry("c2", LedgerType.CREDIT, 6_500_00L, TODAY - 10, 16, "Studio North", "Freelance", TEAL),
    )

    private fun budget(id: String, name: String, spent: Long, of: Long, projected: Long, color: Int) =
        BudgetProgress(
            budget = Budget(
                id = id,
                categoryId = "category-$id",
                subcategoryId = null,
                period = BudgetPeriod.MONTHLY,
                amount = Money(of),
                startDate = 0,
                rolloverEnabled = false,
                alertThresholds = listOf(80, 100),
            ),
            categoryName = name,
            categoryColorArgb = color,
            spent = Money(spent),
            periodStart = MonthToDate.window(TODAY).from,
            periodEnd = MonthToDate.lastDay(TODAY),
            daysElapsed = 16,
            projectedSpend = Money(projected),
        )

    private fun recurring(id: String, name: String, minor: Long, next: Int) = RecurringMerchant(
        merchantId = id,
        name = name,
        occurrences = 4,
        intervalDays = 30,
        regularity = 0.05,
        typicalAmount = Money(minor),
        lastSeen = next - 30,
        nextExpected = next,
    )

    val summary: HomeSummary = HomeSummary(
        today = TODAY,
        window = MonthToDate.window(TODAY),
        previousWindow = MonthToDate.previous(TODAY),
        spending = BookMonth(LedgerType.DEBIT, total = Money(42_310_00L), previousTotal = Money(37_000_00L)),
        income = BookMonth(LedgerType.CREDIT, total = Money(91_500_00L), previousTotal = Money(91_500_00L)),
        spentToday = Money(1_300_00L),
        budgets = listOf(
            budget("eat", "Eating out", 4_750_00L, 5_000_00L, 9_200_00L, ORANGE),
            budget("groc", "Groceries", 8_200_00L, 10_000_00L, 15_800_00L, GREEN),
            budget("move", "Transport", 1_100_00L, 3_000_00L, 2_100_00L, BLUE),
        ),
        runway = listOf(
            recurring("stream", "Streaming plan", 499_00L, TODAY + 5),
            recurring("gym", "Riverside Gym", 1_500_00L, TODAY + 12),
        ),
        runwayThrough = MonthToDate.lastDay(TODAY),
        captureCoverage = CaptureCoverage(
            automatic = CaptureShare(Money(30_463_20L), count = 41),
            manual = CaptureShare(Money(11_846_80L), count = 16),
            imported = CaptureShare(Money(0L), count = 0),
        ),
    )

    /** Everything Home v1 can show at once: the tallest it gets. */
    val populated: DashboardUiState = DashboardUiState(
        captureHealth = NotificationCaptureHealth.CONNECTED,
        pendingCount = 3,
        summary = summary,
        recentSpending = spending,
        recentIncome = income,
    )

}
