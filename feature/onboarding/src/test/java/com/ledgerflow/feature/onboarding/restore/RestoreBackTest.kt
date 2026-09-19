package com.ledgerflow.feature.onboarding.restore

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * What back does on the restore screen (BUG29), as a decision separate from
 * the platform's callback ordering that `Bug29_BackWithTheKeyboardOpenStaysOnRestoreTest`
 * exercises on a device.
 */
class RestoreBackTest {

    @Test
    fun withTheKeyboardOpen_backClosesTheKeyboard() {
        assertThat(restoreBack(keyboardOpen = true, isWorking = false)).isEqualTo(RestoreBack.CloseKeyboard)
        assertThat(restoreBack(keyboardOpen = true, isWorking = true)).isEqualTo(RestoreBack.CloseKeyboard)
    }

    @Test
    fun whileARestoreRuns_backStays() {
        assertThat(restoreBack(keyboardOpen = false, isWorking = true)).isEqualTo(RestoreBack.Stay)
    }

    @Test
    fun otherwise_backLeaves() {
        assertThat(restoreBack(keyboardOpen = false, isWorking = false)).isEqualTo(RestoreBack.Leave)
    }
}
