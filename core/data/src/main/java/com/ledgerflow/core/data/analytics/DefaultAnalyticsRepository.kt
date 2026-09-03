package com.ledgerflow.core.data.analytics

import com.ledgerflow.core.common.di.IoDispatcher
import com.ledgerflow.core.data.vault.VaultSession
import com.ledgerflow.core.database.LedgerFlowDatabase
import com.ledgerflow.core.database.dao.DimensionTotalRow
import com.ledgerflow.core.domain.analytics.AnalyticsRepository
import com.ledgerflow.core.domain.analytics.AnalyticsSnapshot
import com.ledgerflow.core.domain.analytics.AnalyticsWindow
import com.ledgerflow.core.domain.analytics.BudgetPeriods
import com.ledgerflow.core.domain.analytics.BudgetProgress
import com.ledgerflow.core.domain.analytics.DayTotal
import com.ledgerflow.core.domain.analytics.DimensionTotal
import com.ledgerflow.core.domain.analytics.Occurrence
import com.ledgerflow.core.domain.analytics.RecurringDetection
import com.ledgerflow.core.domain.analytics.RecurringMerchant
import com.ledgerflow.core.domain.analytics.TimeBucket
import com.ledgerflow.core.model.LedgerType
import com.ledgerflow.core.model.Money
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext

/**
 * A1–A5's reads (`SPEC.md` §5.6).
 *
 * **Aggregates in SQL, names in Kotlin.** The figures come back already grouped
 * — one row per bucket, per category, per merchant — because a 5Y window spans
 * 1,825 days and returning a row per day per dimension would move tens of
 * thousands of rows to draw a few hundred pixels of chart. The taxonomy is then
 * read once and joined in memory, which is cheap: categories, merchants and
 * payment methods together are a few hundred rows on any real install, and
 * joining in SQL would mean a `LEFT JOIN` against three tables inside every one
 * of five aggregates.
 *
 * **One snapshot, not five observable queries.** The five views share a window,
 * so issuing them independently would let the screen show a donut from one
 * window beside a bar chart from another while both were still settling.
 */
