package com.ledgerflow.core.designsystem.pattern

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import com.ledgerflow.core.designsystem.theme.LfTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The step-E1 sheets do what they say: each option opens its own place, the
 * two answers of a confirmation are distinct, and a filter chip reports
 * whether it is chosen as state rather than only as a colour (§9.6).
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w420dp-h900dp")
class LfSheetsTest {

    @get:Rule
    val rule = createComposeRule()

    @Test
    fun everyOption_opensItsOwnPlace() {
        val opened = mutableListOf<String>()
        rule.setContent {
            LfTheme {
                LfOptionsSheet(
                    options = listOf("Scan a receipt", "Manual entry", "Inbox").map { label ->
                        LfSheetOption(label = label, onClick = { opened += label })
                    },
                    onDismiss = {},
                )
            }
        }
        listOf("Scan a receipt", "Manual entry", "Inbox").forEach { label ->
            opened.clear()
            rule.onNodeWithText(label).performClick()
            assertWithMessage(label).that(opened).containsExactly(label)
        }
    }

    @Test
    fun aConfirmation_keepsItsTwoAnswersApart() {
        var confirmed = 0
        var dismissed = 0
        rule.setContent {
            LfTheme {
                LfConfirmSheet(
                    title = "Sign out of Google Drive?",
                    body = "Backups stop going to Drive.",
                    confirmLabel = "Sign out",
                    onConfirm = { confirmed++ },
                    onDismiss = { dismissed++ },
                )
            }
        }
        rule.onNodeWithText("Cancel").performClick()
        assertThat(confirmed to dismissed).isEqualTo(0 to 1)
        rule.onNodeWithText("Sign out").performClick()
        assertThat(confirmed to dismissed).isEqualTo(1 to 1)
    }

    @Test
    fun filterChips_sayWhetherTheyAreChosen_andApplyAndResetAreSeparate() {
        val toggled = mutableListOf<String>()
        var applied = 0
        var reset = 0
        rule.setContent {
            LfTheme {
                LfFilterSheet(
                    title = "Filters",
                    groups = listOf(
                        LfFilterGroup(
                            label = "Source",
                            choices = listOf(
                                LfFilterChoice(label = "SMS", selected = true, onToggle = { toggled += "SMS" }),
                                LfFilterChoice(
                                    label = "Receipt",
                                    selected = false,
                                    onToggle = { toggled += "Receipt" },
                                ),
                            ),
                        ),
                    ),
                    onReset = { reset++ },
                    onApply = { applied++ },
                    onDismiss = {},
                )
            }
        }
        rule.onNodeWithText("SMS").assert(SemanticsMatcher.expectValue(SemanticsProperties.Selected, true))
        rule.onNodeWithText("Source").assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.Heading))
        rule.onNodeWithText("Receipt").performClick()
        assertThat(toggled).containsExactly("Receipt")
        rule.onNodeWithText("Apply").performClick()
        rule.onNodeWithText("Reset").performClick()
        assertThat(applied to reset).isEqualTo(1 to 1)
    }
}
