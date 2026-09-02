package com.ledgerflow.core.database.dao

import androidx.room.ColumnInfo
import com.ledgerflow.core.model.Money

/**
 * One point of a time series (`SPEC.md` §5.6, A1).
 *
 * `bucket` is an ordinal, not a date: the query groups by
 * `(local_date - :from) / :bucketDays`, so bucket 0 starts at `from` and each
 * step is `bucketDays` wide. Integer division in SQL, which is exact and needs
 * no calendar — the same reason `local_date` exists at all (§6.1).
 *
 * That deliberately makes a "month" 30 days rather than a calendar month. A
 * calendar-accurate grouping needs timezone-aware date maths in SQL, which §6.1
 * exists to avoid, and the axis labels say which range a bucket covers. If
 * calendar months are ever required for a *figure* rather than a chart bucket,
 * that is a different query and should be written as one.
 */
public data class TimeBucketRow(
    @ColumnInfo(name = "bucket") val bucket: Int,
    @ColumnInfo(name = "sum_minor") val sumMinor: Money,
    @ColumnInfo(name = "txn_count") val txnCount: Int,
)

/**
 * A total for one dimension value — a category, merchant or payment method.
 *
 * `txnCount` is safe to sum here and it is worth saying why, because it is not
 * safe everywhere: §5.6 fixes `txn_count` as **distinct entries**, and an entry
 * carries exactly one date, one merchant and one payment method. Only the
 * category dimensions fan out (ADR-0018). So summing over dates within one
 * category is correct, and summing *across* categories is not — which is why
 * there is no query here that does the latter.
 */
public data class DimensionTotalRow(
    @ColumnInfo(name = "dimension_id") val dimensionId: String,
    @ColumnInfo(name = "sum_minor") val sumMinor: Money,
    @ColumnInfo(name = "txn_count") val txnCount: Int,
)

/** A subcategory total under its parent (A3). */
public data class SubcategoryTotalRow(
    @ColumnInfo(name = "category_id") val categoryId: String,
    @ColumnInfo(name = "dimension_id") val dimensionId: String,
    @ColumnInfo(name = "sum_minor") val sumMinor: Money,
    @ColumnInfo(name = "txn_count") val txnCount: Int,
)

/** One category's share of one time bucket (A1's stacking). */
public data class BucketCategoryRow(
    @ColumnInfo(name = "bucket") val bucket: Int,
    @ColumnInfo(name = "dimension_id") val dimensionId: String,
    @ColumnInfo(name = "sum_minor") val sumMinor: Money,
)
