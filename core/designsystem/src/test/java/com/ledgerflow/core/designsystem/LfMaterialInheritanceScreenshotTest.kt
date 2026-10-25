package com.ledgerflow.core.designsystem

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DatePicker
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.takahirom.roborazzi.captureRoboImage
import com.ledgerflow.core.designsystem.theme.LfTheme
import com.ledgerflow.core.testing.screenshot.DarkGoldensRule
import com.ledgerflow.core.testing.screenshot.GOLDEN_DIR
import com.ledgerflow.core.testing.screenshot.GoldenTheme
import com.ledgerflow.core.testing.screenshot.LfScreenshotOptions
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Material's own widgets, drawn under `LfTheme` with nothing passed but the
 * theme (DESIGN_SYSTEM_PLAN §6.4, step A2).
 *
 * These are the widgets that read Material's colour roles directly: the date
 * picker (Budgets' start date and Analytics' range use it with no colours of
 * their own, and came out lavender), and the switch, checkbox and radio button.
 * The off switch is BUG63. If a Material upgrade adds a role these draw with,
 * or a change unmaps one, the golden shows it in both themes.
 *
 * The picker is captured inline rather than inside `DatePickerDialog`:
 * Roborazzi captures the test's own window, and a dialog is a window of its
 * own. The dialog's container is the same `surfaceContainerHigh` this picker
 * draws on, which `LfMaterialThemeTest` pins to `surfaceOverlay`.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [INHERITANCE_ROBOLECTRIC_SDK], qualifiers = INHERITANCE_WINDOW)
open class LfMaterialInheritanceScreenshotTest {

    @get:Rule
    val composeRule = createComposeRule()

    private fun capture(name: String, width: Int, content: @Composable () -> Unit) {
        composeRule.setContent {
            GoldenTheme.Provide {
                LfTheme {
                    Column(
                        modifier = Modifier
                            .width(width.dp)
                            .background(LfTheme.colors.surfaceBase)
                            .padding(LfTheme.spacing.md),
                    ) { content() }
                }
            }
        }
        composeRule.onRoot().captureRoboImage(
            "$GOLDEN_DIR/${GoldenTheme.fileName(name)}.png",
            roborazziOptions = LfScreenshotOptions,
        )
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Test
    fun datePicker() = capture("material-date-picker", PICKER_WIDTH) {
        DatePicker(state = rememberDatePickerState(initialSelectedDateMillis = SELECTED_DAY_MILLIS))
    }

    @Test
    fun selectionControls() = capture("material-selection-controls", CONTROLS_WIDTH) {
        Column(
            modifier = Modifier.background(LfTheme.colors.surfaceRaised).padding(LfTheme.spacing.sm),
            verticalArrangement = Arrangement.spacedBy(LfTheme.spacing.xs),
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(LfTheme.spacing.md)) {
                Switch(checked = false, onCheckedChange = null)
                Switch(checked = true, onCheckedChange = null)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(LfTheme.spacing.md)) {
                Checkbox(checked = false, onCheckedChange = null)
                Checkbox(checked = true, onCheckedChange = null)
                RadioButton(selected = false, onClick = null)
                RadioButton(selected = true, onClick = null)
            }
        }
    }
}

/** The same goldens with the phone in dark mode, saved with a `-dark` suffix (DESIGN_SYSTEM_PLAN §6.3). */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class LfMaterialInheritanceScreenshotDarkTest : LfMaterialInheritanceScreenshotTest() {
    @get:Rule
    val darkGoldens: DarkGoldensRule = DarkGoldensRule()
}

private const val INHERITANCE_ROBOLECTRIC_SDK = 34
/** Material's picker is 360 dp wide; Robolectric's default 320 dp window clipped it. */
private const val INHERITANCE_WINDOW = "w420dp-h900dp"

/** 360 dp of picker plus the 16 dp padding on each side. */
private const val PICKER_WIDTH = 392
private const val CONTROLS_WIDTH = 240

/** 19 Aug 2026, 00:00 UTC: the date the other goldens use. */
private const val SELECTED_DAY_MILLIS = 1_787_097_600_000L
