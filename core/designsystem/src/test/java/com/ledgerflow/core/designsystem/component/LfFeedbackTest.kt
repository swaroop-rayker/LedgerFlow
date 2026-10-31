package com.ledgerflow.core.designsystem.component

import androidx.compose.material3.Text
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.ledgerflow.core.designsystem.theme.LfTheme
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The window's one snackbar (DESIGN_SYSTEM_PLAN §5.2, step E1).
 *
 * Every full-screen destination is a scaffold inside the shell's, so the
 * failure modes are a message drawn twice, a message lost with the screen that
 * raised it, and a message raised where nothing can show it. One test each.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w360dp-h800dp")
class LfFeedbackTest {

    @get:Rule
    val rule = createComposeRule()

    @Test
    fun aMessage_fromInsideNestedScaffolds_isDrawnOnce() {
        rule.setContent {
            LfTheme {
                LfScaffold {
                    LfScaffold {
                        val feedback = LfFeedback.current
                        LaunchedEffect(Unit) { feedback.show("Moved to bin") }
                        Text("Inner screen")
                    }
                }
            }
        }
        rule.waitForIdle()
        rule.onAllNodesWithText("Moved to bin").assertCountEquals(1)
    }

    @Test
    fun aMessage_outlivesTheScreenThatRaisedIt() {
        var entryOpen by mutableStateOf(true)
        rule.setContent {
            LfTheme {
                LfScaffold {
                    if (entryOpen) {
                        LfScaffold {
                            val feedback = LfFeedback.current
                            LaunchedEffect(Unit) {
                                // Save, announce on the screen we land on, close. `post`,
                                // not `show`: `show` would be cancelled with this screen.
                                feedback.post("Saved to Expenses")
                                entryOpen = false
                            }
                            Text("Entry")
                        }
                    } else {
                        Text("Ledger")
                    }
                }
            }
        }
        rule.waitForIdle()
        rule.onNodeWithText("Ledger").assertExists()
        rule.onAllNodesWithText("Saved to Expenses").assertCountEquals(1)
    }

    @Test
    fun theAction_reportsThatItWasTapped() {
        var undone: Boolean? = null
        rule.setContent {
            LfTheme {
                LfScaffold {
                    val feedback = LfFeedback.current
                    LaunchedEffect(Unit) { undone = feedback.show("Moved to bin", actionLabel = "Undo") }
                }
            }
        }
        rule.onNodeWithText("Undo").performClick()
        rule.waitForIdle()
        assertThat(undone).isTrue()
    }

    @Test
    fun aPostedAction_runsWhenTapped() {
        var undone = 0
        rule.setContent {
            LfTheme {
                LfScaffold {
                    val feedback = LfFeedback.current
                    LaunchedEffect(Unit) { feedback.post("Moved to bin", actionLabel = "Undo") { undone++ } }
                }
            }
        }
        rule.onNodeWithText("Undo").performClick()
        rule.waitForIdle()
        assertThat(undone).isEqualTo(1)
    }

    @Test
    fun aMessage_raisedWhereNothingCanShowIt_isAnError() {
        assertThrows(IllegalStateException::class.java) {
            rule.setContent { LfTheme { LfFeedback.current } }
        }
    }
}
