package com.ledgerflow.core.ui.picker

import androidx.compose.runtime.Composable
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.takahirom.roborazzi.ExperimentalRoborazziApi
import com.github.takahirom.roborazzi.captureScreenRoboImage
import com.google.common.truth.Truth.assertThat
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
 * The merchant picker's browse by merchant category (V2-6 PR 3): headings over
 * their rows, Uncategorised last on the muted swatch, the chosen merchant
 * ticked inside its group. Goldens at 1x and 2x in both themes; a dialog is
 * its own window, so these capture the screen rather than the root.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [GOLDEN_SDK], qualifiers = PHONE_1X)
open class LfPickerDialogGroupsTest {

    @get:Rule
    val rule = createComposeRule()

    private val groups = listOf(
        LfPickerGroup(
            "Groceries",
            0xFF2F7E50.toInt(),
            listOf(LfPickerOption("bb", "Big Bazaar"), LfPickerOption("cg", "Corner Grocer")),
        ),
        LfPickerGroup(
            "Restaurants & caf" + Char(0x00E9) + "s",
            0xFF207E6E.toInt(),
            listOf(LfPickerOption("sw", "Swiggy")),
        ),
        LfPickerGroup(
            "Uncategorised",
            null,
            listOf(LfPickerOption("st", "Studio North"), LfPickerOption("ze", "Zepto")),
        ),
    )

    @Composable
    private fun Picker(onSelect: (String?) -> Unit = {}) {
        LfTheme {
            LfPickerDialog(
                title = "Merchant",
                body = "Where the money went.",
                options = groups.flatMap { it.options },
                selectedId = "cg",
                onSelect = onSelect,
                onDismiss = {},
                // Without the search field, which the entry form and review
                // screen draw above these rows unchanged: under Robolectric a
                // text field in a dialog never lets Compose go idle (with or
                // without groups, so not this change), and these goldens are
                // of the list. The field is checked on the phone.
                query = null,
                groups = groups,
            )
        }
    }

    /**
     * A dialog is its own window, built from the device configuration, so
     * font scale is set there: provided as a composition local around the
     * dialog, as `captureScreenGolden` does for a screen, it never reaches it,
     * and the 2x capture came out identical to 1x.
     */
    @OptIn(ExperimentalRoborazziApi::class)
    private fun capture(name: String, fontScale: Float) {
        RuntimeEnvironment.setFontScale(fontScale)
        rule.setContent { GoldenTheme.Provide { Picker() } }
        rule.waitForIdle()
        captureScreenRoboImage(
            "$GOLDEN_DIR/${GoldenTheme.fileName(name)}.png",
            roborazziOptions = LfScreenshotOptions,
        )
    }

    @Test fun browse_1x() = capture("picker-browse-1x", 1f)

    @Config(qualifiers = PHONE_2X)
    @Test fun browse_2x() = capture("picker-browse-2x", 2f)

    // No RTL golden: under Robolectric a dialog window takes RTL only when the
    // test app declares `supportsRtl`, which this module's does not, so one
    // came out identical to 1x. The heading is a plain Row that mirrors by
    // itself, and Organise's `organise-merchants-rtl-1x` covers the same
    // heading in RTL.

    /** Headings are headings to TalkBack, and only the rows choose. */
    @Test
    fun headingsAreHeadings_andARowChooses() {
        var chosen: String? = "none"
        rule.setContent { Picker(onSelect = { chosen = it }) }

        val heading = SemanticsMatcher.keyIsDefined(SemanticsProperties.Heading)
        rule.onAllNodesWithText("Groceries").assertCountEquals(1)
        assertThat(rule.onAllNodes(heading, useUnmergedTree = false).fetchSemanticsNodes()).hasSize(groups.size)

        rule.onNodeWithText("Swiggy").performClick()
        assertThat(chosen).isEqualTo("sw")
    }
}

/** The same goldens with the phone in dark mode, saved with a `-dark` suffix (DESIGN_SYSTEM_PLAN §6.3). */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class LfPickerDialogGroupsDarkTest : LfPickerDialogGroupsTest() {
    @get:Rule
    val darkGoldens: DarkGoldensRule = DarkGoldensRule()
}
