package com.ledgerflow.core.domain.analytics

import com.ledgerflow.core.model.EntrySource
import com.ledgerflow.core.model.Money

/**
 * §5.6's composable filters — all simultaneously active.
 *
 * **They divide into two kinds, and the division decides which table answers.**
 *
 * *Dimension* filters — category, subcategory, merchant, payment method — name
 * columns `daily_rollup` already carries, so a filtered chart is still a rollup
 * read and still meets §11's 5Y budget.
 *
 * *Entry-level* filters — amount range, source, text — name columns the rollup
 * does **not** carry and must never be given: `daily_rollup` sums money per
 * dimension, and widening it to hold an entry's note or source is the move
 * `CLAUDE.md` §5 forbids. When one of these is active the aggregate reads the
 * base tables instead. That is slower, and it is the honest trade: §11's budget
 * is for the 5Y *view*, and a user who has typed a search term has already
 * narrowed the data far below 5Y.
 *
 * **`hasAttachment` is absent, and that is not an oversight.** §5.6 lists ten
 * filters; the tenth needs the `attachment` table, which schema v10 does not
 * have — §6.1 specifies it and it lands with OCR at P4. A filter over a table
 * that does not exist would have to be silently ignored, which is worse than
 * not offering it.
 */
public data class AnalyticsFilters(
    val categoryIds: Set<String> = emptySet(),
    val subcategoryIds: Set<String> = emptySet(),
    val merchantIds: Set<String> = emptySet(),
    val paymentMethodIds: Set<String> = emptySet(),
    val minAmount: Money? = null,
    val maxAmount: Money? = null,
    val sources: Set<EntrySource> = emptySet(),
    /** Matched against the entry's note, its merchant's name, and item names. */
    val query: String = "",
) {
    /** True when nothing is narrowed — the common case, and the fast path. */
    public val isEmpty: Boolean
        get() = categoryIds.isEmpty() && subcategoryIds.isEmpty() &&
            merchantIds.isEmpty() && paymentMethodIds.isEmpty() &&
            minAmount == null && maxAmount == null && sources.isEmpty() &&
            query.isBlank()

    /**
     * True when a filter names something `daily_rollup` cannot see.
     *
     * The single question the query layer asks: rollup, or base tables.
     */
    public val needsBaseTables: Boolean
        get() = minAmount != null || maxAmount != null ||
            sources.isNotEmpty() || query.isNotBlank()

    /** How many distinct filters are active, for the UI's "Filters (3)" chip. */
    public val activeCount: Int
        get() = listOf(
            categoryIds.isNotEmpty(),
            subcategoryIds.isNotEmpty(),
            merchantIds.isNotEmpty(),
            paymentMethodIds.isNotEmpty(),
            minAmount != null || maxAmount != null,
            sources.isNotEmpty(),
            query.isNotBlank(),
        ).count { it }

    public companion object {
        public val None: AnalyticsFilters = AnalyticsFilters()
    }
}
