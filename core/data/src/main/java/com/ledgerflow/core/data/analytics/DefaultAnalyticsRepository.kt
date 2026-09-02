package com.ledgerflow.core.data.analytics

import com.ledgerflow.core.common.di.IoDispatcher
import com.ledgerflow.core.data.vault.VaultSession
import com.ledgerflow.core.database.LedgerFlowDatabase
import com.ledgerflow.core.database.dao.DimensionTotalRow
import com.ledgerflow.core.domain.analytics.AnalyticsRepository
import com.ledgerflow.core.domain.analytics.AnalyticsSnapshot
import com.ledgerflow.core.domain.analytics.AnalyticsWindow
import com.ledgerflow.core.domain.analytics.DimensionTotal
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
            subcategories = dao.subcategoryTotals(ledger, window.from, window.to)
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
                },
            merchants = dao.merchantTotals(ledger, window.from, window.to).map { row ->
                row.toTotal(name = names.merchant(row.dimensionId), colorArgb = null)
            },
            paymentMethods = dao.paymentMethodTotals(ledger, window.from, window.to).map { row ->
                row.toTotal(
                    name = names.paymentMethod(row.dimensionId),
                    colorArgb = names.paymentMethodColor(row.dimensionId),
                )
            },
        )
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

        return dao.timeSeries(
            ledger = ledger,
            from = window.from,
            to = window.to,
            bucketDays = window.range.bucketDays,
        ).map { row ->
            val start = window.from + row.bucket * window.range.bucketDays
            TimeBucket(
                bucket = row.bucket,
                startDate = start,
                endDate = (start + window.range.bucketDays - 1).coerceAtMost(window.to),
                amount = row.sumMinor,
                byCategory = segments[row.bucket].orEmpty().map { segment ->
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
