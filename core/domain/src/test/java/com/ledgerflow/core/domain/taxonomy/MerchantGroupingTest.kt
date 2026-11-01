package com.ledgerflow.core.domain.taxonomy

import com.google.common.truth.Truth.assertThat
import com.ledgerflow.core.model.Merchant
import com.ledgerflow.core.model.MerchantCategory
import org.junit.Test

/**
 * The one grouping rule Organise and the merchant picker share (V2-6): the
 * categories' own order, empty ones left out, Uncategorised last.
 */
class MerchantGroupingTest {

    private val groceries = MerchantCategory("g", "Groceries", 0, 0, isSystem = true)
    private val fuel = MerchantCategory("f", "Fuel", 0, 1, isSystem = true)
    private val pharmacy = MerchantCategory("p", "Pharmacy & health", 0, 2, isSystem = true)
    private val categories = listOf(groceries, fuel, pharmacy)

    private fun merchant(id: String, category: String?) =
        Merchant(id, id, id, null, null, merchantCategoryId = category)

    private val merchants = listOf(
        merchant("zepto", null),
        merchant("shell", "f"),
        merchant("bigbazaar", "g"),
        merchant("hp", "f"),
        merchant("studio", null),
    )

    private fun shape(groups: List<MerchantGroup>) =
        groups.map { (it.category?.name ?: "-") to it.merchants.map(Merchant::id) }

    @Test
    fun groups_followTheCategoriesOrder_withUncategorisedLast() {
        assertThat(shape(groupByMerchantCategory(merchants, categories))).containsExactly(
            "Groceries" to listOf("bigbazaar"),
            "Fuel" to listOf("shell", "hp"),
            "-" to listOf("zepto", "studio"),
        ).inOrder()
    }

    @Test
    fun aCategoryWithNoMerchants_hasNoGroup() {
        val ids = groupByMerchantCategory(merchants, categories).map { it.category?.id }
        assertThat(ids).doesNotContain("p")
    }

    /** A category hidden before its merchants moved: Analytics calls them Uncategorised, and so does this. */
    @Test
    fun aMerchantUnderAnUnknownCategory_isUncategorised() {
        val groups = groupByMerchantCategory(listOf(merchant("gone", "hidden-id")), categories)
        assertThat(shape(groups)).containsExactly("-" to listOf("gone"))
    }

    @Test
    fun whenEverythingIsFiled_thereIsNoUncategorisedGroup() {
        val groups = groupByMerchantCategory(listOf(merchant("shell", "f")), categories)
        assertThat(groups.map { it.category }).containsExactly(fuel)
    }

    @Test
    fun noMerchants_noGroups() {
        assertThat(groupByMerchantCategory(emptyList(), categories)).isEmpty()
    }

    // ── The merchant picker (V2-6 PR 3) ────────────────────────────────────

    @Test
    fun picker_withAnEmptySearch_browsesByCategory() {
        assertThat(merchantPickerGroups(merchants, categories, query = "  ").map { it.title })
            .containsExactly("Groceries", "Fuel", UNCATEGORISED_MERCHANTS).inOrder()
    }

    /** Typing means the user knows the name: matches, not headings. */
    @Test
    fun picker_whileSearching_isFlat() {
        assertThat(merchantPickerGroups(merchants, categories, query = "sh")).isEmpty()
    }

    /** One Uncategorised heading over every merchant says nothing, so no headings at all. */
    @Test
    fun picker_whenNothingIsFiled_isFlat() {
        val unfiled = listOf(merchant("zepto", null), merchant("studio", null))
        assertThat(merchantPickerGroups(unfiled, categories, query = "")).isEmpty()
    }
}
