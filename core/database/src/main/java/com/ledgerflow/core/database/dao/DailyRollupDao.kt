package com.ledgerflow.core.database.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Transaction
import com.ledgerflow.core.database.entity.DailyRollupEntity
import com.ledgerflow.core.model.LedgerType

/**
 * The rollup maintenance path (ADR-0006, SPEC.md §5.6).
 *
 * **[recompute] is the only way rows get into `daily_rollup`,** and it does not
 * adjust anything — it deletes the buckets in a date range and re-aggregates
 * them from `ledger_entry` ⋈ `line_item`. That is the whole of ADR-0006: a
 * signed delta is correct only if applied exactly once, and a delta applied
 * twice is arithmetically invisible and permanent, whereas a recompute is
 * correct however many times it runs and repairs whatever the previous attempt
 * got wrong.
 *
 * It follows that reconciliation is not a second algorithm. The nightly pass is
 * this same function with the range widened to everything, so it cannot
 * disagree with the incremental path about *method* — only about staleness,
 * which has exactly one correct resolution.
 *
 * **Every statement here binds `:ledger`** (Law 2). `daily_rollup` has no
 * protective views the way `ledger_entry` has `debit_entries` /
 * `credit_entries`, so `LedgerIsolationTest` is the only thing standing between
 * a plausible `SUM(sum_minor)` and a figure that nets a month of income against
 * a month of spending.
 *
 * **The SQL is written as concatenated regular strings, never a raw `"""`
 * string, and that is not a style preference.** `LedgerIsolationTest` finds SQL
 * by scanning for double-quoted literals; a `"""` literal reads to that scanner
 * as an empty string followed by loose text, so a raw-string query is invisible
 * to the Law 2 guard. `noDaoUsesARawStringLiteral` enforces this so the next
 * person to reach for a raw string finds out from a test rather than from a
 * wrong total.
 */
@Dao
public interface DailyRollupDao {

    /**
     * Rebuild every bucket in `[from, to]` for one book, atomically.
     *
     * Pass the single date a write touched for the incremental path, or the
     * full range for reconciliation. Both are the same operation.
     */
    @Transaction
    public suspend fun recompute(ledger: LedgerType, from: Int, to: Int) {
        deleteRange(ledger, from, to)
        insertRange(ledger, from, to)
    }

    /** Reconciliation: every date, one book. See [recompute]. */
    @Transaction
    public suspend fun recomputeAll(ledger: LedgerType) {
        recompute(ledger, Int.MIN_VALUE, Int.MAX_VALUE)
    }

    @Query(
        "DELETE FROM daily_rollup WHERE ledger = :ledger " +
            "AND local_date BETWEEN :from AND :to",
    )
    public suspend fun deleteRange(ledger: LedgerType, from: Int, to: Int)

