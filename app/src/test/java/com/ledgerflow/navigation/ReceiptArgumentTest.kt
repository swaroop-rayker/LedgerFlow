package com.ledgerflow.navigation

import com.google.common.truth.Truth.assertThat
import com.ledgerflow.feature.settings.receipts.ReceiptViewModel
import org.junit.Test

/**
 * The string that ties a row on the Receipts page to the receipt it opens (V2-5).
 *
 * The sibling of [InboxReviewArgumentTest]: `Destination.Receipt`'s property
 * name becomes the argument key, and `ReceiptViewModel` reads it by string
 * because `:feature:settings` cannot see routes in `:app`. Renamed on one side
 * only, everything compiles and the page opens on nothing and closes itself.
 */
class ReceiptArgumentTest {

    @Test
    fun theRoutePropertyNameMatchesTheArgumentTheReceiptPageReads() {
        val fields = Destination.Receipt("any").javaClass.declaredFields.map { it.name }

        assertThat(fields).contains(ReceiptViewModel.ATTACHMENT_ID_ARG)
    }
}
