package com.ledgerflow.navigation

import com.google.common.truth.Truth.assertThat
import com.ledgerflow.feature.categories.CategoriesViewModel
import org.junit.Test

/**
 * The string that lets Analytics open Organise on the merchants still to file
 * (V2-6, "Categorise in Merchants").
 *
 * The sibling of [InboxFilterArgumentTest]: `Destination.Categories.view`
 * becomes the argument key by its property name, and `CategoriesViewModel`
 * reads that key by string because it cannot see the route type. Rename
 * either half and it still compiles and navigates, and the link opens
 * Categories instead of the merchants it promised.
 */
class OrganiseViewArgumentTest {

    @Test
    fun theRoutePropertyNameMatchesTheArgumentOrganiseReads() {
        val fields = Destination.Categories().javaClass.declaredFields.map { it.name }

        assertThat(fields).contains(CategoriesViewModel.VIEW_ARG)
    }

    /** Settings > Organise opens it as it always did. */
    @Test
    fun theDefaultRouteNamesNoView() {
        assertThat(Destination.Categories().view).isNull()
    }
}
