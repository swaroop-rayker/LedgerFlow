package com.ledgerflow.core.designsystem

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.ledgerflow.core.designsystem.component.LfSwitchRow
import com.ledgerflow.core.designsystem.theme.LfTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * The two things a labelled switch gets wrong, asserted.
 *
 * Both were found on device in the budget editor's rollover toggle, which was
 * a bare `Switch` in a `Row` — the only raw `Switch` the app had. Neither shows
 * up in a screenshot: the row looked right in both failure modes.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [SWITCH_ROBOLECTRIC_SDK])
class LfSwitchRowTest {

    @get:Rule
    val composeRule = createComposeRule()

    /**
     * **The label is the control.** Tapping it did nothing before, leaving a
     * 40dp target on a row that reads as one thing.
     */
    @Test
    fun tappingTheLabelTogglesTheSwitch() {
        composeRule.setContent { Subject() }

        composeRule.onNodeWithText(LABEL).assertIsOff()
        composeRule.onNodeWithText(LABEL).performClick()
        composeRule.onNodeWithText(LABEL).assertIsOn()
    }

    /**
     * **The switch carries its label's name.**
     *
     * With a bare `Switch` beside a `Text` the state and the words describing
     * it are two unrelated nodes: a screen reader reaches a switch with nothing
     * to say what would roll over. `toggleable` on the row merges them, and
     * this asserts the merge rather than assuming it — the label's node must
     * itself carry the toggleable state (§9.6).
     */
    @Test
    fun theToggleStateAndItsLabelAreOneNode() {
        composeRule.setContent { Subject() }

        val node = composeRule.onNodeWithText(LABEL).fetchSemanticsNode()

        assertThat(node.config.contains(SemanticsProperties.ToggleableState)).isTrue()
        assertThat(node.config[SemanticsProperties.ToggleableState])
            .isEqualTo(ToggleableState.Off)
    }

    @Composable
    private fun Subject() {
        LfTheme {
            var checked by remember { mutableStateOf(false) }
            LfSwitchRow(
                label = LABEL,
                checked = checked,
                onCheckedChange = { checked = it },
            )
        }
    }

    private companion object {
        const val LABEL = "Roll over what is left"
    }
}

/** Matches the other Robolectric suites in this module. */
private const val SWITCH_ROBOLECTRIC_SDK = 34
