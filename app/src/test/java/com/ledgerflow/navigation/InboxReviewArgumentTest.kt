package com.ledgerflow.navigation

import com.google.common.truth.Truth.assertThat
import com.ledgerflow.feature.inbox.ReviewViewModel
import org.junit.Test

/**
 * The one string that ties an Inbox row to the review screen. P2-6.
 *
 * The sibling of [EntryDraftArgumentTest], for the same reason and with a worse
 * failure mode. `Destination.InboxReview` carries a `pendingId`, Navigation
 * Compose turns that *property name* into the argument key, and
 * `ReviewViewModel` reads the key out of its `SavedStateHandle` by string —
 * because it cannot reference the route type, routes living in `:app` precisely
 * so features never depend on one another (CLAUDE.md §3).
 *
 * Nothing in the type system holds the two halves together. Rename the property
 * and everything still compiles and still navigates; the review screen then
 * fails its `requireNotNull` and the user, who tapped a bank message they were
 * about to approve, gets a crash. §5.1 also deep-links straight to this
 * destination at P2-7, so the same name is about to have a third caller that
 * cannot see it either.
 */
class InboxReviewArgumentTest {

    /**
     * Java reflection, not `KClass.members`: the latter needs `kotlin-reflect`
     * on the test classpath, and pulling in a reflection library to check one
     * field name would cost more than the check is worth.
     */
    @Test
    fun theRoutePropertyNameMatchesTheArgumentTheReviewScreenReads() {
        val fields = Destination.InboxReview("any").javaClass.declaredFields.map { it.name }

        assertThat(fields).contains(ReviewViewModel.PENDING_ID_ARG)
    }

    /**
     * The id is required, unlike the entry form's draft id.
     *
     * There is no "review nothing": every route into this screen names a
     * candidate, so a nullable argument would only be a way to reach it with
     * nothing to show.
     */
    @Test
    fun theRouteCarriesTheCandidateItOpens() {
        assertThat(Destination.InboxReview("pending-1").pendingId).isEqualTo("pending-1")
    }
}
