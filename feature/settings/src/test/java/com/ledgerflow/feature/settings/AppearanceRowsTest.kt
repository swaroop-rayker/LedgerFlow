package com.ledgerflow.feature.settings

import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import com.ledgerflow.core.designsystem.theme.LfTheme
import com.ledgerflow.core.domain.appearance.AnimationPreference
import com.ledgerflow.core.domain.appearance.AppearancePreferences
import com.ledgerflow.core.domain.appearance.StartScreen
import com.ledgerflow.core.domain.appearance.ThemePreference
import com.ledgerflow.core.testing.screenshot.GOLDEN_SDK
import com.ledgerflow.core.testing.screenshot.PHONE_1X
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Settings > Appearance (V2-PLAN §3.2): each option sends its own choice, the
 * stored choice is the one shown selected, and each row's sentence says what
 * the choice means.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [GOLDEN_SDK], qualifiers = PHONE_1X)
class AppearanceRowsTest {

    @get:Rule
    val rule = createComposeRule()

    private val events = mutableListOf<SettingsEvent>()

    private fun show(state: SettingsUiState) {
        rule.setContent {
            LfTheme {
                SettingsSectionScreen(
                    page = SettingsPage.Appearance,
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
                    onAbout = {},
                    onEvent = { events += it },
                )
            }
        }
    }

    @Test
    fun everyOption_sendsItsOwnChoice() {
        show(SettingsUiState(isLoaded = true))
        val expected = linkedMapOf<String, SettingsEvent>(
            "System" to SettingsEvent.ThemePicked(ThemePreference.SYSTEM),
            "Light" to SettingsEvent.ThemePicked(ThemePreference.LIGHT),
            "Dark" to SettingsEvent.ThemePicked(ThemePreference.DARK),
            "Full" to SettingsEvent.AnimationsPicked(AnimationPreference.FULL),
            "Reduced" to SettingsEvent.AnimationsPicked(AnimationPreference.REDUCED),
            "Off" to SettingsEvent.AnimationsPicked(AnimationPreference.OFF),
            "Home" to SettingsEvent.StartScreenPicked(StartScreen.HOME),
            "Ledger" to SettingsEvent.StartScreenPicked(StartScreen.LEDGER),
            "Analytics" to SettingsEvent.StartScreenPicked(StartScreen.ANALYTICS),
        )
        expected.forEach { (label, event) ->
            events.clear()
            rule.onNodeWithText(label).performScrollTo().performClick()
            rule.waitForIdle()
            assertWithMessage("tapping \"$label\"").that(events).containsExactly(event)
        }
    }

    @Test
    fun theStoredChoice_isTheOneSelected() {
        show(
            SettingsUiState(
                isLoaded = true,
                appearance = AppearancePreferences(
                    theme = ThemePreference.DARK,
                    animations = AnimationPreference.REDUCED,
                    startScreen = StartScreen.ANALYTICS,
                ),
            ),
        )
        listOf("Dark", "Reduced", "Analytics").forEach { rule.onNodeWithText(it).assertIsSelected() }
        listOf("System", "Light", "Full", "Off", "Home", "Ledger").forEach {
            rule.onNodeWithText(it).assertIsNotSelected()
        }
    }

    @Test
    fun theSentences_sayWhatEachChoiceMeans() {
        assertThat(themeSubtitle(ThemePreference.SYSTEM)).isEqualTo("Light or dark, as your phone is set.")
        assertThat(themeSubtitle(ThemePreference.DARK)).isEqualTo("Always dark.")
        assertThat(animationsSubtitle(withAnimations(AnimationPreference.REDUCED))).isEqualTo(
            "Screens fade. Nothing slides.",
        )
        assertThat(animationsSubtitle(withAnimations(AnimationPreference.OFF))).contains("Nothing moves")
        // The start screen applies from the next launch, and says so.
        assertThat(subtitleFor(SettingsItem.StartScreen, SettingsUiState())).contains("next time")
    }

    @Test
    fun aPhoneWithAnimationsRemoved_isNamedAsTheReason_whateverIsPicked() {
        // Otherwise "Full" with nothing moving looks like a broken setting.
        AnimationPreference.entries.forEach { choice ->
            val state = withAnimations(choice).copy(phoneAnimationsOn = false)
            assertWithMessage("$choice on a phone with animations removed")
                .that(animationsSubtitle(state))
                .isEqualTo("Your phone has animations removed, so nothing here moves either.")
        }
    }

    private fun withAnimations(choice: AnimationPreference) =
        SettingsUiState(appearance = AppearancePreferences(animations = choice))
}
