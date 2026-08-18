package com.ledgerflow.core.designsystem

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.ledgerflow.core.designsystem.component.LfAmountField
import com.ledgerflow.core.designsystem.theme.LfTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * BUG9's contract on the amount field, plus the field's own typing behaviour.
 *
 * An amount is the one string on the screen that must never break: a wrapped
 * "1,24,000.00" is not a slightly awkward number, it is an unreadable one.
 *
 * Measured through the real `TextLayoutResult`, as `Bug9_ControlLabelsNeverWrapTest`
 * establishes -- and for the same reason `hasVisualOverflow` is not asserted
 * anywhere here: with `softWrap = false` it tracks the incoming constraint
 * rather than what was painted, and reports `true` for labels that render fine.
 */
@RunWith(AndroidJUnit4::class)
class AmountInputLayoutTest {

    @get:Rule
    val composeRule = createComposeRule()

    /**
     * `useUnmergedTree`: `LfAmountField` merges its parts under one spoken
     * description (§9.6), so the amount does not exist as its own node in the
     * merged tree. The unmerged tree is where the thing that was actually laid
     * out still lives.
     */
    private fun layoutOf(text: String): TextLayoutResult {
        val results = mutableListOf<TextLayoutResult>()
        composeRule.onNodeWithText(text, useUnmergedTree = true).fetchSemanticsNode()
            .config[SemanticsActions.GetTextLayoutResult]
            .action
            ?.invoke(results)
        return results.first()
    }

    @Test
    fun aLargeAmountStaysOnOneLine() {
        composeRule.setContent {
            LfTheme {
                LfAmountField(
                    value = "1,23,45,678.00",
                    onValueChange = {},
                    currencyCode = "INR",
                )
            }
        }

        val layout = layoutOf("1,23,45,678.00")
        assertThat(layout.lineCount).isEqualTo(1)
        assertThat(layout.getLineEnd(0, /* visibleEnd = */ true))
            .isEqualTo("1,23,45,678.00".length)
    }

    /**
     * §9.6 requires 2.0x without truncation or overlap, and the amount is the
     * largest type on the screen -- if anything is going to run out of width at
     * that scale, it is this.
     */
    @Test
    fun aLargeAmountSurvivesFontScaleTwo() {
        composeRule.setContent {
            androidx.compose.runtime.CompositionLocalProvider(
                LocalDensity provides Density(
                    density = LocalDensity.current.density,
                    fontScale = 2.0f,
                ),
            ) {
                LfTheme {
                    LfAmountField(
                        value = "12,345.00",
                        onValueChange = {},
                        currencyCode = "INR",
                    )
                }
            }
        }

        assertThat(layoutOf("12,345.00").lineCount).isEqualTo(1)
    }

    /** §9.6: an amount is announced in words, not as a glyph plus a number. */
    @Test
    fun theAmountIsAnnouncedAsOneSpokenPhrase() {
        composeRule.setContent {
            LfTheme {
                LfAmountField(
                    value = "1240",
                    onValueChange = {},
                    currencyCode = "INR",
                    label = "Amount",
                )
            }
        }

        // Spoken from the *parsed* value, so a half-typed field still announces
        // an amount rather than punctuation.
        composeRule.onNodeWithContentDescription("Amount, 1,240.00 rupees").assertExists()
    }

    /**
     * ADR-0012: typing 125 means one hundred and twenty-five, not one twenty-five
     * paise. The field reports raw text and the parse is what makes it a `Long`.
     */
    @Test
    fun theFieldReportsWhatWasTypedWithoutRewritingIt() {
        var reported = ""
        composeRule.setContent {
            LfTheme {
                var text by remember { mutableStateOf("") }
                LfAmountField(
                    value = text,
                    onValueChange = { text = it; reported = it },
                    currencyCode = "INR",
                    label = "Amount",
                )
            }
        }

        // The field itself, not the merged block: LfAmountField merges its parts
        // under one spoken description (§9.6), so the node carrying the text
        // actions only exists in the unmerged tree.
        composeRule.onNode(hasSetTextAction(), useUnmergedTree = true)
            .performTextReplacement("125.5")

        assertThat(reported).isEqualTo("125.5")
        composeRule.onNodeWithContentDescription("Amount, 125.50 rupees").assertExists()
    }

    private companion object {
        private const val NARROW_PHONE = 280
    }
}
