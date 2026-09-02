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
