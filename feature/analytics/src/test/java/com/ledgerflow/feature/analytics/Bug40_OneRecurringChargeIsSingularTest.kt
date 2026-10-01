package com.ledgerflow.feature.analytics

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * **BUG40: "1 recurring charges expected".**
 *
 * The runway card (A10) counted its charges into a fixed plural. Found in P5
 * step 4's first Analytics golden, whose fixture had one charge due — the
 * common case for a single subscription, and the sentence most users with
 * any runway would have read.
 */
class Bug40_OneRecurringChargeIsSingularTest {

    @Test
    fun Bug40_oneCharge_isSingular() {
        assertThat(runwayCountLabel(1)).isEqualTo("1 recurring charge expected")
    }

    @Test
    fun severalCharges_arePlural() {
        assertThat(runwayCountLabel(3)).isEqualTo("3 recurring charges expected")
    }
}
