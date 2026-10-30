package com.ledgerflow.core.designsystem.component

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertWithMessage
import com.ledgerflow.core.designsystem.theme.LfTheme
import org.junit.Rule
import kotlin.math.ceil
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * BUG80, found by Lock after's first golden (2026-10-30): at font scale 1.0
 * its four options stayed on one row in the dialog and "Immediately" and
 * "15 minutes" were cut off. The control chose a row from a fixed 72 dp a
 * option, not from its labels, and a label that does not fit is clipped
 * (BUG9). It measures its widest label now, and stacks when that would not fit.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w360dp-h800dp")
class Bug80_SegmentLabelsNeverClipTest {

    @get:Rule
    val rule = createComposeRule()

    @Test
    fun fourLongLabels_inADialogsWidth_areNeverClipped() {
        val labels = listOf("Immediately", "1 minute", "5 minutes", "15 minutes")
        rule.setContent {
            LfTheme {
                // Lock after's dialog content width on a 360 dp phone, measured
                // from its golden: the old rule put four options on one row here.
                Box(Modifier.width(DIALOG_CONTENT)) {
                    LfSegmentedControl(options = labels, selectedIndex = 2, onSelect = {})
                }
            }
        }
        labels.forEach { label ->
            val layouts = mutableListOf<TextLayoutResult>()
            rule.onNodeWithText(label).fetchSemanticsNode()
                .config[SemanticsActions.GetTextLayoutResult].action?.invoke(layouts)
            // The drawn width against the label's own: a clipped label is laid
            // out narrower than it is. (`hasVisualOverflow` misreports for
            // centred text that does not wrap, so it cannot be the check.)
            val layout = layouts.single()
            val natural = ceil(layout.multiParagraph.intrinsics.maxIntrinsicWidth).toInt()
            assertWithMessage("\"$label\" is clipped").that(layout.size.width).isAtLeast(natural)
        }
    }

    private companion object {
        val DIALOG_CONTENT = 312.dp
    }
}
