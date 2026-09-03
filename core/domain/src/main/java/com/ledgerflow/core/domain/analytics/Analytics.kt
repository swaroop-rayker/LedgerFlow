package com.ledgerflow.core.domain.analytics

import com.ledgerflow.core.model.LedgerType
import com.ledgerflow.core.model.Money

/**
 * The time windows `SPEC.md` §5.6 specifies.
 *
 * [days] is the span; [bucketDays] is how wide one column of the time chart is,
 * chosen so the chart never receives more columns than a phone has horizontal
 * pixels (§11). The two together are what a change of window actually changes:
 * the query's range *and* its `GROUP BY` divisor, which is why a zoom here is a
 * re-query rather than a transform over held data (ADR-0005).
 *
 * **A "month" bucket is 30 days, not a calendar month.** Calendar-accurate
 * grouping needs timezone-aware date maths in SQL, which §6.1's `local_date`
 * column exists specifically to avoid. The distinction is invisible in a chart
 * whose axis states its range, and if a calendar month is ever needed for a
 * *figure* rather than a bucket, that is a different query.
 */
public enum class AnalyticsRange(
    public val days: Int,
    public val bucketDays: Int,
    public val label: String,
) {
    DAY(days = 1, bucketDays = 1, label = "Day"),
    WEEK(days = 7, bucketDays = 1, label = "Week"),
    MONTH(days = 30, bucketDays = 1, label = "Month"),
    THREE_MONTHS(days = 90, bucketDays = 3, label = "3M"),
    SIX_MONTHS(days = 180, bucketDays = 7, label = "6M"),
    YEAR(days = 365, bucketDays = 14, label = "1Y"),
    FIVE_YEARS(days = 1_825, bucketDays = 30, label = "5Y"),
    ;

    /** Columns this range produces. 5Y is 61 — well under any phone's width. */
    public val bucketCount: Int get() = (days + bucketDays - 1) / bucketDays
}

/**
 * A resolved date range, in days since epoch.
 *
 * Inclusive at both ends, matching the `BETWEEN` in the queries.
 */
public data class AnalyticsWindow(
    val range: AnalyticsRange,
    val from: Int,
    val to: Int,
) {
    /**
     * The window immediately before this one, for §5.6's comparison toggle.
     *
     * Same length, ending the day before this one starts — so "vs previous
     * period" compares like with like rather than against a calendar boundary
     * that may be a different number of days.
     */
    public fun previous(): AnalyticsWindow = AnalyticsWindow(
        range = range,
        from = from - range.days,
        to = from - 1,
    )

    public companion object {
        /** The window ending today. */
        public fun endingOn(today: Int, range: AnalyticsRange): AnalyticsWindow =
            AnalyticsWindow(range = range, from = today - range.days + 1, to = today)
    }
}

/**
 * One column of the time chart. [bucket] is an ordinal from the window start.
 *
 * [byCategory] is what makes A1 a *stacked* bar rather than a total bar, and
 * §5.6 asks for the stacking specifically: it is what shows *what* changed when
 * a month moves, rather than only that it did. Ordered largest first, so the
 * chart stacks consistently across buckets instead of reshuffling per column.
 */
public data class TimeBucket(
    val bucket: Int,
    val startDate: Int,
    val endDate: Int,
    val amount: Money,
    val byCategory: List<DimensionTotal>,
)

/**
 * One day of A6's calendar heatmap.
 *
 * Only days with spending are returned; the grid fills the gaps, because a
 * month has a fixed shape and an absent day is a day with nothing on it rather
 * than a day that is missing.
 */
public data class DayTotal(
    val localDate: Int,
    val amount: Money,
    val transactionCount: Int,
)

/**
 * A total for one dimension value, with its share and its movement.
 *
 * [id] is `''` for the "does not apply" sentinel (§6.1.1) — uncategorised
 * spend, no merchant. [name] is what the user sees, resolved from the taxonomy,
 * and is never blank: an unfiled bucket says so rather than rendering as a gap.
 *
 * [previousAmount] is null when comparison is off, and zero is a different
 * thing entirely — it means the category existed in the window before and had
 * nothing in it, which is a real and different statement from "we did not look".
 */
public data class DimensionTotal(
    val id: String,
    val name: String,
    val colorArgb: Int?,
    val amount: Money,
    val transactionCount: Int,
    val previousAmount: Money?,
)

/**
 * Everything A1–A5 need for one window, from one pass.
 *
 * A single snapshot rather than five independently observable queries: the five
 * views share a window and a filter set, and issuing them separately would let
 * the screen render a donut from one window beside a bar chart from another
 * while both were still settling.
 *
 * **`transactionCount` is not the sum of the categories' counts** and must not
 * be computed as one. `txn_count` fans out across `category_id` (§5.6), so this
 * comes from a `COUNT(DISTINCT id)` over the base tables — the one analytics
 * read that does not come from `daily_rollup`.
 */
public data class AnalyticsSnapshot(
    val ledger: LedgerType,
    val window: AnalyticsWindow,
    val total: Money,
    val previousTotal: Money?,
    val transactionCount: Int,
    val timeBuckets: List<TimeBucket>,
    val categories: List<DimensionTotal>,
    val subcategories: Map<String, List<DimensionTotal>>,
    val merchants: List<DimensionTotal>,
    val paymentMethods: List<DimensionTotal>,
    /** A6 — one entry per day that had spending. */
    val days: List<DayTotal> = emptyList(),
    /** A7 — every live budget, against the period containing today. */
    val budgets: List<BudgetProgress> = emptyList(),
    /** A8 — merchants whose payments cluster into a regular interval. */
    val recurring: List<RecurringMerchant> = emptyList(),
    /**
     * A10 — detected charges falling between today and the window's end.
     *
     * A subset of [recurring], not a separate detection: the runway is a
     * *reading* of A8's output, and computing it independently would let the
     * two disagree about what is recurring.
     */
    val runway: List<RecurringMerchant> = emptyList(),
) {
    public val isEmpty: Boolean get() = total.minor == 0L && transactionCount == 0

    /** A10's headline: what the detected charges add up to. Law 3 — `Long`. */
    public val runwayTotal: Money get() = Money(runway.sumOf { it.typicalAmount.minor })
}

/**
 * The analytics read port (`SPEC.md` §5.6).
 *
 * Reads only. Nothing here writes `daily_rollup` — that is ADR-0006's
 * in-transaction recompute and the nightly pass, neither of which is reachable
 * from this interface.
 */
public interface AnalyticsRepository {

    /**
     * @param comparePrevious when true, each total carries the same figure for
     *   the preceding window of equal length. It costs a second set of
     *   aggregates, so it is a parameter rather than always-on.
     */
    public suspend fun snapshot(
        ledger: LedgerType,
        window: AnalyticsWindow,
        comparePrevious: Boolean,
    ): AnalyticsSnapshot
}
