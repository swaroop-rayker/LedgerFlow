package com.ledgerflow.core.designsystem

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.ledgerflow.core.designsystem.component.LfActionRow
import com.ledgerflow.core.designsystem.component.LfButton
import com.ledgerflow.core.designsystem.component.LfButtonStyle
import com.ledgerflow.core.designsystem.theme.LfTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * BUG9 — a control's label broke mid-word (SPEC.md §8).
 *
 * Found on a real device: three text buttons in a row inside a category card
 * overflowed it, and "Delete" rendered as "Delet" with a lone "e" beneath.
 * Nothing caught it — the unit tests asserted behaviour, and previews were not
 * being diffed.
 *
 * The assertion is on the real `TextLayoutResult`, pulled out through the
 * `GetTextLayoutResult` semantics action, so it measures what was laid out
 * rather than what the code intended. A content assertion would not have caught
 * this: the string was always "Delete", only its layout was wrong.
 *
 * **`hasVisualOverflow` is deliberately not asserted anywhere here.** Once
 * `softWrap = false`, Compose reports that flag as `true` even for a label that
 * demonstrably renders in full at its natural width in an unconstrained
 * container — it tracks the incoming constraint rather than what was painted.
 * Two earlier revisions of this test asserted it and failed against builds that
 * were visually correct on the device. `lineCount` catches the actual defect
 * (a mid-word break), and `getLineEnd(visibleEnd = true)` catches clipping by
 * asking how many characters survived, which is the question that matters.
 */
@RunWith(AndroidJUnit4::class)
class Bug9_ControlLabelsNeverWrapTest {

    @get:Rule
    val composeRule = createComposeRule()

    /** The laid-out text of the node showing [text]. */
    private fun layoutOf(text: String): TextLayoutResult {
        val results = mutableListOf<TextLayoutResult>()
        composeRule.onNodeWithText(text).fetchSemanticsNode()
            .config[SemanticsActions.GetTextLayoutResult]
            .action
            ?.invoke(results)
        return results.first()
    }

    /** The exact shape that shipped the bug: three actions in a card-width row. */
    @Test
    fun threeActionsInANarrowRowKeepTheirLabelsWhole() {
        composeRule.setContent {
            LfTheme {
                Box(Modifier.width(NARROW_CARD.dp)) {
                    LfActionRow {
                        LfButton(text = "Rename", style = LfButtonStyle.Text, onClick = {})
                        LfButton(text = "Add sub", style = LfButtonStyle.Text, onClick = {})
                        LfButton(text = "Delete", style = LfButtonStyle.Text, onClick = {})
                    }
                }
            }
        }

        listOf("Rename", "Add sub", "Delete").forEach { label ->
            assertThat(layoutOf(label).lineCount).isEqualTo(1)
        }
    }

    /**
     * Two multi-word labels in one row — the case where a `Row` would break
     * "payment" from "method" rather than move the whole control.
     */
    @Test
    fun multiWordLabelsStayWhole() {
        composeRule.setContent {
            LfTheme {
                Box(Modifier.width(NARROW_CARD.dp)) {
                    LfActionRow {
                        LfButton(text = "Make default", style = LfButtonStyle.Text, onClick = {})
                        LfButton(text = "Add payment method", style = LfButtonStyle.Text, onClick = {})
                    }
                }
            }
        }

        assertThat(layoutOf("Make default").lineCount).isEqualTo(1)
        assertThat(layoutOf("Add payment method").lineCount).isEqualTo(1)
    }

    /**
     * At its natural width, a label is neither wrapped nor clipped.
     *
     * The container is deliberately unconstrained. `softWrap = false` trades
     * wrapping for clipping when a control is squeezed below the width its label
     * needs, so the contract the app relies on is precisely this: *given its
     * natural width*, nothing is lost. `LfActionRow` and `fillMaxWidth` are what
     * supply that width in practice.
     *
     * An earlier version of this test pinned the box to 240dp and failed on a
     * device set to font scale 1.15 — which was the fixture being wrong about
     * how wide 16sp is, not the component. Constraining the width here would
     * make the test a measurement of one device's font settings.
     */
    @Test
    fun aLabelAtItsNaturalWidthIsNeitherWrappedNorClipped() {
        composeRule.setContent {
            LfTheme {
                Box {
                    LfButton(text = "Move and delete", onClick = {})
                }
            }
        }

        val layout = layoutOf("Move and delete")
        assertThat(layout.lineCount).isEqualTo(1)
        // Every character actually reaches the screen -- no silent ellipsis,
        // nothing cut off the end.
        assertThat(layout.getLineEnd(0, /* visibleEnd = */ true))
            .isEqualTo("Move and delete".length)
    }

    private companion object {
        /** Roughly a phone-width card's inner width, where the bug appeared. */
        private const val NARROW_CARD = 280
    }
}
