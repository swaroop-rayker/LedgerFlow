package com.ledgerflow.core.domain.analytics

import com.ledgerflow.core.domain.taxonomy.UNCATEGORISED_MERCHANTS
import com.ledgerflow.core.model.MerchantCategory
import com.ledgerflow.core.model.Money

/**
 * A11: one book's spending by merchant category (V2-PLAN §5.4, V2-6 PR 4).
 *
 * **Regrouped from the merchant totals, not read again.** The snapshot already
 * sums `daily_rollup` per merchant, with every filter applied, bound to one
 * book (Law 2). Each merchant's total lands in the bucket of the category the
 * merchant has *now*, so re-filing a merchant moves its whole history: the
 * retroactive rule §5.4 asks for, with no new query and no new rollup column.
 *
 * [totals] ranks the categories by amount, then **Uncategorised** (merchants
 * not yet filed), then **No merchant** (spending with no merchant at all, which
 * no filing can fix, so it is its own row rather than part of Uncategorised).
 * Together they add up to the page total. [merchants] holds each bucket's
 * merchants, ranked, for the drill-down.
 */
public data class MerchantCategoryBreakdown(
    val totals: List<DimensionTotal>,
    val merchants: Map<String, List<DimensionTotal>>,
) {
    public companion object {
        public val Empty: MerchantCategoryBreakdown = MerchantCategoryBreakdown(emptyList(), emptyMap())

        /** Uncategorised's id in [totals]: never a category id, which are UUIDs. */
        public const val UNCATEGORISED_ID: String = "uncategorised"

        /** No merchant's id: the `''` sentinel the merchant totals already use (§6.1.1). */
        public const val NO_MERCHANT_ID: String = ""

        public const val NO_MERCHANT: String = "No merchant"
    }
}

/**
 * Builds [MerchantCategoryBreakdown] from [merchants], the snapshot's merchant
 * totals (`''` is spending with no merchant).
 *
 * @param categoryOf each merchant's merchant category id, hidden merchants
 *   included: they keep their category (§5.1) and their spending is real.
 * @param categories the live merchant categories. A merchant filed under one
 *   that is not here counts as Uncategorised, as Organise shows it.
 * @param previous the same merchant totals for the previous window, or null
 *   when comparison is off; each bucket's previous amount is summed from them
 *   through the categories merchants have *now*, so the change compares like
 *   with like.
 */
public fun merchantCategoryBreakdown(
    merchants: List<DimensionTotal>,
    categoryOf: Map<String, String?>,
    categories: List<MerchantCategory>,
    previous: Map<String, Money>?,
): MerchantCategoryBreakdown {
    val live = categories.associateBy { it.id }
    fun bucketOf(merchantId: String): String = when {
        merchantId.isEmpty() -> MerchantCategoryBreakdown.NO_MERCHANT_ID
        else -> categoryOf[merchantId]?.takeIf { it in live } ?: MerchantCategoryBreakdown.UNCATEGORISED_ID
    }

    val byBucket = merchants.groupBy { bucketOf(it.id) }
    val previousByBucket = previous?.entries
        ?.groupBy({ bucketOf(it.key) }, { it.value })
        ?.mapValues { (_, amounts) -> Money.sum(amounts) }

    fun total(id: String, name: String, colorArgb: Int?, rows: List<DimensionTotal>) = DimensionTotal(
        id = id,
        name = name,
        colorArgb = colorArgb,
        amount = Money.sum(rows.map { it.amount }),
        transactionCount = rows.sumOf { it.transactionCount },
        previousAmount = previousByBucket?.let { it[id] ?: Money(0L) },
    )

    val filed = byBucket.mapNotNull { (id, rows) ->
        live[id]?.let { category -> total(id, category.name, category.colorArgb, rows) }
    }.sortedWith(compareByDescending<DimensionTotal> { it.amount.minor }.thenBy { it.name })
    val unfiled = listOfNotNull(
        byBucket[MerchantCategoryBreakdown.UNCATEGORISED_ID]?.let {
            total(MerchantCategoryBreakdown.UNCATEGORISED_ID, UNCATEGORISED_MERCHANTS, null, it)
        },
        byBucket[MerchantCategoryBreakdown.NO_MERCHANT_ID]?.let {
            total(MerchantCategoryBreakdown.NO_MERCHANT_ID, MerchantCategoryBreakdown.NO_MERCHANT, null, it)
        },
    )
    return MerchantCategoryBreakdown(
        totals = filed + unfiled,
        merchants = byBucket
            .filterKeys { it != MerchantCategoryBreakdown.NO_MERCHANT_ID }
            .mapValues { (_, rows) -> rows.sortedByDescending { it.amount.minor } },
    )
}

/**
 * The merchant-category filter (A9), turned into the merchant filter every
 * query already honours, so it combines with the others and reads the rollup
 * like any narrowing by merchant.
 *
 * Merchants in the chosen categories ([MerchantCategoryBreakdown.UNCATEGORISED_ID]
 * picks the unfiled ones), intersected with any merchants also chosen. When
 * nothing is left the result names one id no merchant has, so the read matches
 * nothing rather than dropping the filter and matching everything.
 */
public fun AnalyticsFilters.resolveMerchantCategories(
    categoryOf: Map<String, String?>,
    categories: List<MerchantCategory>,
): AnalyticsFilters {
    if (merchantCategoryIds.isEmpty()) return this
    val live = categories.mapTo(HashSet()) { it.id }
    val inCategories = categoryOf.filter { (_, categoryId) ->
        (categoryId?.takeIf { it in live } ?: MerchantCategoryBreakdown.UNCATEGORISED_ID) in merchantCategoryIds
    }.keys
    val narrowed = if (merchantIds.isEmpty()) inCategories else inCategories intersect merchantIds
    return copy(merchantIds = narrowed.ifEmpty { setOf(NO_SUCH_MERCHANT) }, merchantCategoryIds = emptySet())
}

/** An id no merchant has: merchant ids are UUIDs. */
private const val NO_SUCH_MERCHANT = "no-such-merchant"
