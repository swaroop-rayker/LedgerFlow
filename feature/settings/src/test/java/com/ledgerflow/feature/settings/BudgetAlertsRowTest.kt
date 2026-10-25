package com.ledgerflow.feature.settings

import com.google.common.truth.Truth.assertThat
import com.ledgerflow.core.domain.analytics.AlertChannelState
import org.junit.Test

/**
 * Settings > Notifications > Budget alerts (V2-PLAN §3.4). The row reports
 * Android's channel, which is the only switch, and says what turning it back
 * on takes; its tap is held by `SettingsSectionsTest`.
 */
class BudgetAlertsRowTest {

    @Test
    fun eachState_saysWhatItMeans() {
        assertThat(budgetAlertsSubtitle(AlertChannelState.ON))
            .isEqualTo("On. When a budget nears its limit, or is on course to go over.")
        assertThat(budgetAlertsSubtitle(AlertChannelState.CHANNEL_OFF))
            .isEqualTo("Off in Android settings. Tap to turn them on.")
        assertThat(budgetAlertsSubtitle(AlertChannelState.APP_OFF))
            .isEqualTo("LedgerFlow's notifications are off. Tap to turn them on.")
    }

    @Test
    fun beforeTheFirstRead_itClaimsNothing() {
        // Says what the row is for, not whether it is on.
        assertThat(budgetAlertsSubtitle(null)).doesNotContain("On.")
        assertThat(budgetAlertsSubtitle(null)).doesNotContain("off")
    }
}
