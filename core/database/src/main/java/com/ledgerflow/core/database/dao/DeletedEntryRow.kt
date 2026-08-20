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
)