    /**
     * **The single most important query in the analytics feature.**
     *
     * It implements ADR-0018's grain, and if it is wrong it is wrong identically
     * in the incremental path and in reconciliation — so no amount of
     * reconciling will notice. That is why it is tested against hand-computed
     * fixtures rather than against itself.
     *
     * The `LEFT JOIN` is what carries the mixed grain in one statement: an
     * itemised entry produces one row per line (`li` non-null), a plain entry
     * produces exactly one row with `li` null, and `COALESCE` picks the line's
     * filing where there is one and the entry's where there is not. An itemised
     * entry has no entry-level category at all (ADR-0018), so for those the
     * `COALESCE` falls through to the line every time; a line with no category
     * of its own lands on `''`, the "does not apply" sentinel (§6.1.1) — never
     * `NULL`, which SQLite would treat as distinct inside the composite key and
     * fan one logical bucket out into rows that can never merge.
     *
     * `COUNT(DISTINCT entry_id)` is `txn_count` as §5.6 defines it: **distinct
     * entries**, so a ₹1,000 bill split ₹600/₹400 writes `1` to both category
     * buckets, because one payment happened. An entry with two lines in the
     * *same* category also counts once, which is what `DISTINCT` is doing here
     * and the reason a plain `COUNT(*)` would be wrong.
     *
     * `deleted_at IS NULL` matches the two entry views, so a binned entry is
     * absent from every rollup — which is what makes purge a no-op for this
     * table and `restoreEntry` the door that silently changes a past total.
     *
     * It names `ledger_entry` rather than reading the views because the views
     * are two objects and this would have to become two near-identical
     * statements that could drift; `CLAUDE.md` §5 permits naming the base table
     * exactly when a `ledger` parameter is bound, which it is.
     */
    @Query(
        "INSERT INTO daily_rollup (local_date, ledger, category_id, subcategory_id, " +
            "merchant_id, payment_method_id, sum_minor, txn_count) " +
            "SELECT local_date, ledger, category_id, subcategory_id, merchant_id, " +
            "payment_method_id, SUM(amount), COUNT(DISTINCT entry_id) FROM (" +
            "SELECT e.local_date AS local_date, e.ledger AS ledger, " +
            "COALESCE(li.category_id, e.category_id, '') AS category_id, " +
            "COALESCE(li.subcategory_id, e.subcategory_id, '') AS subcategory_id, " +
            "COALESCE(e.merchant_id, '') AS merchant_id, " +
            "COALESCE(e.payment_method_id, '') AS payment_method_id, " +
            "COALESCE(li.total_minor, e.amount_minor) AS amount, e.id AS entry_id " +
            "FROM ledger_entry e LEFT JOIN line_item li ON li.entry_id = e.id " +
            "WHERE e.ledger = :ledger AND e.deleted_at IS NULL " +
            "AND e.local_date BETWEEN :from AND :to) " +
            "GROUP BY local_date, ledger, category_id, subcategory_id, merchant_id, " +
            "payment_method_id",
    )
    public suspend fun insertRange(ledger: LedgerType, from: Int, to: Int)

    // ── Analytics reads (SPEC.md §5.6). ────────────────────────────────────
    //
    // Every one binds `:ledger` (Law 2) and reads only `daily_rollup`, never
    // `ledger_entry` (CLAUDE.md §8). They are aggregates rather than row dumps
    // on purpose: a 5Y window covers 1,825 days and returning a row per day per
    // dimension would move tens of thousands of rows across the JNI boundary to
    // draw a chart with a few hundred pixels of width, which is the shape §11's
    // 300 ms budget is most easily lost to.

    /**
     * The time series for A1, pre-binned in SQL (§11).
     *
     * `(local_date - :from) / :bucketDays` is integer division, so buckets are
     * exact and need no calendar arithmetic. The caller chooses `bucketDays`
     * from the selected window so the chart never receives more columns than it
     * has horizontal pixels — a zoom is a re-query with a different divisor,
     * which is precisely the arrangement ADR-0005 says a chart library cannot
     * accommodate.
     */
    @Query(
        "SELECT ((local_date - :from) / :bucketDays) AS bucket, " +
            "SUM(sum_minor) AS sum_minor, SUM(txn_count) AS txn_count " +
            "FROM daily_rollup WHERE ledger = :ledger " +
            "AND local_date BETWEEN :from AND :to " +
            "GROUP BY bucket ORDER BY bucket",
    )
    public suspend fun timeSeries(
        ledger: LedgerType,
        from: Int,
        to: Int,
        bucketDays: Int,
    ): List<TimeBucketRow>

    /**
     * The time series **split by category**, for A1's stacked bars.
     *
     * §5.6 asks for "stacked bar (by category)", which a per-bucket total
     * cannot produce — the stacking is the point, since it is what shows *what*
     * changed when a month moves rather than only that it did. Same bucketing
     * as [timeSeries], one more `GROUP BY` term.
     *
     * `txn_count` is deliberately absent: it is additive over dates within one
     * category, but this projection exists to be drawn, and a count per bucket
     * per category is not a figure any of A1-A5 shows.
     */
    @Query(
        "SELECT ((local_date - :from) / :bucketDays) AS bucket, " +
            "category_id AS dimension_id, SUM(sum_minor) AS sum_minor " +
            "FROM daily_rollup WHERE ledger = :ledger " +
            "AND local_date BETWEEN :from AND :to " +
            "GROUP BY bucket, category_id ORDER BY bucket, sum_minor DESC",
    )
    public suspend fun timeSeriesByCategory(
        ledger: LedgerType,
        from: Int,
        to: Int,
        bucketDays: Int,
    ): List<BucketCategoryRow>

