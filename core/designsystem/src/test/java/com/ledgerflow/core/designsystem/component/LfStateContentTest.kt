package com.ledgerflow.core.designsystem.component

import androidx.compose.material3.Text
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
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
 * `LfStateContent`'s rules (DESIGN_SYSTEM_PLAN §5.1, step E1), each of which a
 * golden cannot see: when a loading screen starts drawing, that it never spins
 * without saying so, and that no failure is drawn without a way forward.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w360dp-h1600dp")
class LfStateContentTest {

    @get:Rule
    val rule = createComposeRule()

    @Test
    fun loading_drawsNothingFor300ms_thenSkeletons_thenAt10sSaysSo() {
        rule.mainClock.autoAdvance = false
        rule.setContent {
            LfTheme {
                LfStateContent(state = LfContentState.Loading, slowMessage = "Still counting.") { Text("Ready") }
            }
        }
        rule.mainClock.advanceTimeBy(250)
        rule.onNodeWithContentDescription("Loading").assertDoesNotExist()

        rule.mainClock.advanceTimeBy(100)
        rule.onNodeWithContentDescription("Loading").assertExists()
        rule.onNodeWithText("Still counting.").assertDoesNotExist()

        rule.mainClock.advanceTimeBy(9_700)
        rule.onNodeWithText("Still counting.").assertExists()
        rule.onNodeWithText("Ready").assertDoesNotExist()
    }

    @Test
    fun ready_drawsTheContent() {
        rule.setContent { LfTheme { LfStateContent(state = LfContentState.Ready) { Text("Ready") } } }
        rule.onNodeWithText("Ready").assertExists()
    }

    @Test
    fun error_offersItsActionAndCallsIt() {
        var retried = 0
        rule.setContent {
            LfTheme {
                LfStateContent(
                    state = LfContentState.Error(title = "Couldn't load your budgets", onRetry = { retried++ }),
                ) { Text("Ready") }
            }
        }
        rule.onNodeWithText("Retry").performClick()
        assertThat(retried).isEqualTo(1)
    }

    @Test
    fun unavailable_offersTheFix() {
        var fixed = 0
        rule.setContent {
            LfTheme {
                LfStateContent(
                    state = LfContentState.Unavailable(
                        title = "Camera access is off",
                        fixLabel = "Allow camera",
                        onFix = { fixed++ },
                    ),
                ) { Text("Ready") }
            }
        }
        rule.onNodeWithText("Allow camera").performClick()
        assertThat(fixed).isEqualTo(1)
    }

    @Test
    fun noMatches_withoutTheWayOut_isRejected() {
        assertThrows(IllegalArgumentException::class.java) {
            LfContentState.Empty(kind = LfEmptyKind.NoMatches, title = "Nothing matched", body = "Try fewer filters.")
        }
        // The other kinds may stand alone.
        LfContentState.Empty(kind = LfEmptyKind.AllDone, title = "Inbox clear", body = "Nothing waiting.")
    }
}
