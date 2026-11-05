package com.ledgerflow.navigation

import android.app.Application
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavHostController
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import com.ledgerflow.core.designsystem.theme.LfTheme
import com.ledgerflow.core.domain.appearance.MotionLevel
import com.ledgerflow.feature.settings.SettingsPage
import com.ledgerflow.feature.settings.SettingsUiState
import com.ledgerflow.feature.settings.settingsPageNamed
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Profile replaced Settings as the fourth tab, and each Settings section became
 * a page (design-system step D4, DESIGN_SYSTEM_PLAN Q8).
 *
 * A move like that breaks in ways every screen survives looking right: a row
 * wired to its neighbour's destination, a page whose route name no longer reads
 * back, a Back that skips a level. Each of those is one test here. Home's
 * banners, the budget-alert tap and `ledgerflow://` links never went through
 * Settings and their code did not change; `InboxDeepLinkTest` still holds the
 * links.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], application = Application::class, qualifiers = "w360dp-h3600dp")
class ProfileSettingsRoutesTest {

    @get:Rule
    val rule = createComposeRule()

    @Test
    fun theBar_endsWithProfile_andSettingsIsNotATab() {
        assertThat(BottomBarDestinations).containsExactly(
            Destination.Dashboard,
            Destination.Ledger,
            Destination.Analytics,
            Destination.Profile,
        ).inOrder()
    }

    @Test
    fun everyPage_roundTripsThroughItsRoute() {
        SettingsPage.entries.forEach { page ->
            val route = settingsRouteFor(page) as Destination.SettingsSection
            assertWithMessage(page.name).that(settingsPageNamed(route.section)).isEqualTo(page)
        }
    }

    /** Every row that leaves its page, by the title the user taps, and where it lands. */
    @Test
    fun everySettingsRow_reachesItsPage() {
        val rows = mapOf(
            SettingsPage.Notifications to listOf(
                "Notification capture" to Destination.NotificationAccess,
                "Payment apps" to Destination.PaymentApps,
            ),
            SettingsPage.Organise to listOf(
                "Categories & merchants" to Destination.Categories,
                "Budgets" to Destination.Budgets,
            ),
            SettingsPage.Data to listOf(
                "Back up now" to Destination.BackUpNow,
                "Export" to Destination.Export,
                "Deleted entries" to Destination.DeletedEntries,
            ),
            SettingsPage.Security to listOf("Data permissions" to Destination.DataPermissions),
            SettingsPage.Tools to listOf("Diagnostics" to Destination.Diagnostics),
        )
        val reached = mutableListOf<Destination>()
        var alerts = 0
        var page by mutableStateOf(SettingsPage.Notifications)
        rule.setContent {
            LfTheme {
                SettingsSectionRoute(
                    page = page,
                    state = SettingsUiState(isLoaded = true),
                    navigate = { reached += it },
                    onBack = {},
                    onBudgetAlerts = { alerts++ },
                    onEvent = {},
                )
            }
        }

        rows.forEach { (onPage, taps) ->
            page = onPage
            rule.waitForIdle()
            taps.forEach { (title, destination) ->
                reached.clear()
                rule.onNodeWithText(title).performScrollTo().performClick()
                rule.waitForIdle()
                assertWithMessage("\"$title\" on ${onPage.name}").that(reached).containsExactly(destination)
            }
        }

        // Budget alerts opens Android's own page for the channel, not a route.
        page = SettingsPage.Notifications
        rule.waitForIdle()
        reached.clear()
        rule.onNodeWithText("Budget alerts").performScrollTo().performClick()
        rule.waitForIdle()
        assertThat(alerts).isEqualTo(1)
        assertThat(reached).isEmpty()
    }

    @Test
    fun back_unwindsASection_thenSettings_thenProfile_andDataReturnsToProfile() {
        lateinit var navController: NavHostController
        rule.setContent {
            LfTheme {
                navController = rememberNavController()
                LfNavHost(
                    navController = navController,
                    motion = rememberNavMotion(MotionLevel.OFF),
                    startDestination = Destination.Dashboard,
                ) {
                    composable<Destination.Dashboard> { Text("Home screen") }
                    composable<Destination.Profile> { Text("Profile screen") }
                    composable<Destination.Settings> { Text("Settings screen") }
                    composable<Destination.SettingsSection> { Text("Section page") }
                }
            }
        }
        rule.waitForIdle()
        rule.runOnUiThread {
            navController.switchTab(Destination.Profile)
            navController.navigate(Destination.Settings)
            navController.navigate(settingsRouteFor(SettingsPage.Appearance))
        }
        rule.waitForIdle()
        rule.onNodeWithText("Section page").assertExists()

        fun currentIs(destination: Destination) =
            navController.currentDestination?.hasRoute(destination::class) == true

        rule.runOnUiThread { navController.popBackStack() }
        rule.waitForIdle()
        assertWithMessage("Back from a section").that(currentIs(Destination.Settings)).isTrue()

        rule.runOnUiThread { navController.popBackStack() }
        rule.waitForIdle()
        assertWithMessage("Back from Settings").that(currentIs(Destination.Profile)).isTrue()
        rule.onNodeWithText("Profile screen").assertExists()

        // Data and Organise open from Profile itself (BUG65): Back returns there.
        rule.runOnUiThread { navController.navigate(settingsRouteFor(SettingsPage.Data)) }
        rule.waitForIdle()
        rule.runOnUiThread { navController.popBackStack() }
        rule.waitForIdle()
        assertWithMessage("Back from Data opened on Profile").that(currentIs(Destination.Profile)).isTrue()
    }
}
