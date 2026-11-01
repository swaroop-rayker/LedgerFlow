package com.ledgerflow.core.ui.picker

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.ledgerflow.core.designsystem.theme.LfTheme
import com.ledgerflow.core.testing.screenshot.GOLDEN_SDK
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.math.abs

/**
 * BUG83, found 2026-11-01 by V2-6 PR 3's `picker-browse-*` goldens: the
 * picker's chosen row was drawn in `action.primary`, which Night Vault makes
 * the same colour as `text.primary` in both themes. "Corner Grocer" was
 * selected and looked exactly like its neighbours, in the entry form, the
 * Inbox's review screen and the budget editor alike. TalkBack could not tell
 * either: the row was a clickable text with no selected state.
 *
 * The owner chose a fill and a tick (2026-11-01). This holds the parts no
 * golden can: that the chosen row is *selected* to an accessibility service,
 * that its fill differs from an unchosen row's in both themes, and that the
 * fill reaching past the label does not move the label off the title's line.
 * The tick is in the goldens (`LfPickerDialogScreenshotTest`).
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [GOLDEN_SDK])
class Bug83_PickerShowsWhichRowIsChosenTest {

    @get:Rule
    val rule = createComposeRule()

    private fun rows(dark: Boolean) {
        rule.setContent {
            LfTheme(darkTheme = dark) {
                Column(
                    modifier = Modifier
                        .width(WIDTH.dp)
                        .testTag(LIST)
                        .background(LfTheme.colors.surface.overlay)
                        .padding(PADDING.dp),
                ) {
                    LfChoiceRow(label = "Add \"Corner\"", selected = false, emphasised = true, onClick = {})
                    LfChoiceRow(label = "Big Bazaar", selected = false, onClick = {})
                    LfChoiceRow(label = "Corner Grocer", selected = true, onClick = {})
                }
            }
        }
    }

    @Test
    fun theChosenRowIsSelectedToTalkBack_andTheOthersAreNot() {
        rows(dark = true)
        val radio = SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.RadioButton)

        rule.onNodeWithText("Corner Grocer").assertIsSelected().assert(radio)
        rule.onNodeWithText("Big Bazaar").assertIsNotSelected().assert(radio)
        // The create row is an action: a button, never "not selected".
        rule.onNodeWithText("Add \"Corner\"")
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button))
            .assert(SemanticsMatcher.keyNotDefined(SemanticsProperties.Selected))
    }

    @Test fun theChosenRowIsFilled_dark() = chosenRowIsFilled(dark = true)

    @Test fun theChosenRowIsFilled_light() = chosenRowIsFilled(dark = false)

    private fun chosenRowIsFilled(dark: Boolean) {
        rows(dark)
        val chosen = rule.onNodeWithText("Corner Grocer").captureToImage().toPixelMap()
        val other = rule.onNodeWithText("Big Bazaar").captureToImage().toPixelMap()
        assertThat(chosen.width).isEqualTo(other.width)

        // Three quarters across, two pixels down: clear of the label, the
        // tick and the fill's rounded corners.
        val x = chosen.width * 3 / 4
        val y = 2
        val a = chosen[x, y]
        val b = other[x, y]
        val difference = maxOf(abs(a.red - b.red), abs(a.green - b.green), abs(a.blue - b.blue))
        assertThat(difference).isGreaterThan(MIN_VISIBLE_DIFFERENCE)
    }

    @Test
    fun theLabelStaysInLineWithTheDialogsContent() {
        rows(dark = true)
        val list = rule.onNodeWithTag(LIST).fetchSemanticsNode().boundsInRoot
        val label = rule.onNodeWithText("Corner Grocer", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        val padding = PADDING * rule.density.density

        assertThat(label.left - list.left).isWithin(EPSILON_PX).of(padding)
    }
}

private const val WIDTH = 312
private const val PADDING = 24
private const val LIST = "list"
private const val EPSILON_PX = 0.5f

/** More than anti-aliasing (`ANTI_ALIASING_TOLERANCE`, 8/255): a fill a reader can see. */
private const val MIN_VISIBLE_DIFFERENCE = 24f / 255f
