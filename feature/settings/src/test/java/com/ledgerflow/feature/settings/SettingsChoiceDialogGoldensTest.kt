package com.ledgerflow.feature.settings

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.takahirom.roborazzi.ExperimentalRoborazziApi
import com.github.takahirom.roborazzi.captureScreenRoboImage
import com.ledgerflow.core.designsystem.component.LfScaffold
import com.ledgerflow.core.designsystem.theme.LfTheme
import com.ledgerflow.core.testing.screenshot.DarkGoldensRule
import com.ledgerflow.core.testing.screenshot.GOLDEN_DIR
import com.ledgerflow.core.testing.screenshot.GOLDEN_SDK
import com.ledgerflow.core.testing.screenshot.GoldenTheme
import com.ledgerflow.core.testing.screenshot.LfScreenshotOptions
import com.ledgerflow.core.testing.screenshot.PHONE_1X
import com.ledgerflow.core.testing.screenshot.PHONE_2X
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The Lock after choice (Settings > Security & privacy), whose four options
 * stack in their dialog. Found by the owner on the device (2026-10-30): the
 * stacked track kept the one-row pill's fully round corners and drew a
 * capsule with ends half its width across. No golden had ever shown it.
 *
 * **Review the diff; never re-record blind** (`CLAUDE.md` §12).
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [GOLDEN_SDK], qualifiers = PHONE_1X)
open class SettingsChoiceDialogGoldensTest {

    @get:Rule
    val rule = createComposeRule()

    @Test fun lockAfter_1x() = withDialog("settings-lock-after-1x", 1f)

    @Config(qualifiers = PHONE_2X)
    @Test fun lockAfter_2x() = withDialog("settings-lock-after-2x", 2f)

    /**
     * A dialog is its own window: it ignores `captureScreenGolden`'s density
     * override, so the font scale goes through the configuration and the
     * whole screen is captured, as the Budgets sheet's goldens are.
     */
    @OptIn(ExperimentalRoborazziApi::class)
    private fun withDialog(name: String, fontScale: Float) {
        if (fontScale != 1f) RuntimeEnvironment.setFontScale(fontScale)
        rule.setContent {
            GoldenTheme.Provide {
                LfTheme {
                    LfScaffold {
                        SettingsSectionScreen(
                            page = SettingsPage.Security,
                            state = SettingsUiState(isLoaded = true, choosingLockAfter = true),
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
                            onEvent = {},
                        )
                    }
                }
            }
        }
        rule.waitForIdle()
        captureScreenRoboImage(
            "$GOLDEN_DIR/${GoldenTheme.fileName(name)}.png",
            roborazziOptions = LfScreenshotOptions,
        )
    }
}

/** The same goldens with the phone in dark mode, saved with a `-dark` suffix (DESIGN_SYSTEM_PLAN §6.3). */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SettingsChoiceDialogGoldensDarkTest : SettingsChoiceDialogGoldensTest() {
    @get:Rule
    val darkGoldens: DarkGoldensRule = DarkGoldensRule()
}
