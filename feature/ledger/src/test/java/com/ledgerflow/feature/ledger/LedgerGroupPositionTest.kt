package com.ledgerflow.feature.ledger

import com.google.common.truth.Truth.assertThat
import com.ledgerflow.core.designsystem.component.LfGroupPosition
import org.junit.Test

/**
 * Where a Ledger row sits in its band's panel (step F5). A row that both
 * opens and closes its band is the whole panel: drawn as First it would leave
 * the panel without a bottom edge.
 */
class LedgerGroupPositionTest {

    @Test
    fun groupPosition_coversAllFourCases() {
        assertThat(groupPosition(opens = true, closes = true)).isEqualTo(LfGroupPosition.Only)
        assertThat(groupPosition(opens = true, closes = false)).isEqualTo(LfGroupPosition.First)
        assertThat(groupPosition(opens = false, closes = false)).isEqualTo(LfGroupPosition.Middle)
        assertThat(groupPosition(opens = false, closes = true)).isEqualTo(LfGroupPosition.Last)
    }
}
