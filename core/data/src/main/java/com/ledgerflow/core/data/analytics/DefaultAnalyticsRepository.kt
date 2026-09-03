package com.ledgerflow.core.data.analytics

import com.ledgerflow.core.common.di.IoDispatcher
import com.ledgerflow.core.data.vault.VaultSession
import com.ledgerflow.core.database.LedgerFlowDatabase
import com.ledgerflow.core.database.dao.DimensionTotalRow
import com.ledgerflow.core.domain.analytics.AnalyticsFilters
import com.ledgerflow.core.domain.analytics.AnalyticsRepository
import com.ledgerflow.core.domain.analytics.AnalyticsSnapshot
import com.ledgerflow.core.domain.analytics.AnalyticsWindow
import com.ledgerflow.core.domain.analytics.Budget
import com.ledgerflow.core.domain.analytics.BudgetPeriods
import com.ledgerflow.core.domain.analytics.BudgetProgress
import com.ledgerflow.core.domain.analytics.CaptureCoverage
import com.ledgerflow.core.domain.analytics.CaptureShare
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
        filters: AnalyticsFilters,
    ): AnalyticsSnapshot = withContext(io) {
        // **`openForBackgroundWork()`, not `requireDatabase()`.** The budget
        // alert evaluation reaches this from a Worker with no Activity alive,
        // where `requireDatabase()` throws -- and the throw lands in a
        // `runCatching` and returns a clean success that read nothing, which is
        // BUG13 exactly (CLAUDE.md §7). An unopenable vault yields an empty
        // snapshot, which is honest: there is nothing to report, rather than a
        // lie that there is nothing to spend.
        val database = session.openForBackgroundWork()
            ?: return@withContext emptySnapshot(ledger, window)
        val names = NameBook.read(database)
        val dao = database.dailyRollupDao()

        val recurring = detectRecurring(dao, ledger, names, window.to)
        val previous = if (comparePrevious) window.previous() else null
        val previousCategories = previous?.let { earlier ->
            dimensionTotals(dao, ledger, CATEGORY, earlier, filters).associate { row ->
                row.dimensionId to row.sumMinor
            }
        }.orEmpty()

        AnalyticsSnapshot(
            ledger = ledger,
            window = window,
            total = windowTotal(dao, ledger, window, filters),
            previousTotal = previous?.let { windowTotal(dao, ledger, it, filters) },
            // Not the sum of the categories' counts: `txn_count` fans out across
            // `category_id`, so summing it double-counts a split bill (§5.6).
            transactionCount = distinctEntries(dao, ledger, window, filters),
            timeBuckets = buildTimeBuckets(dao, ledger, window, names, filters),
            categories = dimensionTotals(dao, ledger, CATEGORY, window, filters).map { row ->
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
            subcategories = buildSubcategories(dao, ledger, window, names, filters),
            merchants = dimensionTotals(dao, ledger, MERCHANT, window, filters).map { row ->
                row.toTotal(name = names.merchant(row.dimensionId), colorArgb = null)
            },
            paymentMethods = dimensionTotals(dao, ledger, METHOD, window, filters).map { row ->
                row.toTotal(
                    name = names.paymentMethod(row.dimensionId),
                    colorArgb = names.paymentMethodColor(row.dimensionId),
                )
            },
            days = dailyTotals(dao, ledger, window, filters).map { row ->
                DayTotal(row.localDate, row.sumMinor, row.txnCount)
            },
            budgets = buildBudgets(database, ledger, names, window.to),
            recurring = recurring,
            runway = RecurringDetection.runway(
                detected = recurring,
                today = window.to,
                through = window.to + window.range.days,
            ),
            captureCoverage = buildCaptureCoverage(dao, ledger, window, filters),
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
            val rolledOver = rolloverFor(budget, period, rollups)
            BudgetProgress(
                budget = budget,
                categoryName = names.category(budget.categoryId),
                categoryColorArgb = names.categoryColor(budget.categoryId),
                spent = Money(spent),
                periodStart = period.first,
                periodEnd = period.last,
                daysElapsed = elapsed,
                projectedSpend = BudgetPeriods.project(Money(spent), elapsed, length),
                rolledOver = rolledOver,
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
        filters: AnalyticsFilters,
    ): Map<String, List<DimensionTotal>> = dao
        .subcategoryTotalsFiltered(
            ledger = ledger,
            from = window.from,
            to = window.to,
            filterCategories = filters.categoryIds.flag(),
            categoryIds = filters.categoryIds.orPlaceholder(),
            filterSubcategories = filters.subcategoryIds.flag(),
            subcategoryIds = filters.subcategoryIds.orPlaceholder(),
            filterMerchants = filters.merchantIds.flag(),
            merchantIds = filters.merchantIds.orPlaceholder(),
            filterMethods = filters.paymentMethodIds.flag(),
            paymentMethodIds = filters.paymentMethodIds.orPlaceholder(),
        )
        // **By the parent**, which is how `AnalyticsScreen` looks it up: the
        // expanded row hands back a *category* id and asks for its children.
        // Grouping by `dimensionId` keyed the map by subcategory, so every
        // lookup missed and the drill-down silently expanded to nothing.
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
     * C1 — capture coverage over the same window and filters as everything else.
     *
     * **It honours the filters, including the source filter**, and that is the
     * consistent answer rather than the clever one. Filtering to "Manual" does
     * make this section read 100% typed by hand, which is useless but true; the
     * sheet promises "Narrow every figure on this screen", and one section
     * quietly exempting itself would make that copy a lie. The useful cases —
     * "how much of my Food spending is captured?" — are the same mechanism.
     */
    private suspend fun buildCaptureCoverage(
        dao: com.ledgerflow.core.database.dao.DailyRollupDao,
        ledger: LedgerType,
        window: AnalyticsWindow,
        filters: AnalyticsFilters,
    ): CaptureCoverage = CaptureCoverage.from(
        dao.captureCoverage(
            ledger = ledger,
            from = window.from,
            to = window.to,
            filterCategories = filters.categoryIds.flag(),
            categoryIds = filters.categoryIds.orPlaceholder(),
            filterSubcategories = filters.subcategoryIds.flag(),
            subcategoryIds = filters.subcategoryIds.orPlaceholder(),
            filterMerchants = filters.merchantIds.flag(),
            merchantIds = filters.merchantIds.orPlaceholder(),
            filterMethods = filters.paymentMethodIds.flag(),
            paymentMethodIds = filters.paymentMethodIds.orPlaceholder(),
            minAmount = filters.minAmount?.minor,
            maxAmount = filters.maxAmount?.minor,
            filterSources = filters.sources.flag(),
            sources = filters.sources.map { it.name }.orPlaceholder(),
            query = filters.query,
            like = filters.query.toLikePattern(),
        ).associate { row -> row.source to CaptureShare(row.sumMinor, row.txnCount) },
    )

    private fun emptySnapshot(ledger: LedgerType, window: AnalyticsWindow) = AnalyticsSnapshot(
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
        filters: AnalyticsFilters,
    ): List<TimeBucket> {
        // Stacking is a rollup-only read: the base-table path has no
        // per-category series, and building one would be a third copy of the
        // grain expression. Under an entry-level filter the bars are drawn as a
        // single total segment -- honest, and the ranked list beside them still
        // carries the per-category detail.
        val segments = if (filters.needsBaseTables) {
            emptyMap()
        } else {
            dao.timeSeriesByCategoryFiltered(
                ledger = ledger,
                from = window.from,
                to = window.to,
                bucketDays = window.bucketDays,
                filterCategories = filters.categoryIds.flag(),
                categoryIds = filters.categoryIds.orPlaceholder(),
                filterSubcategories = filters.subcategoryIds.flag(),
                subcategoryIds = filters.subcategoryIds.orPlaceholder(),
                filterMerchants = filters.merchantIds.flag(),
                merchantIds = filters.merchantIds.orPlaceholder(),
                filterMethods = filters.paymentMethodIds.flag(),
                paymentMethodIds = filters.paymentMethodIds.orPlaceholder(),
            ).groupBy { it.bucket }
        }

        val totals = timeSeries(dao, ledger, window, filters).associateBy { it.bucket }

        // **Every bucket in the window, including the empty ones.** SQL returns
        // only days that had spending, and handing those straight to the chart
        // makes a month with one purchase render as a single bar spanning the
        // whole plot -- which reads as "this is the month" rather than "this is
        // one day of it". Observed on device with two real entries: one column,
        // full width. The gaps are the information.
        return (0 until window.bucketCount).map { bucket ->
            val start = window.from + bucket * window.bucketDays
            TimeBucket(
                bucket = bucket,
                startDate = start,
                endDate = (start + window.bucketDays - 1).coerceAtMost(window.to),
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

    /**
     * What the previous period left unspent (§5.7's rollover).
     *
     * Zero when the budget does not roll over, and zero for its **first**
     * period — there is no earlier period to carry from, and treating a missing
     * period as fully unspent would hand the user a double budget on day one.
     *
     * One period back only. Compounding every past remainder would make this
     * month's figure something nobody chose and nobody can predict.
     */
    private suspend fun rolloverFor(
        budget: Budget,
        period: IntRange,
        rollups: com.ledgerflow.core.database.dao.DailyRollupDao,
    ): Money {
        if (!budget.rolloverEnabled) return Money(0L)
        val length = BudgetPeriods.lengthInDays(budget.period)
        val previousStart = period.first - length
        if (previousStart < budget.startDate) return Money(0L)

        val subcategoryId = budget.subcategoryId
        val previousSpend = if (subcategoryId == null) {
            rollups.categorySpend(budget.categoryId, previousStart, period.first - 1)
        } else {
            rollups.subcategorySpend(
                categoryId = budget.categoryId,
                subcategoryId = subcategoryId,
                from = previousStart,
                to = period.first - 1,
            )
        }
        return BudgetPeriods.rollover(budget.amount, Money(previousSpend))
    }

    /**
     * The window's total, summed from the category breakdown.
     *
     * Not a separate `SUM` query: under a filter the total must be the sum of
     * exactly what the screen shows, and a second statement with its own copy
     * of the filter clause is how a chart and its own caption end up
     * disagreeing. Categories are the finest dimension every path returns, so
     * summing them is the total by construction.
     */
    private suspend fun windowTotal(
        dao: com.ledgerflow.core.database.dao.DailyRollupDao,
        ledger: LedgerType,
        window: AnalyticsWindow,
        filters: AnalyticsFilters,
    ): Money = Money(
        dimensionTotals(dao, ledger, CATEGORY, window, filters).sumOf { it.sumMinor.minor },
    )

    // ── The one decision this class makes: rollup, or base tables ─────────
    //
    // `daily_rollup` answers whenever it can, because that is what §11's 5Y
    // budget depends on. It cannot answer a filter naming an entry's amount,
    // source or note -- those columns are not in it and must never be added
    // (`CLAUDE.md` §5) -- so those route to the base tables and pay for it.

    private suspend fun timeSeries(
        dao: com.ledgerflow.core.database.dao.DailyRollupDao,
        ledger: LedgerType,
        window: AnalyticsWindow,
        filters: AnalyticsFilters,
    ) = if (filters.needsBaseTables) {
        dao.timeSeriesFromEntries(
            ledger = ledger,
            from = window.from,
            to = window.to,
            bucketDays = window.bucketDays,
            filterCategories = filters.categoryIds.flag(),
            categoryIds = filters.categoryIds.orPlaceholder(),
            filterSubcategories = filters.subcategoryIds.flag(),
            subcategoryIds = filters.subcategoryIds.orPlaceholder(),
            filterMerchants = filters.merchantIds.flag(),
            merchantIds = filters.merchantIds.orPlaceholder(),
            filterMethods = filters.paymentMethodIds.flag(),
            paymentMethodIds = filters.paymentMethodIds.orPlaceholder(),
            minAmount = filters.minAmount?.minor,
            maxAmount = filters.maxAmount?.minor,
            filterSources = filters.sources.flag(),
            sources = filters.sources.map { it.name }.orPlaceholder(),
            query = filters.query,
            like = filters.query.toLikePattern(),
        )
    } else {
        dao.timeSeriesFiltered(
            ledger = ledger,
            from = window.from,
            to = window.to,
            bucketDays = window.bucketDays,
            filterCategories = filters.categoryIds.flag(),
            categoryIds = filters.categoryIds.orPlaceholder(),
            filterSubcategories = filters.subcategoryIds.flag(),
            subcategoryIds = filters.subcategoryIds.orPlaceholder(),
            filterMerchants = filters.merchantIds.flag(),
            merchantIds = filters.merchantIds.orPlaceholder(),
            filterMethods = filters.paymentMethodIds.flag(),
            paymentMethodIds = filters.paymentMethodIds.orPlaceholder(),
        )
    }

    private suspend fun dimensionTotals(
        dao: com.ledgerflow.core.database.dao.DailyRollupDao,
        ledger: LedgerType,
        groupBy: String,
        window: AnalyticsWindow,
        filters: AnalyticsFilters,
    ) = if (filters.needsBaseTables) {
        dao.dimensionTotalsFromEntries(
            ledger = ledger,
            groupBy = groupBy,
            from = window.from,
            to = window.to,
            filterCategories = filters.categoryIds.flag(),
            categoryIds = filters.categoryIds.orPlaceholder(),
            filterSubcategories = filters.subcategoryIds.flag(),
            subcategoryIds = filters.subcategoryIds.orPlaceholder(),
            filterMerchants = filters.merchantIds.flag(),
            merchantIds = filters.merchantIds.orPlaceholder(),
            filterMethods = filters.paymentMethodIds.flag(),
            paymentMethodIds = filters.paymentMethodIds.orPlaceholder(),
            minAmount = filters.minAmount?.minor,
            maxAmount = filters.maxAmount?.minor,
            filterSources = filters.sources.flag(),
            sources = filters.sources.map { it.name }.orPlaceholder(),
            query = filters.query,
            like = filters.query.toLikePattern(),
        )
    } else {
        dao.dimensionTotalsFiltered(
            ledger = ledger,
            groupBy = groupBy,
            from = window.from,
            to = window.to,
            filterCategories = filters.categoryIds.flag(),
            categoryIds = filters.categoryIds.orPlaceholder(),
            filterSubcategories = filters.subcategoryIds.flag(),
            subcategoryIds = filters.subcategoryIds.orPlaceholder(),
            filterMerchants = filters.merchantIds.flag(),
            merchantIds = filters.merchantIds.orPlaceholder(),
            filterMethods = filters.paymentMethodIds.flag(),
            paymentMethodIds = filters.paymentMethodIds.orPlaceholder(),
        )
    }

    private suspend fun dailyTotals(
        dao: com.ledgerflow.core.database.dao.DailyRollupDao,
        ledger: LedgerType,
        window: AnalyticsWindow,
        filters: AnalyticsFilters,
    ) = if (filters.needsBaseTables) {
        // A6's grid has no base-table form: a heatmap under a text search is a
        // sparse month that says less than the list beside it. Empty is the
        // honest answer, and the section hides itself.
        emptyList()
    } else {
        dao.dailyTotalsFiltered(
            ledger = ledger,
            from = window.from,
            to = window.to,
            filterCategories = filters.categoryIds.flag(),
            categoryIds = filters.categoryIds.orPlaceholder(),
            filterSubcategories = filters.subcategoryIds.flag(),
            subcategoryIds = filters.subcategoryIds.orPlaceholder(),
            filterMerchants = filters.merchantIds.flag(),
            merchantIds = filters.merchantIds.orPlaceholder(),
            filterMethods = filters.paymentMethodIds.flag(),
            paymentMethodIds = filters.paymentMethodIds.orPlaceholder(),
        )
    }

    private suspend fun distinctEntries(
        dao: com.ledgerflow.core.database.dao.DailyRollupDao,
        ledger: LedgerType,
        window: AnalyticsWindow,
        filters: AnalyticsFilters,
    ): Int = if (filters.isEmpty) {
        dao.distinctEntryTotal(ledger, window.from, window.to)
    } else {
        dao.distinctEntriesFromEntries(
            ledger = ledger,
            from = window.from,
            to = window.to,
            filterCategories = filters.categoryIds.flag(),
            categoryIds = filters.categoryIds.orPlaceholder(),
            filterSubcategories = filters.subcategoryIds.flag(),
            subcategoryIds = filters.subcategoryIds.orPlaceholder(),
            filterMerchants = filters.merchantIds.flag(),
            merchantIds = filters.merchantIds.orPlaceholder(),
            filterMethods = filters.paymentMethodIds.flag(),
            paymentMethodIds = filters.paymentMethodIds.orPlaceholder(),
            minAmount = filters.minAmount?.minor,
            maxAmount = filters.maxAmount?.minor,
            filterSources = filters.sources.flag(),
            sources = filters.sources.map { it.name }.orPlaceholder(),
            query = filters.query,
            like = filters.query.toLikePattern(),
        )
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

/**
 * 1 when the set narrows something, 0 when it does not.
 *
 * The flag is what lets a filtered read stay a static `@Query`: the clause is
 * `(:flag = 0 OR column IN (:ids))`, so a 0 short-circuits the whole predicate
 * to true and one statement serves both the filtered and unfiltered case —
 * while staying a literal `LedgerIsolationTest` can read.
 *
 * **The polarity is worth stating because getting it backwards is silent.**
 * Inverted, an *unfiltered* read evaluates `IN (placeholder)`, matches nothing,
 * and every figure on the screen comes back zero — which reads as "you have no
 * spending", not as a bug. `AnalyticsFilterTest` caught exactly that.
 */
private fun Collection<*>.flag(): Int = if (isEmpty()) 0 else 1

/**
 * Room refuses to bind an empty list, so an unused `IN` gets one impossible id.
 *
 * It is never evaluated — the flag above short-circuits first — but the
 * statement still has to be *bindable*, and an empty list is not.
 */
private fun Collection<String>.orPlaceholder(): List<String> =
    if (isEmpty()) listOf(EMPTY_FILTER_PLACEHOLDER) else toList()

/**
 * `%term%`, with SQL's wildcards escaped out of the user's text.
 *
 * A search for "50%" must mean the characters "50%", not "anything containing
 * 50". Without this, one `%` in a note turns a search into a match-all and the
 * user sees every entry back with no indication why.
 */
private fun String.toLikePattern(): String {
    val escaped = replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")
    return "%" + escaped + "%"
}

private const val EMPTY_FILTER_PLACEHOLDER = "\u0000-no-filter"

private const val CATEGORY = "category"
private const val MERCHANT = "merchant"
private const val METHOD = "method"
