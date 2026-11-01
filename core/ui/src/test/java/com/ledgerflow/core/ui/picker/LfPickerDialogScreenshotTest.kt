package com.ledgerflow.core.ui.picker

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.takahirom.roborazzi.ExperimentalRoborazziApi
import com.github.takahirom.roborazzi.captureScreenRoboImage
import com.ledgerflow.core.designsystem.theme.LfTheme
import com.ledgerflow.core.testing.screenshot.DarkGoldensRule
import com.ledgerflow.core.testing.screenshot.GOLDEN_DIR
import com.ledgerflow.core.testing.screenshot.GOLDEN_SDK
import com.ledgerflow.core.testing.screenshot.GoldenTheme
import com.ledgerflow.core.testing.screenshot.LfScreenshotOptions
import com.ledgerflow.core.testing.screenshot.PHONE_1X
import com.ledgerflow.core.testing.screenshot.PHONE_2X
import androidx.compose.ui.test.junit4.createComposeRule
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The picker the entry form, the Inbox's review screen and the budget editor
 * share: a create row with its +, and the chosen row filled and ticked
 * (BUG83). The chosen name is long enough to wrap beside its tick at 2.0, the
 * case that would push a tick off the row. Goldens at 1x and 2x in both
 * themes; a dialog is its own window, so these capture the screen.
 *
 * Without the search field: under Robolectric a text field in a dialog never
 * lets Compose go idle (`LfPickerDialogGroupsTest` on the V2-6 branch found
 * the same), and these goldens are of the rows.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [GOLDEN_SDK], qualifiers = PHONE_1X)
open class LfPickerDialogScreenshotTest {

    @get:Rule
    val rule = createComposeRule()

    /**
     * Font scale is set on the device configuration: a dialog builds its own
     * window from it, and a composition local around the dialog never reaches
     * it.
     */
    @OptIn(ExperimentalRoborazziApi::class)
    private fun capture(name: String, fontScale: Float) {
        RuntimeEnvironment.setFontScale(fontScale)
        rule.setContent {
            GoldenTheme.Provide {
                LfTheme {
                    LfPickerDialog(
                        title = "Merchant",
                        body = "Where the money went.",
                        options = listOf(
                            LfPickerOption("bb", "Big Bazaar"),
                            LfPickerOption("cg", "Corner Grocer and Daily Needs"),
                            LfPickerOption("sw", "Swiggy"),
                            LfPickerOption("ze", "Zepto"),
                        ),
                        selectedId = "cg",
                        onSelect = {},
                        onDismiss = {},
                        createLabel = "Add \"Corner\"",
                        onCreate = {},
                    )
                }
            }
        }
        rule.waitForIdle()
        captureScreenRoboImage(
            "$GOLDEN_DIR/${GoldenTheme.fileName(name)}.png",
            roborazziOptions = LfScreenshotOptions,
        )
    }

    @Test fun choice_1x() = capture("picker-choice-1x", 1f)

    @Config(qualifiers = PHONE_2X)
    @Test fun choice_2x() = capture("picker-choice-2x", 2f)
}

/** The same goldens with the phone in dark mode, saved with a `-dark` suffix (DESIGN_SYSTEM_PLAN §6.3). */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class LfPickerDialogScreenshotDarkTest : LfPickerDialogScreenshotTest() {
    @get:Rule
    val darkGoldens: DarkGoldensRule = DarkGoldensRule()
}