@Singleton
public class DefaultAnalyticsRepository @Inject constructor(
    private val session: VaultSession,
    @param:IoDispatcher private val io: CoroutineDispatcher,
) : AnalyticsRepository {

    override suspend fun snapshot(
        ledger: LedgerType,
        window: AnalyticsWindow,
        comparePrevious: Boolean,
    ): AnalyticsSnapshot = withContext(io) {
        val database = session.requireDatabase()
        val names = NameBook.read(database)
        val dao = database.dailyRollupDao()

        val recurring = detectRecurring(dao, ledger, names, window.to)
        val previous = if (comparePrevious) window.previous() else null
        val previousCategories = previous?.let {
            dao.categoryTotals(ledger, it.from, it.to).associate { row ->
                row.dimensionId to row.sumMinor
            }
        }.orEmpty()

        AnalyticsSnapshot(
            ledger = ledger,
            window = window,
            total = Money(dao.windowTotal(ledger, window.from, window.to)),
            previousTotal = previous?.let { Money(dao.windowTotal(ledger, it.from, it.to)) },
            // Not the sum of the categories' counts: `txn_count` fans out across
            // `category_id`, so summing it double-counts a split bill (§5.6).
            transactionCount = dao.distinctEntryTotal(ledger, window.from, window.to),
            timeBuckets = buildTimeBuckets(dao, ledger, window, names),
            categories = dao.categoryTotals(ledger, window.from, window.to).map { row ->
                row.toTotal(
                    name = names.category(row.dimensionId),
                    colorArgb = names.categoryColor(row.dimensionId),
                    previousAmount = if (comparePrevious) {
                        previousCategories[row.dimensionId] ?: Money(0L)
                    } else {
                        null
                    },
                )
            },
            subcategories = buildSubcategories(dao, ledger, window, names),
            merchants = dao.merchantTotals(ledger, window.from, window.to).map { row ->
                row.toTotal(name = names.merchant(row.dimensionId), colorArgb = null)
            },
            paymentMethods = dao.paymentMethodTotals(ledger, window.from, window.to).map { row ->
                row.toTotal(
                    name = names.paymentMethod(row.dimensionId),
                    colorArgb = names.paymentMethodColor(row.dimensionId),
                )
            },
            days = dao.dailyTotals(ledger, window.from, window.to).map { row ->
                DayTotal(row.localDate, row.sumMinor, row.txnCount)
            },
            budgets = buildBudgets(database, ledger, names, window.to),
            recurring = recurring,
            runway = RecurringDetection.runway(
                detected = recurring,
                today = window.to,
                through = window.to + window.range.days,
            ),
        )
    }

    /**
     * A8's detection, over a **longer history than the selected window**.
     *
     * A monthly subscription needs three months of dates to be visible, so
     * detecting inside a one-month view would find nothing and the feature would
     * appear broken on exactly the range people look at most. The lookback is
     * fixed and generous; the window still decides what A1–A5 show.
     */
    private suspend fun detectRecurring(
        dao: com.ledgerflow.core.database.dao.DailyRollupDao,
        ledger: LedgerType,
        names: NameBook,
        today: Int,
    ): List<RecurringMerchant> = dao
        .merchantOccurrences(ledger, today - RECURRING_LOOKBACK_DAYS, today)
        .groupBy { it.merchantId }
        .mapNotNull { (merchantId, rows) ->
            RecurringDetection.detect(
                merchantId = merchantId,
                name = names.merchant(merchantId),
                occurrences = rows.map { Occurrence(it.localDate, it.amountMinor) },
            )
        }
        .sortedByDescending { it.typicalAmount.minor }

    /**
     * A7: every live budget against the period containing [today].
     *
     * **Not the selected analytics window.** A budget has its own period
     * (§5.7) and showing "spent 40% of your monthly grocery budget" against a
     * 5Y range would be meaningless — the figure the user needs is progress
     * through *this* budget's period, whatever range the rest of the screen is
     * showing.
     */
    private suspend fun buildBudgets(
        database: LedgerFlowDatabase,
        ledger: LedgerType,
        names: NameBook,
        today: Int,
    ): List<BudgetProgress> {
        // §5.7: budgets are debit-only, so a credit screen has none. The reads
        // below bind 'DEBIT' as a literal regardless; this avoids issuing them.
        if (ledger != LedgerType.DEBIT) return emptyList()

        val rollups = database.dailyRollupDao()
        return database.budgetDao().live().map { row ->
            val budget = row.toDomainBudget()
            val period = BudgetPeriods.currentPeriod(budget, today)
            val subcategoryId = budget.subcategoryId
            val spent = if (subcategoryId == null) {
                rollups.categorySpend(budget.categoryId, period.first, period.last)
            } else {
                rollups.subcategorySpend(
                    categoryId = budget.categoryId,
                    subcategoryId = subcategoryId,
                    from = period.first,
                    to = period.last,
                )
            }
            val length = BudgetPeriods.lengthInDays(budget.period)
            val elapsed = (today - period.first + 1).coerceIn(0, length)
            BudgetProgress(
                budget = budget,
                categoryName = names.category(budget.categoryId),
                categoryColorArgb = names.categoryColor(budget.categoryId),
                spent = Money(spent),
                periodStart = period.first,
                periodEnd = period.last,
                daysElapsed = elapsed,
                projectedSpend = BudgetPeriods.project(Money(spent), elapsed, length),
            )
        }
    }

    /**
     * A3's drill-down, grouped under its parent.
     *
     * No previous-period figure: the comparison §5.6 asks for is against the
     * *category*, and a subcategory delta beside its parent's would be two
     * deltas competing for the same row — which is the crowding
     * `CLAUDE.md`'s compactness brief warns about, for a figure nobody asked
     * for at that depth.
     */
    private suspend fun buildSubcategories(
        dao: com.ledgerflow.core.database.dao.DailyRollupDao,
        ledger: LedgerType,
        window: AnalyticsWindow,
        names: NameBook,
    ): Map<String, List<DimensionTotal>> = dao
        .subcategoryTotals(ledger, window.from, window.to)
        .groupBy { it.categoryId }
        .mapValues { (_, rows) ->
            rows.map { row ->
                DimensionTotal(
                    id = row.dimensionId,
                    name = names.category(row.dimensionId),
                    colorArgb = names.categoryColor(row.dimensionId),
                    amount = row.sumMinor,
                    transactionCount = row.txnCount,
                    previousAmount = null,
                )
            }
        }

    /**
     * A1's columns, stacked by category.
     *
     * Two queries rather than one: the totals come from the bucket aggregate
     * and the segments from the bucket-by-category aggregate. Summing the
     * segments to get the total would work today and would be wrong the moment
     * a filter excluded a category from one and not the other — the total is a
     * figure, and a figure derived by adding up what happens to be drawn is how
     * a chart and its own caption end up disagreeing.
     */
    private suspend fun buildTimeBuckets(
        dao: com.ledgerflow.core.database.dao.DailyRollupDao,
        ledger: LedgerType,
        window: AnalyticsWindow,
        names: NameBook,
    ): List<TimeBucket> {
        val segments = dao.timeSeriesByCategory(
            ledger = ledger,
            from = window.from,
            to = window.to,
            bucketDays = window.range.bucketDays,
        ).groupBy { it.bucket }

        val totals = dao.timeSeries(
            ledger = ledger,
            from = window.from,
            to = window.to,
            bucketDays = window.range.bucketDays,
        ).associateBy { it.bucket }

        // **Every bucket in the window, including the empty ones.** SQL returns
        // only days that had spending, and handing those straight to the chart
        // makes a month with one purchase render as a single bar spanning the
        // whole plot -- which reads as "this is the month" rather than "this is
        // one day of it". Observed on device with two real entries: one column,
        // full width. The gaps are the information.
        return (0 until window.range.bucketCount).map { bucket ->
            val start = window.from + bucket * window.range.bucketDays
            TimeBucket(
                bucket = bucket,
                startDate = start,
                endDate = (start + window.range.bucketDays - 1).coerceAtMost(window.to),
                amount = totals[bucket]?.sumMinor ?: Money(0L),
                byCategory = segments[bucket].orEmpty().map { segment ->
                    DimensionTotal(
                        id = segment.dimensionId,
                        name = names.category(segment.dimensionId),
                        colorArgb = names.categoryColor(segment.dimensionId),
                        amount = segment.sumMinor,
                        transactionCount = 0,
                        previousAmount = null,
                    )
                },
            )
        }
    }

    private fun DimensionTotalRow.toTotal(
        name: String,
        colorArgb: Int?,
        previousAmount: Money? = null,
    ) = DimensionTotal(
        id = dimensionId,
        name = name,
        colorArgb = colorArgb,
        amount = sumMinor,
        transactionCount = txnCount,
        previousAmount = previousAmount,
    )
}

