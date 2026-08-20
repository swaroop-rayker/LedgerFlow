package com.ledgerflow.core.database.dao

import androidx.room.ColumnInfo
import com.ledgerflow.core.model.Money

/**
 * One page-row of the Ledger list.
 *
 * Not an entity and not one of the ledger views: it is the projection of a view
 * joined to `category` and `merchant`, so a page of the list is a single read
 * rather than a row read plus two lookups per entry.
 *
 * The projection is explicit rather than `SELECT *` because this feeds a
 * `PagingSource`. Room's `LimitOffsetPagingSource` runs the statement once per
 * page against a `COUNT(*)` of the same query; every column named here is read
 * off disk for every visible row, and the ledger's twenty-odd columns include
 * three foreign-currency fields and a provenance pair the list never shows.
 *
 * There is one of these rather than a debit and a credit variant: the shape is
 * identical, and which book a row came from is a property of the *statement*
 * that produced it, not of the row. The two statements read `debit_entries` and
 * `credit_entries` respectively (ADR-0002) and neither can be pointed at the
 * other book by passing a different argument.
 */
public data class LedgerListRow(
    @ColumnInfo(name = "id") val id: String,
    @ColumnInfo(name = "amount_minor") val amountMinor: Money,
    @ColumnInfo(name = "currency") val currency: String,
    @ColumnInfo(name = "local_date") val localDate: Int,
    @ColumnInfo(name = "occurred_at") val occurredAt: Long,
    @ColumnInfo(name = "category_name") val categoryName: String?,
    @ColumnInfo(name = "category_color_argb") val categoryColorArgb: Int?,
    @ColumnInfo(name = "merchant_name") val merchantName: String?,
    @ColumnInfo(name = "note") val note: String?,
)