    /**
     * Category totals for A2.
     *
     * `SUM(txn_count)` is correct *within* a category because an entry has one
     * date; it would not be correct across categories, and no query here does
     * that. The all-categories transaction count is [distinctEntryTotal].
     */
    @Query(
        "SELECT category_id AS dimension_id, SUM(sum_minor) AS sum_minor, " +
            "SUM(txn_count) AS txn_count FROM daily_rollup WHERE ledger = :ledger " +
            "AND local_date BETWEEN :from AND :to " +
            "GROUP BY category_id ORDER BY sum_minor DESC",
    )
    public suspend fun categoryTotals(
        ledger: LedgerType,
        from: Int,
        to: Int,
    ): List<DimensionTotalRow>

    /** Subcategory totals under their parents, for A3's drill-down. */
    @Query(
        "SELECT category_id, subcategory_id AS dimension_id, " +
            "SUM(sum_minor) AS sum_minor, SUM(txn_count) AS txn_count " +
            "FROM daily_rollup WHERE ledger = :ledger " +
            "AND local_date BETWEEN :from AND :to AND subcategory_id != '' " +
            "GROUP BY category_id, subcategory_id ORDER BY sum_minor DESC",
    )
    public suspend fun subcategoryTotals(
        ledger: LedgerType,
        from: Int,
        to: Int,
    ): List<SubcategoryTotalRow>

    /** Merchant totals for A4's leaderboard. */
    @Query(
        "SELECT merchant_id AS dimension_id, SUM(sum_minor) AS sum_minor, " +
            "SUM(txn_count) AS txn_count FROM daily_rollup WHERE ledger = :ledger " +
            "AND local_date BETWEEN :from AND :to " +
            "GROUP BY merchant_id ORDER BY sum_minor DESC",
    )
    public suspend fun merchantTotals(
        ledger: LedgerType,
        from: Int,
        to: Int,
    ): List<DimensionTotalRow>

    /** Payment-method totals for A5. */
    @Query(
        "SELECT payment_method_id AS dimension_id, SUM(sum_minor) AS sum_minor, " +
            "SUM(txn_count) AS txn_count FROM daily_rollup WHERE ledger = :ledger " +
            "AND local_date BETWEEN :from AND :to " +
            "GROUP BY payment_method_id ORDER BY sum_minor DESC",
    )
    public suspend fun paymentMethodTotals(
        ledger: LedgerType,
        from: Int,
        to: Int,
    ): List<DimensionTotalRow>

    /** The window's total. One number, so it costs one row. */
    @Query(
        "SELECT COALESCE(SUM(sum_minor), 0) FROM daily_rollup WHERE ledger = :ledger " +
            "AND local_date BETWEEN :from AND :to",
    )
    public suspend fun windowTotal(ledger: LedgerType, from: Int, to: Int): Long

    /**
     * **The all-categories transaction count, and it may not come from the
     * rollup** (§5.6).
     *
     * `txn_count` fans out across `category_id`, so summing it over every
     * category double-counts any entry filed to more than one. This is the one
     * analytics read that names `ledger_entry`, and it is exactly the drill-down
     * shape `CLAUDE.md` §8 permits: a single `COUNT(DISTINCT)` over an indexed
     * date range for one book, not a chart.
     */
    @Query(
        "SELECT COUNT(DISTINCT id) FROM ledger_entry WHERE ledger = :ledger " +
            "AND deleted_at IS NULL AND local_date BETWEEN :from AND :to",
    )
    public suspend fun distinctEntryTotal(ledger: LedgerType, from: Int, to: Int): Int

