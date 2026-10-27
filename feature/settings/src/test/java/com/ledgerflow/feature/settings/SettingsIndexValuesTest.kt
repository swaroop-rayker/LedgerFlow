package com.ledgerflow.feature.settings

import com.google.common.truth.Truth.assertThat
import com.ledgerflow.core.domain.appearance.AppearancePreferences
import com.ledgerflow.core.domain.appearance.ThemePreference
import com.ledgerflow.core.domain.ingest.NotificationCaptureHealth
import com.ledgerflow.core.domain.security.SecurityPreferences
import org.junit.Test

/**
 * What the Settings index says beside each section (design-system step D4,
 * owner 2026-10-27): a value only where one value says the section.
 */
class SettingsIndexValuesTest {

    private val state = SettingsUiState(
        isLoaded = true,
        captureHealth = NotificationCaptureHealth.CONNECTED,
        appearance = AppearancePreferences(theme = ThemePreference.DARK),
        baseCurrency = "INR",
        security = SecurityPreferences(appLock = true),
    )

    @Test
    fun theFourSingleValueSections_sayTheirValue_theRestSayNothing() {
        assertThat(SettingsPage.entries.associateWith { indexValueFor(it, state) }).containsExactly(
            SettingsPage.Notifications, "On",
            SettingsPage.Organise, null,
            SettingsPage.Data, null,
            SettingsPage.Security, "Phone's lock",
            SettingsPage.Appearance, "Dark",
            SettingsPage.Region, "INR",
            SettingsPage.Tools, null,
        )
    }

    @Test
    fun capture_saysOff_orStopped_andNothingWhileReconnecting() {
        fun capture(health: NotificationCaptureHealth) =
            indexValueFor(SettingsPage.Notifications, state.copy(captureHealth = health))
        assertThat(capture(NotificationCaptureHealth.NOT_GRANTED)).isEqualTo("Off")
        assertThat(capture(NotificationCaptureHealth.DEAD)).isEqualTo("Stopped")
        assertThat(capture(NotificationCaptureHealth.RECONNECTING)).isNull()
        assertThat(capture(NotificationCaptureHealth.UNAVAILABLE)).isNull()
    }

    @Test
    fun appLockOff_saysOff() {
        assertThat(indexValueFor(SettingsPage.Security, state.copy(security = SecurityPreferences(appLock = false))))
            .isEqualTo("Off")
    }

    @Test
    fun noBaseCurrencyYet_saysNothing() {
        assertThat(indexValueFor(SettingsPage.Region, state.copy(baseCurrency = null))).isNull()
    }
}
