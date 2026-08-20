package com.ledgerflow.core.database.dao

import androidx.room.ColumnInfo
import com.ledgerflow.core.model.LedgerType
import com.ledgerflow.core.model.Money

/**
 * An unsaved entry, projected for a screen that is not the entry form.
 *
 * Not an entity: it is `draft_entry`'s summary columns joined to `category` and
 * `merchant`, so the Ledger's pending section can render a draft in one read.
 *
 * **`payload_json` is absent, and that absence is the point.** The payload's
 * shape is `:feature:entry`'s business — `EntryDraftPayload` is `internal` to
 * that module and `DraftRepository` treats the JSON as opaque — so a projection
 * that carried it would let a second feature parse a schema neither of them
 * owns. The summary columns (schema v4) exist precisely so that never has to
 * happen.
 */
public data class DraftSummaryRow(
    @ColumnInfo(name = "id") val id: String,
    @ColumnInfo(name = "ledger") val ledger: LedgerType,
    @ColumnInfo(name = "amount_minor") val amountMinor: Money,
    @ColumnInfo(name = "category_name") val categoryName: String?,
    @ColumnInfo(name = "category_color_argb") val categoryColorArgb: Int?,
    @ColumnInfo(name = "merchant_name") val merchantName: String?,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
    /** Zero for a draft written before schema v5 -- see [datedAt]. */
    @ColumnInfo(name = "occurred_at") val occurredAt: Long,
) {
    /**
     * When to say this entry happened.
     *
     * `occurred_at` is the date the user picked in the form. Drafts written
     * before schema v5 have none, and for those the last edit is the closest
     * honest answer -- rendering 1 January 1970 would be worse than
     * approximately right, and back-filling was impossible because SQLite
     * cannot read the payload the real date lives in.
     */
    public val datedAt: Long get() = if (occurredAt > 0L) occurredAt else updatedAt
}