    /**
     * Per-day totals for A6's calendar heatmap.
     *
     * Real dates rather than [timeSeries]'s bucket ordinals, because a heatmap
     * cell *is* a calendar day and has to know which one. A month is 31 rows.
     */
    @Query(
        "SELECT local_date, SUM(sum_minor) AS sum_minor, " +
            "SUM(txn_count) AS txn_count FROM daily_rollup WHERE ledger = :ledger " +
            "AND local_date BETWEEN :from AND :to " +
            "GROUP BY local_date ORDER BY local_date",
    )
    public suspend fun dailyTotals(ledger: LedgerType, from: Int, to: Int): List<DailyTotalRow>

    /**
     * **A8's occurrences, and the second read that may not use `daily_rollup`.**
     *
     * Interval clustering needs the sequence of individual payment dates per
     * merchant; a daily sum has discarded exactly that. `CLAUDE.md` §8 names
     * this as one of two standing exceptions, and it is the drill-down shape the
     * same section permits — one indexed range scan over one book, not a chart.
     *
     * Entries with no merchant are excluded: "no merchant" is a sentinel, not a
     * counterparty, and clustering the dates of everything unattributed would
     * manufacture a subscription out of unrelated spending.
     */
    @Query(
        "SELECT merchant_id, local_date, amount_minor FROM ledger_entry " +
            "WHERE ledger = :ledger AND deleted_at IS NULL " +
            "AND merchant_id IS NOT NULL AND local_date BETWEEN :from AND :to " +
            "ORDER BY merchant_id, local_date",
    )
    public suspend fun merchantOccurrences(
        ledger: LedgerType,
        from: Int,
        to: Int,
    ): List<MerchantOccurrenceRow>

    /**
     * A7: what one category has cost inside a budget's period.
     *
     * **`'DEBIT'` as a literal, not a parameter.** §5.7 scopes budgets to the
     * debit ledger and §6.1 gives `budget` no `ledger` column, so "debit only"
     * is entirely a property of this read. A `:ledger` parameter here would have
     * exactly one legal value and would invite someone to pass the other one;
     * the literal satisfies `LedgerIsolationTest` and states the rule in the
     * SQL where it is enforced.
     *
     * Line grain comes for free — `daily_rollup` is already fed at it
     * (ADR-0018), so a ₹400 kettle inside a grocery bill lands in the home
     * budget rather than the grocery one, which is what §5.6 promises.
     */
    @Query(
        "SELECT COALESCE(SUM(sum_minor), 0) FROM daily_rollup " +
            "WHERE ledger = 'DEBIT' AND category_id = :categoryId " +
            "AND local_date BETWEEN :from AND :to",
    )
    public suspend fun categorySpend(categoryId: String, from: Int, to: Int): Long

    /**
     * The same, narrowed to one subcategory (§5.7's optional scoping).
     *
     * A separate statement rather than a nullable parameter, because
     * `subcategory_id = NULL` matches nothing in SQL and would silently report
     * every subcategory-scoped budget as unspent.
     */
    @Query(
        "SELECT COALESCE(SUM(sum_minor), 0) FROM daily_rollup " +
            "WHERE ledger = 'DEBIT' AND category_id = :categoryId " +
            "AND subcategory_id = :subcategoryId AND local_date BETWEEN :from AND :to",
    )
    public suspend fun subcategorySpend(
        categoryId: String,
        subcategoryId: String,
        from: Int,
        to: Int,
    ): Long

    // ── Filtered analytics reads (SPEC.md §5.6's composable filters) ───────
    //
    // **Still `@Query`, not `@RawQuery`, and that is deliberate.** A raw query
    // is built at runtime, so `LedgerIsolationTest` — which scans SQL string
    // literals — could not see it, and every Law 2 rule would pass without
    // having looked. The dynamic part is expressed instead as
    // `(:filterX = 0 OR column IN (:xs))`: Room binds the list, SQLite skips
    // the `IN` when the flag is 0, and the statement stays a literal the guard
    // can read.
    //
    // Only *dimension* filters appear here. Amount, source and text name columns
    // `daily_rollup` does not carry and must never be given (`CLAUDE.md` §5);
    // those route to the base tables instead.

