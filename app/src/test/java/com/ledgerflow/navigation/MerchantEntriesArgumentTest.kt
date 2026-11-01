package com.ledgerflow.navigation

import com.google.common.truth.Truth.assertThat
import com.ledgerflow.feature.ledger.MerchantEntriesViewModel
import org.junit.Test

/**
 * The strings that carry Analytics' book, merchant and window to the merchant
 * entries page (V2-6).
 *
 * The sibling of [InboxFilterArgumentTest]: each property of
 * `Destination.MerchantEntries` becomes an argument key by its name, and
 * `MerchantEntriesViewModel` reads those keys by string because it cannot see
 * the route type. Rename one half and it still compiles and navigates, and
 * the page opens on an empty merchant or the wrong period.
 */
class MerchantEntriesArgumentTest {

    @Test
    fun everyArgumentThePageReads_isAPropertyOfTheRoute() {
        val route = Destination.MerchantEntries("DEBIT", "m", "Swiggy", 1, 2, analyticsFiltered = false)
        val fields = route.javaClass.declaredFields.map { it.name }

        assertThat(fields).containsAtLeast(
            MerchantEntriesViewModel.LEDGER_ARG,
            MerchantEntriesViewModel.MERCHANT_ID_ARG,
            MerchantEntriesViewModel.MERCHANT_NAME_ARG,
            MerchantEntriesViewModel.FROM_ARG,
            MerchantEntriesViewModel.TO_ARG,
            MerchantEntriesViewModel.FILTERED_ARG,
        )
    }
}
