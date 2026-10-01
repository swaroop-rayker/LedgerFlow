package com.ledgerflow.navigation

import com.google.common.truth.Truth.assertThat
import com.ledgerflow.core.domain.inbox.InboxFilter
import com.ledgerflow.feature.inbox.InboxViewModel
import org.junit.Test

/**
 * The string that lets the diagnostics screen open the Inbox on Suppressed.
 *
 * The sibling of [InboxReviewArgumentTest]: `Destination.Inbox.filter` becomes
 * the argument key by its property name, and `InboxViewModel` reads that key by
 * string because it cannot see the route type. Rename either half and it still
 * compiles and navigates — "Show in Inbox" then opens the queue instead of the
 * duplicates it promised, with nothing failing anywhere.
 */
class InboxFilterArgumentTest {

    @Test
    fun theRoutePropertyNameMatchesTheArgumentTheInboxReads() {
        val fields = Destination.Inbox().javaClass.declaredFields.map { it.name }

        assertThat(fields).contains(InboxViewModel.FILTER_ARG)
    }

    /** The dial and the deep link open the queue, as they did before the argument existed. */
    @Test
    fun theDefaultRouteNamesNoFilter() {
        assertThat(Destination.Inbox().filter).isNull()
    }

    /** The value carried is the enum's name, which is what the ViewModel matches on. */
    @Test
    fun theSuppressedRouteCarriesTheFilterName() {
        val route = Destination.Inbox(filter = InboxFilter.SUPPRESSED.name)

        assertThat(InboxFilter.entries.map { it.name }).contains(route.filter)
    }
}
