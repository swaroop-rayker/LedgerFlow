package com.ledgerflow.core.database.dao

import androidx.room.ColumnInfo
import com.ledgerflow.core.model.LedgerType
import com.ledgerflow.core.model.Money

/**
 * One row of the bin: a soft-deleted entry with its names resolved.
 *
 * **Read from `ledger_entry`, not from a view, and that is unavoidable rather
 * than a shortcut.** `debit_entries` and `credit_entries` exist to hide exactly
 * these rows — their predicate is `deleted_at IS NULL` — so a view physically
 * cannot return one. ADR-0002's rule survives intact because the statement
 * still binds `:ledger`, which is what `LedgerIsolationTest` enforces and what
 * keeps a query from being pointed at the wrong book.
 *
 * Three `LEFT JOIN`s, one more than the Ledger's list row: the bin resolves the
 * subcategory too, because it is where a user tells two near-identical entries
 * apart before deciding which to keep.
 *
 * It also carries the same line-item fallback the list row does (ADR-0018), for
 * the same reason: an itemised entry files at line grain, so without it a binned
 * itemised entry reads as "Unfiled" here exactly as it did in the list. A
 * deleted entry is *more* dependent on it, not less -- the bin is where the user
 * decides whether to restore something, and "Unfiled" is the least useful thing
 * to tell them about a bill they broke into six categories.
 */
public data class DeletedEntryRow(
    @ColumnInfo(name = "id") val id: String,
    @ColumnInfo(name = "ledger") val ledger: LedgerType,
    @ColumnInfo(name = "amount_minor") val amountMinor: Money,
    @ColumnInfo(name = "currency") val currency: String,
    @ColumnInfo(name = "occurred_at") val occurredAt: Long,
    @ColumnInfo(name = "deleted_at") val deletedAt: Long,
    @ColumnInfo(name = "category_name") val categoryName: String?,
    @ColumnInfo(name = "category_color_argb") val categoryColorArgb: Int?,
    @ColumnInfo(name = "subcategory_name") val subcategoryName: String?,
    @ColumnInfo(name = "merchant_name") val merchantName: String?,
    @ColumnInfo(name = "note") val note: String?,
    /**
     * The categorised line with the largest signed total (ADR-0018). Null when
     * [categoryName] is non-null, or when no line item carries a category.
     */
    @ColumnInfo(name = "line_item_category_name") val lineItemCategoryName: String?,
    /** The swatch for [lineItemCategoryName]. Null exactly when it is. */
    @ColumnInfo(name = "line_item_category_color_argb") val lineItemCategoryColorArgb: Int?,
    /** Distinct categories across this entry's line items. 0 when none. */
    @ColumnInfo(name = "line_item_category_count") val lineItemCategoryCount: Int,
)
