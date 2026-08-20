package com.ledgerflow.navigation

import com.google.common.truth.Truth.assertThat
import com.ledgerflow.feature.entry.EntryViewModel
import org.junit.Test

/**
 * The one string that ties the Ledger's pending rows to the entry form.
 *
 * `Destination.Entry` carries a `draftId`, and Navigation Compose turns that
 * *property name* into the argument key. `EntryViewModel` reads the key out of
 * its `SavedStateHandle` by string, because it cannot reference the route type:
 * routes live in `:app` precisely so features never depend on one another
 * (CLAUDE.md §3).
 *
 * So the contract is a name, agreed across a module boundary, with nothing in
 * the type system holding the two halves together. Rename the property and the
 * code still compiles, still navigates, and silently opens a blank form instead
 * of the draft the user tapped — which reads exactly like the draft was lost.
 * This is the test that fails instead.
 */
class EntryDraftArgumentTest {

    /**
     * Java reflection, not `KClass.members`: the latter needs `kotlin-reflect`
     * on the test classpath, and pulling in a reflection library to check one
     * field name would cost more than the check is worth.
     */
    @Test
    fun theRoutePropertyNameMatchesTheArgumentTheEntryFormReads() {
        val fields = Destination.Entry().javaClass.declaredFields.map { it.name }

        assertThat(fields).contains(EntryViewModel.DRAFT_ID_ARG)
    }

    /** A null id is "a new entry", which is what the centre action asks for. */
    @Test
    fun theRouteDefaultsToNoDraft() {
        assertThat(Destination.Entry().draftId).isNull()
    }
}