/**
 * The taxonomy, read once per snapshot and looked up in memory.
 *
 * **The `''` sentinel gets a name, not a blank.** §6.1.1 uses `''` for "this
 * dimension does not apply", and an unfiled bucket is a real answer the user
 * should be able to read and tap — rendering it as an empty label would make
 * the largest slice on some donuts look like a rendering bug.
 *
 * A deleted category still resolves, because a window in the past legitimately
 * contains spending filed to a category the user has since hidden. Returning
 * "Unknown" for those would turn a month of history into anonymous bars the
 * first time someone tidied their taxonomy.
 */
private class NameBook(
    private val categories: Map<String, Pair<String, Int?>>,
    private val merchants: Map<String, String>,
    private val paymentMethods: Map<String, Pair<String, Int?>>,
) {
    fun category(id: String): String =
        if (id.isEmpty()) UNCATEGORISED else categories[id]?.first ?: REMOVED
    fun categoryColor(id: String): Int? = categories[id]?.second
    fun merchant(id: String): String =
        if (id.isEmpty()) NO_MERCHANT else merchants[id] ?: REMOVED
    fun paymentMethod(id: String): String =
        if (id.isEmpty()) NO_METHOD else paymentMethods[id]?.first ?: REMOVED
    fun paymentMethodColor(id: String): Int? = paymentMethods[id]?.second

    companion object {
        const val UNCATEGORISED = "Uncategorised"
        const val NO_MERCHANT = "No merchant"
        const val NO_METHOD = "No method"

        /**
         * A row whose dimension no longer exists at all — hard-deleted taxonomy
         * (ADR-0016). Rare, and honest: the spend happened and the label did not
         * survive, which is different from spend that was never filed.
         */
        const val REMOVED = "Removed"

        suspend fun read(database: LedgerFlowDatabase) = NameBook(
            categories = database.categoryDao().all()
                .associate { it.id to (it.name to it.colorArgb) },
            merchants = database.merchantDao().all()
                .associate { it.id to it.canonicalName },
            paymentMethods = database.paymentMethodDao().all()
                .associate { it.id to (it.label to it.colorArgb) },
        )
    }
}

/**
 * Two years of history for A8.
 *
 * Long enough for a quarterly charge to reach three occurrences, short enough
 * that the scan stays an indexed range read rather than the whole ledger.
 */
private const val RECURRING_LOOKBACK_DAYS = 730

