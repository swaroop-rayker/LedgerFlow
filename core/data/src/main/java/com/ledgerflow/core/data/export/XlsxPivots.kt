package com.ledgerflow.core.data.export

import com.ledgerflow.core.database.entity.DailyRollupEntity
import com.ledgerflow.core.model.LedgerType
import java.time.LocalDate
import java.time.YearMonth

/** A summary sheet: a label column, then minor-unit cells under [header]. */
internal data class PivotSheet(
    val name: String,
    /** The label column's title first, then one title per value column. */
    val header: List<String>,
    val rows: List<PivotRow>,
)

/** One labelled row. A null cell is left blank; [isTotal] rows are styled as totals. */
internal data class PivotRow(
    val label: String,
    val cells: List<Long?>,
    val isTotal: Boolean = false,
)

/**
 * The XLSX export's summary sheets (ADR-0004), from `daily_rollup`.
 *
 * Read from the rollup rather than recomputed from entries so the figures are
 * exactly the ones Analytics shows, ADR-0018's line-item attribution included.
 * Sums are `Long` minor units throughout (Law 3); the writer turns them into
 * exact decimals.
 *
 * **Law 2 is enforced here, not trusted:** a per-book pivot refuses buckets
 * from the other book, and the one sheet that shows both books keeps them in
 * separate columns with no net, balance or difference anywhere.
 */
internal object XlsxPivots {

    const val MONTHLY_TOTALS_SHEET: String = "Monthly totals"
    const val SPENDING_SHEET: String = "Spending by month"
    const val INCOME_SHEET: String = "Income by month"

    private const val TOTAL = "Total"
    private const val UNFILED = "Unfiled"
    private const val UNKNOWN_CATEGORY = "Unknown category"

    /**
     * Month, Spent, Received — two figures per month, side by side.
     *
     * There is deliberately no fourth column. "Received minus spent" is the
     * netted figure Law 2 forbids everywhere in the app, and a spreadsheet the
     * app writes is part of the app.
     */
    fun monthlyTotals(debit: List<DailyRollupEntity>, credit: List<DailyRollupEntity>): PivotSheet {
        val spent = totalsByMonth(debit, LedgerType.DEBIT)
        val received = totalsByMonth(credit, LedgerType.CREDIT)
        val months = (spent.keys + received.keys).sorted()

        val rows = months.map { month ->
            PivotRow(month.toString(), listOf(spent[month] ?: 0L, received[month] ?: 0L))
        }
        val total = PivotRow(
            label = TOTAL,
            // Each column's total sums within one book.
            cells = listOf(spent.values.sum(), received.values.sum()),
            isTotal = true,
        )
        return PivotSheet(
            name = MONTHLY_TOTALS_SHEET,
            header = listOf("Month", "Spent", "Received"),
            rows = if (rows.isEmpty()) rows else rows + total,
        )
    }

    /**
     * Categories down, months across, one book only; a total per category and
     * a total per month, each within that book.
     *
     * Rows are ordered by the category's total, largest first — the order the
     * Analytics breakdown uses — and a category with nothing in a month shows a
     * blank cell rather than a zero, so the sheet reads like a pivot table.
     */
    fun byCategoryAndMonth(
        name: String,
        ledger: LedgerType,
        buckets: List<DailyRollupEntity>,
        categoryNames: Map<String, String>,
    ): PivotSheet {
        requireOneBook(buckets, ledger)
        val months = buckets.map { monthOf(it.localDate) }.distinct().sorted()
        val byCategory = buckets.groupBy { it.categoryId }
            .mapValues { (_, rows) ->
                rows.groupBy { monthOf(it.localDate) }.mapValues { (_, inMonth) -> inMonth.sumOf { it.sumMinor.minor } }
            }

        val rows = byCategory.entries
            .map { (categoryId, perMonth) ->
                val cells = months.map { perMonth[it] } + perMonth.values.sum()
                PivotRow(label = labelOf(categoryId, categoryNames), cells = cells)
            }
            .sortedByDescending { it.cells.last() }

        val monthTotals = months.map { month -> byCategory.values.sumOf { it[month] ?: 0L } }
        val total = PivotRow(TOTAL, monthTotals + monthTotals.sum(), isTotal = true)

        return PivotSheet(
            name = name,
            header = listOf("Category") + months.map { it.toString() } + TOTAL,
            rows = if (rows.isEmpty()) rows else rows + total,
        )
    }

    private fun totalsByMonth(buckets: List<DailyRollupEntity>, ledger: LedgerType): Map<YearMonth, Long> {
        requireOneBook(buckets, ledger)
        return buckets.groupBy { monthOf(it.localDate) }.mapValues { (_, rows) -> rows.sumOf { it.sumMinor.minor } }
    }

    private fun requireOneBook(buckets: List<DailyRollupEntity>, ledger: LedgerType) {
        val stray = buckets.firstOrNull { it.ledger != ledger }
        require(stray == null) { "A $ledger pivot was handed a ${stray?.ledger} bucket (Law 2)" }
    }

    private fun labelOf(categoryId: String, names: Map<String, String>): String = when {
        categoryId.isEmpty() -> UNFILED
        else -> names[categoryId] ?: UNKNOWN_CATEGORY
    }

    private fun monthOf(localDate: Int): YearMonth = YearMonth.from(LocalDate.ofEpochDay(localDate.toLong()))
}
