package com.ledgerflow.navigation

import android.app.Application
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavHostController
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import com.ledgerflow.core.designsystem.theme.LfTheme
import com.ledgerflow.core.domain.appearance.MotionLevel
import com.ledgerflow.core.domain.appearance.StartScreen
import com.ledgerflow.feature.settings.SettingsPage
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * Settings > Appearance > Start screen (V2-PLAN §3.2).
 *
 * Drives the production pieces -- [rememberStartDestination], [LfNavHost] and
 * [switchTab] -- with stand-in screens, the way BUG57's test does.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], application = Application::class)
class StartScreenTest {

    @get:Rule
    val rule = createComposeRule()

    private lateinit var navController: NavHostController

    @Test
    fun eachChoice_opensOnItsTab() {
        var resolved: List<Destination> = emptyList()
        rule.setContent { resolved = StartScreen.entries.map { rememberStartDestination(it) } }
        rule.waitForIdle()
        assertThat(StartScreen.entries.zip(resolved)).containsExactly(
            StartScreen.HOME to Destination.Dashboard,
            StartScreen.LEDGER to Destination.Ledger,
            StartScreen.ANALYTICS to Destination.Analytics,
        ).inOrder()
    }

    @Test
    fun theChosenTab_isTheGraphsStart_soBackFromItLeaves_andOtherTabsReturnToIt() {
        show(StartScreen.LEDGER)
        rule.onNodeWithText("Ledger screen").assertExists()
        assertWithMessage("entries under the Ledger tab: Back from it leaves the app")
            .that(stackRoutes()).containsExactly("Ledger")

        rule.runOnUiThread { navController.switchTab(Destination.Analytics) }
        rule.waitForIdle()
        assertThat(stackRoutes()).containsExactly("Ledger", "Analytics").inOrder()

        // Home is just another tab now: switching to it stacks it on the
        // start, not under it.
        rule.runOnUiThread { navController.switchTab(Destination.Dashboard) }
        rule.waitForIdle()
        assertThat(stackRoutes()).containsExactly("Ledger", "Dashboard").inOrder()

        rule.runOnUiThread { navController.popBackStack() }
        rule.waitForIdle()
        rule.onNodeWithText("Ledger screen").assertExists()
    }

    @Test
    fun aChangeWhileRunning_doesNotRebuildTheGraph() {
        // The setting's own page is inside this graph: following the choice
        // live would rebuild it and throw the user off that page. Since D4 that
        // page is Profile > Settings > Appearance.
        var choice by mutableStateOf(StartScreen.HOME)
        show { choice }
        rule.runOnUiThread {
            navController.switchTab(Destination.Profile)
            navController.navigate(Destination.Settings)
            navController.navigate(settingsRouteFor(SettingsPage.Appearance))
        }
        rule.waitForIdle()

        choice = StartScreen.ANALYTICS
        rule.waitForIdle()

        rule.onNodeWithText("Appearance page").assertExists()
        assertThat(stackRoutes())
            .containsExactly("Dashboard", "Profile", "Settings", "SettingsSection").inOrder()
    }

    private fun show(start: StartScreen) = show { start }

    private fun show(start: () -> StartScreen) {
        rule.setContent {
            LfTheme {
                navController = rememberNavController()
                LfNavHost(
                    navController = navController,
                    motion = rememberNavMotion(MotionLevel.OFF),
                    startDestination = rememberStartDestination(start()),
                ) {
                    composable<Destination.Dashboard> { Text("Home screen") }
                    composable<Destination.Ledger> { Text("Ledger screen") }
                    composable<Destination.Analytics> { Text("Analytics screen") }
                    composable<Destination.Profile> { Text("Profile screen") }
                    composable<Destination.Settings> { Text("Settings screen") }
                    composable<Destination.SettingsSection> { Text("Appearance page") }
                }
            }
        }
        rule.waitForIdle()
    }

    /** The back stack as tab names, graph node excluded. */
    private fun stackRoutes(): List<String> = navController.currentBackStack.value
        .mapNotNull { entry ->
            listOf(
                Destination.Dashboard::class,
                Destination.Ledger::class,
                Destination.Analytics::class,
                Destination.Profile::class,
                Destination.Settings::class,
                Destination.SettingsSection::class,
            )
                .firstOrNull { entry.destination.hasRoute(it) }
                ?.simpleName
        }
}
