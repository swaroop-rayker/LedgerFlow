package com.ledgerflow.feature.settings

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * What the "Deleted entries" row says about itself.
 *
 * Worth a test for one reason: **the empty case is the one a user reading
 * Settings will normally find it in**, and it was originally handled by hiding
 * the row entirely. That was reported as the feature being missing — a control
 * that exists only sometimes is indistinguishable from one that was never
 * built. So the row is always there now, and the copy explaining an empty bin
 * is load-bearing rather than decorative.
 *
 * The plural forms are here because they are exactly the kind of thing that
 * silently regresses into "1 entries".
 */
class DeletedRowTest {

    @Test
    fun beforeTheCountArrives_saysWhatTheRowIsForRatherThanGuessing() {
        val subtitle = deletedSubtitle(MoreUiState(deletedCount = 0, isLoaded = false))

        // Not "nothing deleted": that would be a claim, and at this point the
        // database has not been asked yet.
        assertThat(subtitle).doesNotContain("Nothing deleted")
        assertThat(subtitle).contains("Restore")
    }

    @Test
    fun emptyBin_saysSoAndExplainsWhatTheRowIsFor() {
        val subtitle = deletedSubtitle(MoreUiState(deletedCount = 0, isLoaded = true))

        assertThat(subtitle).startsWith("Nothing deleted")
        assertThat(subtitle).contains("kept here")
    }

    @Test
    fun oneEntry_readsAsSingular() {
        assertThat(deletedSubtitle(MoreUiState(deletedCount = 1, isLoaded = true)))
            .isEqualTo("1 entry kept here. Restore it or erase it for good.")
    }

    @Test
    fun severalEntries_readAsPlural() {
        assertThat(deletedSubtitle(MoreUiState(deletedCount = 4, isLoaded = true)))
            .isEqualTo("4 entries kept here. Restore them or erase them for good.")
    }
}
