package com.ledgerflow.feature.dashboard

import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollToNode
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ledgerflow.core.designsystem.theme.LfTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Home's first frame builds what is on screen, not the whole page
 * (`docs/PERF-120HZ-PLAN.md` §5).
 *
 * **Found in the tab-switch traces.** Home was a scrolling `Column`, so the
 * frame it appeared on composed and measured every card and both recent lists,
 * including the half of the page below the fold. That was the costliest first
 * frame of the four tabs, 28 ms at 120 Hz where a frame has 8.3. Home is now a
 * `LazyColumn`, and this test holds it there: on a phone-sized screen the
 * month's last section is not composed until the user scrolls to it.
 *
 * The second test is the other half, because a page that "composes less" by
 * losing a section passes the first one: the section is still there, a scroll
 * away.
 */
@RunWith(AndroidJUnit4::class)
// NATIVE, or text measures zero wide and every card fits on one screen.
@GraphicsMode(GraphicsMode.Mode.NATIVE)
// The owner's phone in dp (SM-S721B, 1080 x 2340 px at 3x).
@Config(sdk = [34], qualifiers = "w360dp-h780dp")
class DashboardScreenFirstFrameTest {

    @get:Rule
    val rule = createComposeRule()

    private fun showPopulatedHome() {
        rule.setContent {
            LfTheme { DashboardScreen(state = HomeFixtures.populated, onSetUpNotifications = {}) }
        }
    }

    @Test
    fun belowTheFold_notComposedOnFirstFrame() {
        showPopulatedHome()

        rule.onNodeWithText("Home").assertExists()
        rule.onNodeWithText("Recent income").assertDoesNotExist()
    }

    @Test
    fun belowTheFold_reachedByScrolling() {
        showPopulatedHome()

        rule.onNode(hasScrollAction()).performScrollToNode(hasText("Recent income"))

        rule.onNodeWithText("Recent income").assertExists()
    }
}