    @Query(
        "SELECT ((local_date - :from) / :bucketDays) AS bucket, " +
            "SUM(sum_minor) AS sum_minor, SUM(txn_count) AS txn_count " +
            "FROM daily_rollup WHERE ledger = :ledger " +
            "AND local_date BETWEEN :from AND :to " +
            "AND (:filterCategories = 0 OR category_id IN (:categoryIds)) " +
            "AND (:filterSubcategories = 0 OR subcategory_id IN (:subcategoryIds)) " +
            "AND (:filterMerchants = 0 OR merchant_id IN (:merchantIds)) " +
            "AND (:filterMethods = 0 OR payment_method_id IN (:paymentMethodIds)) " +
            "GROUP BY bucket ORDER BY bucket",
    )
    @Suppress("LongParameterList")
    public suspend fun timeSeriesFiltered(
        ledger: LedgerType,
        from: Int,
        to: Int,
        bucketDays: Int,
        filterCategories: Int,
        categoryIds: List<String>,
        filterSubcategories: Int,
        subcategoryIds: List<String>,
        filterMerchants: Int,
        merchantIds: List<String>,
        filterMethods: Int,
        paymentMethodIds: List<String>,
    ): List<TimeBucketRow>

    @Query(
        "SELECT ((local_date - :from) / :bucketDays) AS bucket, " +
            "category_id AS dimension_id, SUM(sum_minor) AS sum_minor " +
            "FROM daily_rollup WHERE ledger = :ledger " +
            "AND local_date BETWEEN :from AND :to " +
            "AND (:filterCategories = 0 OR category_id IN (:categoryIds)) " +
            "AND (:filterSubcategories = 0 OR subcategory_id IN (:subcategoryIds)) " +
            "AND (:filterMerchants = 0 OR merchant_id IN (:merchantIds)) " +
            "AND (:filterMethods = 0 OR payment_method_id IN (:paymentMethodIds)) " +
            "GROUP BY bucket, category_id ORDER BY bucket, sum_minor DESC",
    )
    @Suppress("LongParameterList")
    public suspend fun timeSeriesByCategoryFiltered(
        ledger: LedgerType,
        from: Int,
        to: Int,
        bucketDays: Int,
        filterCategories: Int,
        categoryIds: List<String>,
        filterSubcategories: Int,
        subcategoryIds: List<String>,
        filterMerchants: Int,
        merchantIds: List<String>,
        filterMethods: Int,
        paymentMethodIds: List<String>,
    ): List<BucketCategoryRow>

    /**
     * Totals for one dimension, filtered.
     *
     * `:groupBy` selects which column is the dimension, so one statement serves
     * the category, merchant and payment-method breakdowns rather than three
     * near-identical ones that could drift in their filter clause — which is the
     * clause most likely to be edited.
     */
    @Query(
        "SELECT CASE :groupBy " +
            "WHEN 'category' THEN category_id " +
            "WHEN 'merchant' THEN merchant_id " +
            "ELSE payment_method_id END AS dimension_id, " +
            "SUM(sum_minor) AS sum_minor, SUM(txn_count) AS txn_count " +
            "FROM daily_rollup WHERE ledger = :ledger " +
            "AND local_date BETWEEN :from AND :to " +
            "AND (:filterCategories = 0 OR category_id IN (:categoryIds)) " +
            "AND (:filterSubcategories = 0 OR subcategory_id IN (:subcategoryIds)) " +
            "AND (:filterMerchants = 0 OR merchant_id IN (:merchantIds)) " +
            "AND (:filterMethods = 0 OR payment_method_id IN (:paymentMethodIds)) " +
            "GROUP BY dimension_id ORDER BY sum_minor DESC",
    )
    @Suppress("LongParameterList")
    public suspend fun dimensionTotalsFiltered(
        ledger: LedgerType,
        groupBy: String,
        from: Int,
        to: Int,
        filterCategories: Int,
        categoryIds: List<String>,
        filterSubcategories: Int,
        subcategoryIds: List<String>,
        filterMerchants: Int,
        merchantIds: List<String>,
        filterMethods: Int,
        paymentMethodIds: List<String>,
    ): List<DimensionTotalRow>

