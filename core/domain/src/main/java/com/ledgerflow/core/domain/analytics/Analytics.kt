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

    /**
     * §5.6's custom range.
     *
     * [days] and [bucketDays] are placeholders: a custom window is built from
     * two dates the user picked, and [AnalyticsWindow.custom] recomputes both
     * from the span so the bucket count stays inside a phone's width whatever
     * range is chosen. Reading these constants for a custom window is a bug,
     * which is why they are the same as MONTH rather than something that would
     * look plausible.
     */
    CUSTOM(days = 30, bucketDays = 1, label = "Custom"),
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
        // The *actual* span, not the enum's nominal days -- a custom window has
        // no nominal length, and comparing a 12-day custom range against the
        // preceding 30 days would be a comparison of two different things.
        from = from - spanDays,
        to = from - 1,
    )

    /** Days the window spans, inclusive. Correct for CUSTOM too. */
    public val spanDays: Int get() = to - from + 1

    /**
     * Bucket width for this window, honouring a custom span.
     *
     * §11 forbids handing a chart more columns than it has horizontal pixels,
     * and a user may pick any two dates — including ten years apart. The width
     * is derived so the count stays at or below [MAX_BUCKETS] whatever they
     * choose.
     */
    public val bucketDays: Int
        get() = if (range != AnalyticsRange.CUSTOM) {
            range.bucketDays
        } else {
            ((spanDays + MAX_BUCKETS - 1) / MAX_BUCKETS).coerceAtLeast(1)
        }

    /** Columns this window produces. */
    public val bucketCount: Int get() = (spanDays + bucketDays - 1) / bucketDays

    /**
     * The window shifted by a fraction of its own span (ADR-0005's pan).
     *
     * The result is always a CUSTOM window: once a user has panned, "Month" no
     * longer describes what they are looking at, and leaving the chip selected
     * would make the screen claim a range it is not showing.
     */
    public fun pannedBy(fractionOfSpan: Float): AnalyticsWindow {
        val shift = (spanDays * fractionOfSpan).toInt()
        if (shift == 0) return this
        return custom(from + shift, to + shift)
    }

    /**
     * The window scaled about its centre (ADR-0005's zoom).
     *
     * Clamped at both ends. One day at the bottom, because a window of zero
     * days has no `BETWEEN` that can match and would render as an empty chart
     * rather than as a limit reached. [MAX_SPAN_DAYS] at the top, because each
     * pinch multiplies the span and eight of them would take a month out past a
     * century — §11's 5Y budget is the largest span this app claims to serve,
     * and a zoom that quietly exceeds it is a performance target with no
     * enforcement. Also CUSTOM afterwards, for the reason [pannedBy] gives.
     */
    public fun zoomedBy(scale: Float): AnalyticsWindow {
        val newSpan = (spanDays * scale).toInt().coerceIn(1, MAX_SPAN_DAYS)
        if (newSpan == spanDays) return this
        val centre = from + spanDays / 2
        val half = newSpan / 2
        return custom(centre - half, centre - half + newSpan - 1)
    }

    public companion object {
        /**
         * Comfortably fewer than a phone's horizontal pixels, and few enough
         * that a bar is still wide enough to see. 5Y's fixed 30-day buckets
         * give 61, so this is the same order of magnitude by design.
         */
        public const val MAX_BUCKETS: Int = 64

        /** The widest window a zoom may reach — 5Y, the largest preset (§11). */
        public const val MAX_SPAN_DAYS: Int = 1_825

        /** The window ending today. */
        public fun endingOn(today: Int, range: AnalyticsRange): AnalyticsWindow =
            AnalyticsWindow(range = range, from = today - range.days + 1, to = today)

        /**
         * §5.6's custom range, from two dates the user picked.
         *
         * Ordered defensively: a picker that lets the end precede the start is
         * one tap from a window with a negative span, and a negative span makes
         * every downstream `BETWEEN` return nothing — an empty chart that looks
         * like missing data rather than a mistake.
         */
        public fun custom(from: Int, to: Int): AnalyticsWindow = AnalyticsWindow(
            range = AnalyticsRange.CUSTOM,
            from = minOf(from, to),
            to = maxOf(from, to),
        )
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
    /**
     * C1 - how much of this window arrived automatically.
     *
     * Read from `ledger_entry.source`, which is why it costs no schema. Part of
     * the snapshot rather than its own query for the reason this class exists:
     * one window, one read, so the screen cannot show a coverage figure from a
     * range the charts above it are no longer displaying.
     */
    val captureCoverage: CaptureCoverage = CaptureCoverage.Empty,
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
        filters: AnalyticsFilters = AnalyticsFilters.None,
    ): AnalyticsSnapshot
}
