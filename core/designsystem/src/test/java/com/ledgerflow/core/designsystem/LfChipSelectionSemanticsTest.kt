package com.ledgerflow.core.designsystem

import androidx.compose.runtime.Composable
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.ledgerflow.core.designsystem.component.LfChip
import com.ledgerflow.core.designsystem.component.LfChipStyle
import com.ledgerflow.core.designsystem.theme.LfTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * SPEC Q21 — a chip's selection was a colour and nothing else.
 *
 * `LfChipStyle.Selected` changed the palette and set no semantics, so every
 * chip in the app exposed the same state whether on or off. Found in a
 * `uiautomator` dump of the budget editor, where all four period chips reported
 * `selected="false"` while **Weekly** was visibly outlined. It reached the
 * analytics range chips and the budget pickers alike: a screen reader could not
 * tell which range a figure covered.
 *
 * **A screenshot cannot catch this** — the picture was always right. Only an
 * assertion on the semantics node can, which is why it lives here rather than
 * in a golden.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [CHIP_ROBOLECTRIC_SDK])
class LfChipSelectionSemanticsTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun aSelectedChipReportsThatItIsSelected() {
        composeRule.setContent { Chips() }

        assertThat(selectedFlagOf(SELECTED)).isTrue()
    }

    @Test
    fun anUnselectedChoiceReportsThatItIsNot() {
        composeRule.setContent { Chips() }

        assertThat(selectedFlagOf(UNSELECTED)).isFalse()
    }

    /**
     * **A chip that is not a choice carries no selection state at all.**
     *
     * The Recovery screen shows the twenty-four words as chips with no
     * `onClick`. Marking those "not selected" would announce a state they do
     * not have, twenty-four times, on the one screen where every word matters.
     */
    @Test
    fun anonClickableChipHasNoSelectionStateToReport() {
        composeRule.setContent { Chips() }

        assertThat(selectedFlagOf(LABEL_ONLY)).isNull()
    }

    private fun selectedFlagOf(text: String): Boolean? {
        val config = composeRule.onNodeWithText(text).fetchSemanticsNode().config
        return if (config.contains(SemanticsProperties.Selected)) {
            config[SemanticsProperties.Selected]
        } else {
            null
        }
    }

    @Composable
    private fun Chips() {
        LfTheme {
            androidx.compose.foundation.layout.Column {
                LfChip(label = SELECTED, style = LfChipStyle.Selected, onClick = {})
                LfChip(label = UNSELECTED, style = LfChipStyle.Assist, onClick = {})
                LfChip(label = LABEL_ONLY, style = LfChipStyle.Assist)
            }
        }
    }

    private companion object {
        const val SELECTED = "Weekly"
        const val UNSELECTED = "Monthly"
        const val LABEL_ONLY = "abandon"
    }
}

/** Matches the other Robolectric suites in this module. */
private const val CHIP_ROBOLECTRIC_SDK = 34
