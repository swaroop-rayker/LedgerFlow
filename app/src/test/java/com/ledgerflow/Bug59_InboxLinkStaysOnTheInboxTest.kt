package com.ledgerflow

import android.app.Application
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertWithMessage
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * **BUG59: an Inbox link opened the Inbox for a frame and then put the user
 * back on Home.**
 *
 * `ledgerflow://inbox` is what the capture notification's group summary opens,
 * and `ledgerflow://inbox/{pendingId}` what each candidate's opens. Found
 * 2026-10-20 building `InboxScrollBenchmark`: a cold start through the link
 * landed on Home. The shell navigates and then clears the link
 * (`onDeepLinkHandled`), and the vault-ready route hosted the shell from two
 * different `when` branches, one for "a link is waiting" and one for the rest.
 * Clearing the link moved the shell from one branch to the other. To Compose
 * that is a different place in the tree, so the shell and its nav controller
 * were thrown away and rebuilt at the start destination.
 *
 * [ReadyRoute] is the production routing. The shell here stands in for
 * `LedgerFlowShell`, which needs Hilt, and handles the link the same way: a
 * `NavHost` with its own `rememberNavController`, which navigates in a
 * `LaunchedEffect` and then clears the link. Red before the fix (the Inbox is
 * gone once the link is cleared), green after.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
// The plain Application: LedgerFlowApplication starts Hilt, WorkManager and
// StrictMode's penaltyDeath, none of which this routing needs (as Bug57's test).
@Config(sdk = [34], application = Application::class)
class Bug59_InboxLinkStaysOnTheInboxTest {

    @get:Rule
    val rule = createComposeRule()

    @Test
    fun coldStartThroughTheLink_endsOnTheInbox() {
        var link by mutableStateOf<String?>(INBOX_LINK)
        rule.setContent {
            ReadyRoute(
                deepLink = link,
                prompt = NotificationSetupPrompt.Dismissed,
                explainer = { Text(EXPLAINER) },
                shell = { StandInShell(link) { link = null } },
            )
        }
        rule.waitForIdle()

        assertWithMessage("the link is cleared once handled").that(link).isNull()
        rule.onNodeWithText(INBOX).assertIsDisplayed()
    }

    @Test
    fun tapWhileRunning_endsOnTheInbox() {
        var link by mutableStateOf<String?>(null)
        rule.setContent {
            ReadyRoute(
                deepLink = link,
                prompt = NotificationSetupPrompt.Dismissed,
                explainer = { Text(EXPLAINER) },
                shell = { StandInShell(link) { link = null } },
            )
        }
        rule.onNodeWithText(HOME).assertIsDisplayed()

        // `onNewIntent`: the activity is already showing the shell.
        link = INBOX_LINK
        rule.waitForIdle()

        assertWithMessage("the link is cleared once handled").that(link).isNull()
        rule.onNodeWithText(INBOX).assertIsDisplayed()
    }

    /**
     * The explainer must not take the screen from a link either. "A link
     * outranks the explainer unconditionally" held only while the link was
     * waiting: once it was cleared, a first-run user who had opened the Inbox
     * from a notification was moved onto the setup screen.
     */
    @Test
    fun linkOnTheExplainersFirstRun_staysOnTheInbox() {
        var link by mutableStateOf<String?>(INBOX_LINK)
        rule.setContent {
            ReadyRoute(
                deepLink = link,
                prompt = NotificationSetupPrompt.Show,
                explainer = { Text(EXPLAINER) },
                shell = { StandInShell(link) { link = null } },
            )
        }
        rule.waitForIdle()

        assertWithMessage("the link is cleared once handled").that(link).isNull()
        rule.onNodeWithText(INBOX).assertIsDisplayed()
    }

    /** The explainer still owns a first run that was not opened by a link. */
    @Test
    fun firstRunWithoutALink_showsTheExplainer() {
        rule.setContent {
            ReadyRoute(
                deepLink = null,
                prompt = NotificationSetupPrompt.Show,
                explainer = { Text(EXPLAINER) },
                shell = { StandInShell(null) {} },
            )
        }
        rule.onNodeWithText(EXPLAINER).assertIsDisplayed()
    }

    /** `LedgerFlowShell`'s link handling, without its Hilt-built screens. */
    @Composable
    private fun StandInShell(link: String?, onLinkHandled: () -> Unit) {
        val navController = rememberNavController()
        LaunchedEffect(link) {
            if (link == null) return@LaunchedEffect
            navController.navigate(INBOX)
            onLinkHandled()
        }
        NavHost(navController = navController, startDestination = HOME) {
            composable(HOME) { Text(HOME) }
            composable(INBOX) { Text(INBOX) }
        }
    }

    private companion object {
        const val INBOX_LINK = "ledgerflow://inbox"
        const val HOME = "home"
        const val INBOX = "inbox"
        const val EXPLAINER = "explainer"
    }
}
