package com.ledgerflow.feature.settings

import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.ledgerflow.core.designsystem.theme.LfTheme
import com.ledgerflow.core.domain.security.LockTimeout
import com.ledgerflow.core.domain.security.SecurityPreferences
import com.ledgerflow.core.testing.screenshot.GOLDEN_SDK
import com.ledgerflow.core.testing.screenshot.PHONE_1X
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Settings > Security & privacy (V2-PLAN §3.5, SPEC §7.6): each switch shows
 * and sends its own value, Lock after opens its picker, and each sentence says
 * what the setting does -- or, without a phone screen lock, why the app lock
 * cannot be turned on.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [GOLDEN_SDK], qualifiers = PHONE_1X)
class SecurityRowsTest {

    @get:Rule
    val rule = createComposeRule()

    private val events = mutableListOf<SettingsEvent>()

    private fun show(state: SettingsUiState) {
        rule.setContent {
            LfTheme {
                SettingsSectionScreen(
                    page = SettingsPage.Security,
                    state = state,
                    onBack = {},
                    onCategories = {},
                    onBudgets = {},
                    onExport = {},
                    onDeletedEntries = {},
                    onNotificationAccess = {},
                    onBackUp = {},
                    onDiagnostics = {},
                    onBudgetAlerts = {},
                    onPaymentApps = {},
                    onDataPermissions = {},
                    onEvent = { events += it },
                )
            }
        }
    }

    @Test
    fun theSwitches_showAndSendTheirValues() {
        show(SettingsUiState(isLoaded = true, security = SecurityPreferences(appLock = true)))
        rule.onNodeWithText("App lock").performScrollTo().assertIsOn()
        rule.onNodeWithText("Block screenshots").performScrollTo().assertIsOff()
        rule.onNodeWithText("Hide amounts on Home").performScrollTo().assertIsOff()

        rule.onNodeWithText("App lock").performClick()
        rule.onNodeWithText("Block screenshots").performClick()
        rule.onNodeWithText("Hide amounts on Home").performClick()
        assertThat(events).containsExactly(
            SettingsEvent.AppLockToggled(false),
            SettingsEvent.BlockScreenshotsToggled(true),
            SettingsEvent.HideAmountsToggled(true),
        ).inOrder()
    }

    @Test
    fun lockAfter_opensItsPicker_andAPickIsSent() {
        show(SettingsUiState(isLoaded = true))
        rule.onNodeWithText("Lock after").performScrollTo().performClick()
        assertThat(events).containsExactly(SettingsEvent.LockAfterRequested)
    }

    @Test
    fun thePicker_offersTheOwnersFour() {
        show(SettingsUiState(isLoaded = true, choosingLockAfter = true))
        rule.onNodeWithText("15 minutes").performClick()
        assertThat(events).containsExactly(SettingsEvent.LockAfterPicked(LockTimeout.FIFTEEN_MINUTES))
        assertThat(LockTimeout.entries.map { it.label })
            .containsExactly("Immediately", "1 minute", "5 minutes", "15 minutes").inOrder()
    }

    @Test
    fun withoutAPhoneScreenLock_theSentenceSaysWhatToDo() {
        assertThat(appLockSubtitle(SettingsUiState(hasScreenLock = false)))
            .isEqualTo("Set a screen lock on your phone first, then turn this on.")
        assertThat(appLockSubtitle(SettingsUiState(security = SecurityPreferences(appLock = true))))
            .startsWith("On.")
    }

    @Test
    fun lockAfter_saysItWaitsForTheLock_whenTheLockIsOff() {
        assertThat(lockAfterSubtitle(SecurityPreferences(appLock = false)))
            .isEqualTo("When app lock is on: after 5 minutes away.")
        assertThat(lockAfterSubtitle(SecurityPreferences(appLock = true, lockAfter = LockTimeout.IMMEDIATELY)))
            .isEqualTo("As soon as you leave LedgerFlow.")
    }

    /** "Hide" alone could mean everywhere, or for good; the sentence says Home, and how they come back. */
    @Test
    fun hideAmounts_saysWhereAndHowTheyComeBack() {
        assertThat(hideAmountsSubtitle(false)).isEqualTo("Show dots in place of amounts on Home until you tap them.")
        assertThat(hideAmountsSubtitle(true)).isEqualTo(
            "On. Home shows dots until you tap one. Other screens show amounts.",
        )
    }

    @Test
    fun dataPermissions_opensItsPage() {
        var opened = 0
        rule.setContent {
            LfTheme {
                SettingsSectionScreen(
                    page = SettingsPage.Security,
                    state = SettingsUiState(isLoaded = true),
                    onBack = {},
                    onCategories = {},
                    onBudgets = {},
                    onExport = {},
                    onDeletedEntries = {},
                    onNotificationAccess = {},
                    onBackUp = {},
                    onDiagnostics = {},
                    onBudgetAlerts = {},
                    onPaymentApps = {},
                    onDataPermissions = { opened++ },
                    onEvent = { events += it },
                )
            }
        }
        rule.onNodeWithText("Data permissions").performScrollTo().performClick()
        assertThat(opened).isEqualTo(1)
        assertThat(events).isEmpty()
    }

    @Test
    fun blockScreenshots_namesWhatItStops() {
        assertThat(blockScreenshotsSubtitle(false)).contains("screenshots, recordings and Recents previews")
    }
}