    @Query(
        "SELECT category_id, subcategory_id AS dimension_id, " +
            "SUM(sum_minor) AS sum_minor, SUM(txn_count) AS txn_count " +
            "FROM daily_rollup WHERE ledger = :ledger " +
            "AND local_date BETWEEN :from AND :to AND subcategory_id != '' " +
            "AND (:filterCategories = 0 OR category_id IN (:categoryIds)) " +
            "AND (:filterSubcategories = 0 OR subcategory_id IN (:subcategoryIds)) " +
            "AND (:filterMerchants = 0 OR merchant_id IN (:merchantIds)) " +
            "AND (:filterMethods = 0 OR payment_method_id IN (:paymentMethodIds)) " +
            "GROUP BY category_id, subcategory_id ORDER BY sum_minor DESC",
    )
    /**
     * **Returns [SubcategoryTotalRow], not [DimensionTotalRow].**
     *
     * The `SELECT` always named `category_id`, but the row type had no field
     * for it, so Room dropped the column and the caller was left grouping by
     * the only id it could see — the subcategory's. That produced a map keyed
     * by subcategory against a screen that looks it up by *category*, so A3's
     * drill-down expanded to nothing, for every category, always. The type is
     * the fix: a row that carries both ids cannot be grouped by the wrong one.
     */
    @Suppress("LongParameterList")
    public suspend fun subcategoryTotalsFiltered(
        ledger: LedgerType,
        from: Int,
        to: Int,
        filterCategories: Int,
        categoryIds: List<String>,
        filterSubcategories: Int,
        subcategoryIds: List<String>,
        filterMerchants: Int,
        merchantIds: List<String>,
        filterMethods: Int,
        paymentMethodIds: List<String>,
    ): List<SubcategoryTotalRow>

    @Query(
        "SELECT local_date, SUM(sum_minor) AS sum_minor, " +
            "SUM(txn_count) AS txn_count FROM daily_rollup WHERE ledger = :ledger " +
            "AND local_date BETWEEN :from AND :to " +
            "AND (:filterCategories = 0 OR category_id IN (:categoryIds)) " +
            "AND (:filterSubcategories = 0 OR subcategory_id IN (:subcategoryIds)) " +
            "AND (:filterMerchants = 0 OR merchant_id IN (:merchantIds)) " +
            "AND (:filterMethods = 0 OR payment_method_id IN (:paymentMethodIds)) " +
            "GROUP BY local_date ORDER BY local_date",
    )
    @Suppress("LongParameterList")
    public suspend fun dailyTotalsFiltered(
        ledger: LedgerType,
        from: Int,
        to: Int,
        filterCategories: Int,
        categoryIds: List<String>,
        filterSubcategories: Int,
        subcategoryIds: List<String>,
        filterMerchants: Int,
        merchantIds: List<String>,
        filterMethods: Int,
        paymentMethodIds: List<String>,
    ): List<DailyTotalRow>

    // ── Base-table aggregates, for filters `daily_rollup` cannot answer ────
    //
    // Amount range, source and text search name entry-level columns the rollup
    // does not carry and must never be given. When one is active the aggregate
    // comes from `ledger_entry` joined to `line_item` instead.
    //
    // **This is the same grain expression `insertRange` uses** (ADR-0018's
    // `LEFT JOIN` plus `COALESCE`), and that duplication is the price of the
    // feature: the rollup exists precisely so the common case does not pay for
    // this scan. The two must agree, so `AnalyticsFilterTest` asserts an
    // unfiltered base-table read equals the rollup read over the same window —
    // a drift between them is the failure that would otherwise be invisible.
    //
    // `deleted_at IS NULL` matches the views, so binned entries stay out. Every
    // statement binds `:ledger` (Law 2).

