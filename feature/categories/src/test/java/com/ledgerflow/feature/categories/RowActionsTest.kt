package com.ledgerflow.feature.categories

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * What each row's sheet offers (option A, 2026-10-31). The sheet replaced the
 * inline buttons, so it must offer exactly what they did, send the same events,
 * and keep their order: the action that removes the row comes last.
 */
class RowActionsTest {

    private fun labels(row: TaxonomyRow) = rowActions(row).map { it.label }

    @Test
    fun aCategory_offersRenameAddSubcategoryAndHide() {
        val row = TaxonomyRow.Category("1", "Food & Dining", isChild = false)

        assertThat(rowActions(row).map { it.event }).containsExactly(
            CategoriesEvent.RenameCategory("1", "Food & Dining"),
            CategoriesEvent.AddCategory("1", "Food & Dining"),
            CategoriesEvent.DeleteCategory("1", "Food & Dining", isChild = false),
        ).inOrder()
        assertThat(labels(row)).containsExactly("Rename", "Add subcategory", "Hide").inOrder()
    }

    /** The tree is two levels deep, so a subcategory cannot hold one. It can still be hidden. */
    @Test
    fun aSubcategory_offersRenameAndHide_butNoSubcategory() {
        val row = TaxonomyRow.Category("2", "Groceries", isChild = true)

        assertThat(labels(row)).containsExactly("Rename", "Hide").inOrder()
        assertThat(rowActions(row).last().event)
            .isEqualTo(CategoriesEvent.DeleteCategory("2", "Groceries", isChild = true))
    }

    @Test
    fun aMerchant_offersRenameMergeAndHide() {
        val row = TaxonomyRow.Merchant("m1", "Zepto")

        assertThat(rowActions(row).map { it.event }).containsExactly(
            CategoriesEvent.RenameMerchant("m1", "Zepto"),
            CategoriesEvent.StartMergeMerchant("m1", "Zepto"),
            CategoriesEvent.DeleteMerchant("m1", "Zepto"),
        ).inOrder()
    }

    @Test
    fun theDefaultMethod_doesNotOfferToBecomeTheDefault() {
        assertThat(labels(TaxonomyRow.PaymentMethod("p1", "Cash", isDefault = true)))
            .containsExactly("Hide")
        assertThat(labels(TaxonomyRow.PaymentMethod("p2", "HDFC Card", isDefault = false)))
            .containsExactly("Make default", "Hide").inOrder()
    }

    /** Restore first: the recoverable action is the one reached first. */
    @Test
    fun aHiddenRow_offersRestoreThenErase() {
        val row = TaxonomyRow.Hidden("h1", "Amazon")

        assertThat(rowActions(row).map { it.event }).containsExactly(
            CategoriesEvent.RestoreHidden("h1", "Amazon"),
            CategoriesEvent.EraseHidden("h1", "Amazon"),
        ).inOrder()
    }
}
