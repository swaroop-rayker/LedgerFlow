package com.ledgerflow.feature.dashboard

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.hasText
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ledgerflow.core.designsystem.theme.LfTheme
import com.ledgerflow.core.domain.ingest.ListenerHealth
import com.ledgerflow.core.domain.ingest.NotificationCaptureHealth
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * What §5.2's health banner actually says, per state.
 *
 * [DashboardBannerTest] is the JVM half and asserts *whether* the banner shows.
 * It never composes anything, so it cannot see the failure that matters most
 * here: the right state rendering the wrong words. The two unhealthy states
 * differ only in their sentence, and swapping them is a one-character edit that
 * every existing test would pass.
 *
 * **The two sentences are not interchangeable and that is the point.**
 * "is off" means a permission the user never granted — the fix is the explainer.
 * "has stopped" means one they *did* grant that the system has since stopped
 * honouring — the fix is a battery setting. Telling someone to grant a
 * permission they already granted is how a health banner loses its reader, and
 * sending them hunting for a battery setting they never needed is the same
 * failure pointed the other way.
 *
 * This closes the testable half of P2-8's fourth definition-of-done item.
 * **The half it does not close is the six hours**: that the DEAD state is
 * *reached* after a real outage is `ListenerHealthEvaluationTest`'s boundary
 * assertion off-device, and on hardware it needs an OEM battery manager holding
 * the listener down for six hours — `TESTING.md` F19, which no test can hurry.
 */
@RunWith(AndroidJUnit4::class)
class DashboardBannerContentTest {

    @get:Rule
    val composeRule = createComposeRule()

    private fun setHealth(health: NotificationCaptureHealth) {
        composeRule.setContent {
            LfTheme {
                DashboardScreen(
                    state = DashboardUiState(captureHealth = health),
                    onSetUpNotifications = {},
                )
            }
        }
    }

    @Test
    fun notGranted_saysCaptureIsOff_andOffersSetUp() {
        setHealth(NotificationCaptureHealth.NOT_GRANTED)

        composeRule.onNodeWithText("Notification capture is off").assertIsDisplayed()
        composeRule.onNodeWithText("Set up").assertIsDisplayed()
    }

    /**
     * The state the six-hour threshold exists to produce.
     *
     * Asserted on the exact words, and on the *absence* of the other state's
     * words — "is off" here would be a correct-looking banner sending the user
     * to the wrong screen.
     */
    @Test
    fun dead_saysCaptureHasStopped_andNotThatItIsOff() {
        setHealth(NotificationCaptureHealth.DEAD)

        composeRule.onNodeWithText("Notification capture has stopped").assertIsDisplayed()
        composeRule.onAllNodesWithText("Notification capture is off").assertCountEquals(0)
    }

    /**
     * The banner names the threshold it is actually measuring against.
     *
     * The sentence derives its number from [ListenerHealth.DEAD_THRESHOLD_MILLIS]
     * rather than hardcoding "6", so this asserts the two agree. A banner that
     * claims six hours while the rule uses another figure is worse than one that
     * gives no number at all.
     */
    @Test
    fun dead_namesTheThresholdTheRuleActuallyUses() {
        setHealth(NotificationCaptureHealth.DEAD)

        val hours = ListenerHealth.DEAD_THRESHOLD_MILLIS / (60L * 60L * 1000L)
        composeRule.onNode(hasText("$hours hours", substring = true)).assertIsDisplayed()
    }

    /** The DEAD banner points at the cause, because the fix is not in this app. */
    @Test
    fun dead_pointsAtBatteryOptimisation() {
        setHealth(NotificationCaptureHealth.DEAD)

        composeRule.onNode(hasText("Battery optimisation", substring = true)).assertIsDisplayed()
    }

    /**
     * No banner, in any of its words.
     *
     * One assertion per silent state rather than a loop, because
     * `composeRule.setContent` may only be called **once per test** — a loop
     * calling it three times throws on the second pass, and a test that throws
     * where it meant to assert is not testing the thing in its name.
     */
    private fun assertNoBanner() {
        composeRule.onAllNodesWithText("Notification capture is off").assertCountEquals(0)
        composeRule.onAllNodesWithText("Notification capture has stopped").assertCountEquals(0)
        composeRule.onAllNodesWithText("Set up").assertCountEquals(0)
        composeRule.onAllNodesWithText("Check settings").assertCountEquals(0)
    }

    @Test
    fun connected_rendersNoBanner() {
        setHealth(NotificationCaptureHealth.CONNECTED)

        assertNoBanner()
    }

    /**
     * The state on screen for the first frames of every single launch.
     *
     * A banner here would appear on every open of a perfectly healthy install
     * and then vanish, which is how a warning becomes wallpaper.
     */
    @Test
    fun reconnecting_rendersNoBanner() {
        setHealth(NotificationCaptureHealth.RECONNECTING)

        assertNoBanner()
    }

    @Test
    fun unavailable_rendersNoBanner() {
        setHealth(NotificationCaptureHealth.UNAVAILABLE)

        assertNoBanner()
    }

    /** The default state is what Home composes before anything has been polled. */
    @Test
    fun theDefaultState_rendersNoBanner() {
        composeRule.setContent {
            LfTheme { DashboardScreen(state = DashboardUiState(), onSetUpNotifications = {}) }
        }

        assertNoBanner()
    }

    /**
     * The banner sits above the empty state rather than replacing it.
     *
     * `CLAUDE.md` §7: a dead listener must not look like an empty Inbox. The
     * same argument applies to an empty ledger — "no entries yet" is a fact
     * about the ledger and "capture is not working" is a fact about the pipeline
     * that fills it, and a user who sees only the first has no way to tell them
     * apart.
     */
    @Test
    fun theBanner_doesNotReplaceTheEmptyState() {
        setHealth(NotificationCaptureHealth.DEAD)

        composeRule.onNodeWithText("Notification capture has stopped").assertIsDisplayed()
        composeRule.onNodeWithText("Nothing here yet").assertIsDisplayed()
    }
}
