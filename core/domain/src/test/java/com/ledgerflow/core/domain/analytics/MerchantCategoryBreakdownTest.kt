package com.ledgerflow.core.domain.analytics

import com.google.common.truth.Truth.assertThat
import com.ledgerflow.core.domain.taxonomy.UNCATEGORISED_MERCHANTS
import com.ledgerflow.core.model.MerchantCategory
import com.ledgerflow.core.model.Money
import org.junit.Test

/**
 * A11 (V2-6): spending by merchant category, regrouped from the merchant
 * totals, and the merchant-category filter turned into a merchant filter.
 */
class MerchantCategoryBreakdownTest {

    private val groceries = MerchantCategory("g", "Groceries", 1, 0, isSystem = true)
    private val fuel = MerchantCategory("f", "Fuel", 2, 1, isSystem = true)
    private val categories = listOf(groceries, fuel)

    private val categoryOf = mapOf(
        "bigbazaar" to "g",
        "corner" to "g",
        "shell" to "f",
        "zepto" to null,
        "oldshop" to "hidden",
    )

    private fun spent(id: String, minor: Long, count: Int = 1) =
        DimensionTotal(id, id, null, Money(minor), count, previousAmount = null)

    private val merchants = listOf(
        spent("bigbazaar", 600, 3),
        spent("shell", 900, 2),
        spent("corner", 400),
        spent("zepto", 200),
        spent("oldshop", 50),
        spent("", 70),
    )

    private fun breakdown(previous: Map<String, Money>? = null) =
        merchantCategoryBreakdown(merchants, categoryOf, categories, previous)

    @Test
    fun ranksByAmount_thenUncategorised_thenNoMerchant() {
        assertThat(breakdown().totals.map { it.name })
            .containsExactly("Groceries", "Fuel", UNCATEGORISED_MERCHANTS, MerchantCategoryBreakdown.NO_MERCHANT)
            .inOrder()
    }

    /** §5.4: the section's total never disagrees with the page's. */
    @Test
    fun theBucketsAddUpToEveryMerchantTotal() {
        val buckets = Money.sum(breakdown().totals.map { it.amount })
        assertThat(buckets).isEqualTo(Money.sum(merchants.map { it.amount }))
    }

    @Test
    fun aBucketSumsItsMerchants_amountsAndCounts() {
        val groceriesRow = breakdown().totals.first { it.id == "g" }
        assertThat(groceriesRow.amount).isEqualTo(Money(1_000))
        assertThat(groceriesRow.transactionCount).isEqualTo(4)
        assertThat(groceriesRow.colorArgb).isEqualTo(1)
    }

    /** A category hidden before its merchants moved: its spending is Uncategorised, as Organise shows it. */
    @Test
    fun aMerchantUnderAnUnknownCategory_isUncategorised() {
        val unfiled = breakdown().merchants.getValue(MerchantCategoryBreakdown.UNCATEGORISED_ID)
        assertThat(unfiled.map { it.id }).containsExactly("zepto", "oldshop").inOrder()
    }

    /** The drill-down: each bucket's merchants, ranked. No merchant has none to list. */
    @Test
    fun eachBucketListsItsMerchants_ranked() {
        val drill = breakdown().merchants
        assertThat(drill.getValue("g").map { it.id }).containsExactly("bigbazaar", "corner").inOrder()
        assertThat(drill).doesNotContainKey(MerchantCategoryBreakdown.NO_MERCHANT_ID)
    }

    /** Re-filing moves history: the same totals land where the merchant is filed *now*. */
    @Test
    fun refilingAMerchant_movesItsWholeHistory() {
        val refiled = merchantCategoryBreakdown(merchants, categoryOf + ("shell" to "g"), categories, null)
        assertThat(refiled.totals.first { it.id == "g" }.amount).isEqualTo(Money(1_900))
        assertThat(refiled.totals.map { it.id }).doesNotContain("f")
    }

    /** Last period is regrouped through today's categories, so the change compares like with like. */
    @Test
    fun previousAmounts_followTodaysFiling_andZeroWhenAbsent() {
        val withPrevious = breakdown(previous = mapOf("bigbazaar" to Money(300), "zepto" to Money(10)))
        val byId = withPrevious.totals.associateBy { it.id }
        assertThat(byId.getValue("g").previousAmount).isEqualTo(Money(300))
        assertThat(byId.getValue("f").previousAmount).isEqualTo(Money(0))
        assertThat(byId.getValue(MerchantCategoryBreakdown.UNCATEGORISED_ID).previousAmount).isEqualTo(Money(10))
    }

    @Test
    fun comparisonOff_leavesPreviousNull() {
        assertThat(breakdown().totals.map { it.previousAmount }.toSet()).containsExactly(null)
    }

    // ── The filter ─────────────────────────────────────────────────────────

    @Test
    fun filter_noMerchantCategories_changesNothing() {
        val filters = AnalyticsFilters(merchantIds = setOf("shell"))
        assertThat(filters.resolveMerchantCategories(categoryOf, categories)).isSameInstanceAs(filters)
    }

    @Test
    fun filter_aCategory_becomesItsMerchants() {
        val resolved = AnalyticsFilters(merchantCategoryIds = setOf("g"))
            .resolveMerchantCategories(categoryOf, categories)
        assertThat(resolved.merchantIds).containsExactly("bigbazaar", "corner")
        assertThat(resolved.merchantCategoryIds).isEmpty()
    }

    @Test
    fun filter_uncategorised_isTheUnfiledMerchants() {
        val resolved = AnalyticsFilters(merchantCategoryIds = setOf(MerchantCategoryBreakdown.UNCATEGORISED_ID))
            .resolveMerchantCategories(categoryOf, categories)
        assertThat(resolved.merchantIds).containsExactly("zepto", "oldshop")
    }

    /** Both chosen: the merchants in the category that were also picked by name. */
    @Test
    fun filter_withMerchants_intersects() {
        val resolved = AnalyticsFilters(merchantIds = setOf("corner", "shell"), merchantCategoryIds = setOf("g"))
            .resolveMerchantCategories(categoryOf, categories)
        assertThat(resolved.merchantIds).containsExactly("corner")
    }

    /** Nothing left must match nothing, not drop the filter and match everything. */
    @Test
    fun filter_leavingNoMerchant_stillNarrows() {
        val resolved = AnalyticsFilters(merchantIds = setOf("shell"), merchantCategoryIds = setOf("g"))
            .resolveMerchantCategories(categoryOf, categories)
        assertThat(resolved.merchantIds).hasSize(1)
        assertThat(resolved.merchantIds.single()).isNotIn(categoryOf.keys)
        assertThat(resolved.isEmpty).isFalse()
    }

    @Test
    fun filter_countsAsOneActiveFilter() {
        assertThat(AnalyticsFilters(merchantCategoryIds = setOf("g")).activeCount).isEqualTo(1)
        assertThat(AnalyticsFilters(merchantCategoryIds = setOf("g")).isEmpty).isFalse()
    }
}
