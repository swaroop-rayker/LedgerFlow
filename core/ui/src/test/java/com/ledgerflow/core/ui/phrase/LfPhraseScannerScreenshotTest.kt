package com.ledgerflow.core.ui.phrase

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.takahirom.roborazzi.captureRoboImage
import com.google.common.truth.Truth.assertWithMessage
import com.ledgerflow.core.designsystem.theme.LfTheme
import com.ledgerflow.core.ui.lineitem.LfScreenshotOptions
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The scanner's controls card (ADR-0028), at font scale 1.0 and 2.0.
 *
 * The camera cannot run here; the card is what the user reads and taps, and it
 * is what broke. Found on the owner's phone (2026-09-26): the first inset fix
 * put the button over the instruction, because `LfCard` stacks its children.
 * **The layout is asserted, not just photographed** — the instruction must end
 * above the button — and the goldens are reviewed, never blind-recorded
 * (`CLAUDE.md` §12).
 *
 * The card's other half — sitting above the navigation bar (§8 BUG35) — needs
 * real window insets, which Robolectric reports as zero; it is measured on the
 * device instead (`TESTING.md` D5).
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [SCANNER_ROBOLECTRIC_SDK])
class LfPhraseScannerScreenshotTest {

    @get:Rule
    val composeRule = createComposeRule()

    private fun show(fontScale: Float, granted: Boolean) {
        composeRule.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale)) {
                LfTheme {
                    Column(modifier = Modifier.width(NARROW.dp).padding(LfTheme.spacing.sm)) {
                        ScannerControls(granted = granted, onDismiss = {})
                    }
                }
            }
        }
    }

    private fun capture(name: String) {
        composeRule.onRoot().captureRoboImage("$GOLDEN_DIR/$name.png", roborazziOptions = LfScreenshotOptions)
    }

    private fun assertInstructionAboveButton(instruction: String) {
        val text = composeRule.onNodeWithText(instruction, substring = true).getBoundsInRoot()
        val button = composeRule.onNodeWithText(BUTTON).getBoundsInRoot()
        assertWithMessage("instruction bottom ${text.bottom} vs button top ${button.top}")
            .that(text.bottom.value).isAtMost(button.top.value)
    }

    /**
     * BUG9 on this card: the label is laid out whole and never wraps, so a label
     * wider than its button is clipped ("Type the words i" at 2.0 with the first
     * wording). Its own text node must fit inside the button.
     */
    private fun assertLabelFitsItsButton() {
        // Neither bounds nor `didOverflowWidth` shows it: the node reports the
        // width it was given, and `didOverflowWidth` is true for any label
        // narrower than its slot (measured: 117 of 224 read as "overflowing").
        // What the text needs against what it is given is the real question.
        val node = composeRule.onNodeWithText(BUTTON, useUnmergedTree = true).fetchSemanticsNode()
        val layouts = mutableListOf<TextLayoutResult>()
        node.config[SemanticsActions.GetTextLayoutResult].action?.invoke(layouts)
        val layout = layouts.single()
        assertWithMessage("the \"$BUTTON\" label needs more width than its button gives it")
            .that(layout.multiParagraph.intrinsics.maxIntrinsicWidth)
            .isAtMost(layout.layoutInput.constraints.maxWidth.toFloat())
    }

    @Test
    fun scanning_atDefaultScale() {
        show(fontScale = 1f, granted = true)
        assertInstructionAboveButton("Point the camera")
        assertLabelFitsItsButton()
        capture("scanner-controls-1x")
    }

    @Test
    fun scanning_atLargestSupportedScale() {
        show(fontScale = 2f, granted = true)
        assertInstructionAboveButton("Point the camera")
        assertLabelFitsItsButton()
        capture("scanner-controls-2x")
    }

    @Test
    fun noCamera_atLargestSupportedScale() {
        show(fontScale = 2f, granted = false)
        assertInstructionAboveButton("Scanning needs the camera")
        assertLabelFitsItsButton()
    }
}

private const val SCANNER_ROBOLECTRIC_SDK = 34
private const val GOLDEN_DIR = "src/test/screenshots"
private const val BUTTON = "Type the words"

/** A narrow phone, the width at which a wrapped instruction first matters. */
private const val NARROW = 320