    @Query(
        "SELECT ((local_date - :from) / :bucketDays) AS bucket, " +
            "SUM(amount) AS sum_minor, COUNT(DISTINCT entry_id) AS txn_count FROM (" +
            "SELECT e.local_date AS local_date, " +
            "COALESCE(li.total_minor, e.amount_minor) AS amount, e.id AS entry_id " +
            "FROM ledger_entry e LEFT JOIN line_item li ON li.entry_id = e.id " +
            "LEFT JOIN merchant m ON m.id = e.merchant_id " +
            "WHERE e.ledger = :ledger AND e.deleted_at IS NULL " +
            "AND e.local_date BETWEEN :from AND :to " +
            "AND (:filterCategories = 0 OR " +
            "COALESCE(li.category_id, e.category_id, '') IN (:categoryIds)) " +
            "AND (:filterMerchants = 0 OR COALESCE(e.merchant_id, '') IN (:merchantIds)) " +
            "AND (:filterMethods = 0 OR " +
            "COALESCE(e.payment_method_id, '') IN (:paymentMethodIds)) " +
            "AND (:minAmount IS NULL OR e.amount_minor >= :minAmount) " +
            "AND (:maxAmount IS NULL OR e.amount_minor <= :maxAmount) " +
            "AND (:filterSources = 0 OR e.source IN (:sources)) " +
            "AND (:query = '' OR e.note LIKE :like ESCAPE '\\' " +
            "OR m.canonical_name LIKE :like ESCAPE '\\' " +
            "OR li.name LIKE :like ESCAPE '\\')) " +
            "GROUP BY bucket ORDER BY bucket",
    )
    @Suppress("LongParameterList")
    public suspend fun timeSeriesFromEntries(
        ledger: LedgerType,
        from: Int,
        to: Int,
        bucketDays: Int,
        filterCategories: Int,
        categoryIds: List<String>,
        filterMerchants: Int,
        merchantIds: List<String>,
        filterMethods: Int,
        paymentMethodIds: List<String>,
        minAmount: Long?,
        maxAmount: Long?,
        filterSources: Int,
        sources: List<String>,
        query: String,
        like: String,
    ): List<TimeBucketRow>

    /**
     * Dimension totals from the base tables, filtered.
     *
     * `:groupBy` picks the dimension, as in [dimensionTotalsFiltered], and for
     * the same reason: one filter clause that cannot drift between three
     * breakdowns.
     */
    @Query(
        "SELECT dimension_id, SUM(amount) AS sum_minor, " +
            "COUNT(DISTINCT entry_id) AS txn_count FROM (" +
            "SELECT CASE :groupBy " +
            "WHEN 'category' THEN COALESCE(li.category_id, e.category_id, '') " +
            "WHEN 'merchant' THEN COALESCE(e.merchant_id, '') " +
            "ELSE COALESCE(e.payment_method_id, '') END AS dimension_id, " +
            "COALESCE(li.total_minor, e.amount_minor) AS amount, e.id AS entry_id " +
            "FROM ledger_entry e LEFT JOIN line_item li ON li.entry_id = e.id " +
            "LEFT JOIN merchant m ON m.id = e.merchant_id " +
            "WHERE e.ledger = :ledger AND e.deleted_at IS NULL " +
            "AND e.local_date BETWEEN :from AND :to " +
            "AND (:filterCategories = 0 OR " +
            "COALESCE(li.category_id, e.category_id, '') IN (:categoryIds)) " +
            "AND (:filterMerchants = 0 OR COALESCE(e.merchant_id, '') IN (:merchantIds)) " +
            "AND (:filterMethods = 0 OR " +
            "COALESCE(e.payment_method_id, '') IN (:paymentMethodIds)) " +
            "AND (:minAmount IS NULL OR e.amount_minor >= :minAmount) " +
            "AND (:maxAmount IS NULL OR e.amount_minor <= :maxAmount) " +
            "AND (:filterSources = 0 OR e.source IN (:sources)) " +
            "AND (:query = '' OR e.note LIKE :like ESCAPE '\\' " +
            "OR m.canonical_name LIKE :like ESCAPE '\\' " +
            "OR li.name LIKE :like ESCAPE '\\')) " +
            "GROUP BY dimension_id ORDER BY sum_minor DESC",
    )
    @Suppress("LongParameterList")
    public suspend fun dimensionTotalsFromEntries(
        ledger: LedgerType,
        groupBy: String,
        from: Int,
        to: Int,
        filterCategories: Int,
        categoryIds: List<String>,
        filterMerchants: Int,
        merchantIds: List<String>,
        filterMethods: Int,
        paymentMethodIds: List<String>,
        minAmount: Long?,
        maxAmount: Long?,
        filterSources: Int,
        sources: List<String>,
        query: String,
        like: String,
    ): List<DimensionTotalRow>

