package com.ledgerflow.feature.categories

import com.google.common.truth.Truth.assertThat
import com.ledgerflow.core.model.Merchant
import com.ledgerflow.core.model.MerchantCategory
import org.junit.Test

/**
 * Organise > Merchants grouped under their merchant categories (owner's
 * option A, 2026-11-01): the categories' own order, empty ones left out,
 * Uncategorised last.
 */
class MerchantGroupsTest {

    private val groceries = MerchantCategory("g", "Groceries", 0, 0, isSystem = true)
    private val fuel = MerchantCategory("f", "Fuel", 0, 1, isSystem = true)
    private val pharmacy = MerchantCategory("p", "Pharmacy & health", 0, 2, isSystem = true)

    private fun merchant(id: String, category: String?) =
        Merchant(id, id, id, null, null, merchantCategoryId = category)

    private val state = CategoriesUiState(
        section = TaxonomySection.Merchants,
        merchantCategories = listOf(groceries, fuel, pharmacy),
        merchants = listOf(
            merchant("zepto", null),
            merchant("shell", "f"),
            merchant("bigbazaar", "g"),
            merchant("hp", "f"),
            merchant("studio", null),
        ),
    )

    private fun CategoriesUiState.shape() = merchantGroups.map { it.name to it.merchants.map(Merchant::id) }

    @Test
    fun merchantGroups_followTheCategoriesOrder_withUncategorisedLast() {
        assertThat(state.shape()).containsExactly(
            "Groceries" to listOf("bigbazaar"),
            "Fuel" to listOf("shell", "hp"),
            UNCATEGORISED to listOf("zepto", "studio"),
        ).inOrder()
    }

    @Test
    fun merchantGroups_leaveOutACategoryWithNoMerchants() {
        assertThat(state.merchantGroups.map { it.categoryId }).doesNotContain("p")
    }

    @Test
    fun merchantGroups_uncategorisedHasNoIdAndNoColour() {
        val last = state.merchantGroups.last()
        assertThat(last.categoryId).isNull()
        assertThat(last.colorArgb).isNull()
    }

    /** A category hidden before its merchants moved: Analytics calls them Uncategorised, and so does the tab. */
    @Test
    fun merchantGroups_aMerchantUnderAnUnknownCategory_isUncategorised() {
        val stale = state.copy(merchants = listOf(merchant("gone", "hidden-id")))
        assertThat(stale.shape()).containsExactly(UNCATEGORISED to listOf("gone"))
    }

    @Test
    fun merchantGroups_underTheUncategorisedFilter_areOnlyThatGroup() {
        val filtered = state.copy(merchantFilter = MerchantFilter.Uncategorised)
        assertThat(filtered.shape()).containsExactly(UNCATEGORISED to listOf("zepto", "studio"))
    }

    @Test
    fun merchantGroups_whenEverythingIsFiled_haveNoUncategorisedGroup() {
        val filed = state.copy(merchants = listOf(merchant("shell", "f")))
        assertThat(filed.shape()).containsExactly("Fuel" to listOf("shell"))
    }
}
