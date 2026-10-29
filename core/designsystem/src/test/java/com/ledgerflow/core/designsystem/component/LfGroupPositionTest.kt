package com.ledgerflow.core.designsystem.component

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Where an item sits in a list that is one panel (step F5, the Inbox). A list
 * of one is the whole panel: as First it would have no bottom edge.
 */
class LfGroupPositionTest {

    @Test
    fun aListOfOne_isTheWholePanel() {
        assertThat(lfGroupPosition(index = 0, size = 1)).isEqualTo(LfGroupPosition.Only)
    }

    @Test
    fun aLongerList_opensOnce_andClosesOnce() {
        val positions = (0 until 4).map { lfGroupPosition(it, size = 4) }
        assertThat(positions).containsExactly(
            LfGroupPosition.First,
            LfGroupPosition.Middle,
            LfGroupPosition.Middle,
            LfGroupPosition.Last,
        ).inOrder()
    }
}