    /**
     * The window's distinct-entry count under an entry-level filter.
     *
     * **Every filter the total binds, this binds too.** It shipped without the
     * subcategory clause, so filtering to one subcategory showed the right
     * money beside the wrong count — "₹12,300.00" over "3 transactions" for a
     * single entry. A figure that disagrees with the one next to it is worse
     * than either being absent, because nothing on screen says which to trust.
     */
    @Query(
        "SELECT COUNT(DISTINCT e.id) FROM ledger_entry e " +
            "LEFT JOIN line_item li ON li.entry_id = e.id " +
            "LEFT JOIN merchant m ON m.id = e.merchant_id " +
            "WHERE e.ledger = :ledger AND e.deleted_at IS NULL " +
            "AND e.local_date BETWEEN :from AND :to " +
            "AND (:filterCategories = 0 OR " +
            "COALESCE(li.category_id, e.category_id, '') IN (:categoryIds)) " +
            "AND (:filterSubcategories = 0 OR " +
            "COALESCE(li.subcategory_id, e.subcategory_id, '') IN (:subcategoryIds)) " +
            "AND (:filterMerchants = 0 OR COALESCE(e.merchant_id, '') IN (:merchantIds)) " +
            "AND (:filterMethods = 0 OR " +
            "COALESCE(e.payment_method_id, '') IN (:paymentMethodIds)) " +
            "AND (:minAmount IS NULL OR e.amount_minor >= :minAmount) " +
            "AND (:maxAmount IS NULL OR e.amount_minor <= :maxAmount) " +
            "AND (:filterSources = 0 OR e.source IN (:sources)) " +
            "AND (:query = '' OR e.note LIKE :like ESCAPE '\\' " +
            "OR m.canonical_name LIKE :like ESCAPE '\\' " +
            "OR li.name LIKE :like ESCAPE '\\')",
    )
    @Suppress("LongParameterList")
    public suspend fun distinctEntriesFromEntries(
        ledger: LedgerType,
        from: Int,
        to: Int,
        filterCategories: Int,
        categoryIds: List<String>,
        filterSubcategories: Int,
        subcategoryIds: List<String>,
        filterMerchants: Int,
        merchantIds: List<String>,
        filterMethods: Int,
        paymentMethodIds: List<String>,
        minAmount: Long?,
        maxAmount: Long?,
        filterSources: Int,
        sources: List<String>,
        query: String,
        like: String,
    ): Int

    /** Every bucket in one book — the reconciliation diff reads this twice. */
    @Query("SELECT * FROM daily_rollup WHERE ledger = :ledger ORDER BY local_date")
    public suspend fun allFor(ledger: LedgerType): List<DailyRollupEntity>

    /**
     * The date a given entry sits on, so a write knows which bucket it touched.
     *
     * Soft delete and restore need this *before* they change the row, because
     * afterwards the recompute has to name a date the entry may no longer be
     * visible on.
     */
    @Query("SELECT local_date FROM ledger_entry WHERE id = :id AND ledger = :ledger")
    public suspend fun localDateOfEntry(ledger: LedgerType, id: String): Int?
}
