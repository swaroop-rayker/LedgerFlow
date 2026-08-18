package com.ledgerflow.core.database.dao

import androidx.room.ColumnInfo

/**
 * A category/merchant/instrument combination the ledger has already seen.
 *
 * Not an entity — it is the projection of a `GROUP BY` over one ledger's view,
 * and it exists so §5.4's repeat-expense chips can be filled from a single
 * query rather than by loading entries and grouping them in Kotlin.
 */
public data class EntryComboRow(
    @ColumnInfo(name = "category_id") val categoryId: String,
    @ColumnInfo(name = "subcategory_id") val subcategoryId: String?,
    @ColumnInfo(name = "merchant_id") val merchantId: String?,
    @ColumnInfo(name = "payment_method_id") val paymentMethodId: String?,
    @ColumnInfo(name = "uses") val uses: Int,
    @ColumnInfo(name = "last_used_at") val lastUsedAt: Long,
)
