package com.ledgerflow.navigation

import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavDestination
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.ledgerflow.core.designsystem.component.LfBottomBar
import com.ledgerflow.core.designsystem.component.LfNavItem
import com.ledgerflow.core.designsystem.component.LfScaffold
import com.ledgerflow.core.designsystem.icon.LfIcons
import com.ledgerflow.feature.analytics.AnalyticsScreen
import com.ledgerflow.feature.categories.CategoriesScreen
import com.ledgerflow.feature.categories.CategoriesViewModel
import com.ledgerflow.feature.dashboard.DashboardScreen
import com.ledgerflow.feature.ledger.LedgerScreen
import com.ledgerflow.feature.settings.MoreScreen

/**
 * The unlocked app: bottom bar, centre action, nav graph (SPEC.md §9.3).
 *
 * Reached only from [com.ledgerflow.AppRoute.Ready], so every screen inside can
 * assume an open database.
 *
 * The centre action navigates straight to the entry form rather than opening
 * the speed dial §9.3 describes. Two of that dial's three options -- Inbox and
 * Scan receipt -- do not exist until P2 and P4, and a menu whose other entries
 * are greyed out is worse than no menu. The dial lands when it has a second
 * live option.
 */
@Composable
internal fun LedgerFlowShell(
    modifier: Modifier = Modifier,
    navController: NavHostController = rememberNavController(),
) {
    val backStackEntry by navController.currentBackStackEntryAsState()
    val current = backStackEntry?.destination

    // Full-screen destinations hide the bar: a bottom bar under an entry form
    // invites a tap that silently discards what the user was typing.
    val showBottomBar = BottomBarDestinations.any { current.isAt(it) }

    LfScaffold(
        modifier = modifier,
        bottomBar = {
            if (showBottomBar) {
                LfBottomBar(
                    items = BottomBarDestinations.map { destination ->
                        LfNavItem(
                            label = destination.label,
                            icon = destination.icon,
                            selected = current.isAt(destination),
                            onClick = { navController.switchTab(destination) },
                        )
                    },
                    onAddClick = { navController.navigate(Destination.Entry) },
                    addContentDescription = "Add an entry",
                )
            }
        },
    ) { padding ->
        NavHost(
            navController = navController,
            startDestination = Destination.Dashboard,
            modifier = Modifier.padding(padding),
        ) {
            composable<Destination.Dashboard> { DashboardScreen() }
            composable<Destination.Ledger> { LedgerScreen() }
            composable<Destination.Analytics> { AnalyticsScreen() }
            composable<Destination.More> {
                MoreScreen(
                    onCategories = { navController.navigate(Destination.Categories) },
                    onExport = { navController.navigate(Destination.Export) },
                )
            }
            composable<Destination.Entry> { EntryPlaceholder(navController::popBackStack) }
            composable<Destination.Categories> {
                val viewModel: CategoriesViewModel = hiltViewModel()
                val state by viewModel.state.collectAsStateWithLifecycle()
                CategoriesScreen(
                    state = state,
                    onEvent = viewModel::onEvent,
                    onBack = { navController.popBackStack() },
                )
            }
            composable<Destination.Export> { ExportPlaceholder(navController::popBackStack) }
        }
    }
}

/**
 * Tab switching, not stacking.
 *
 * Without `popUpTo(startDestination) { saveState }` every tap pushes a new entry
 * and the system back gesture walks the user backwards through their tab
 * history instead of leaving the app -- the most common bottom-bar bug there is.
 */
private fun NavHostController.switchTab(destination: Destination) {
    navigate(destination) {
        popUpTo(graph.findStartDestination().id) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}

/** True when [destination] is the current entry or an ancestor of it. */
private fun NavDestination?.isAt(destination: Destination): Boolean =
    this?.hierarchy?.any { it.hasRoute(destination::class) } == true

private val Destination.label: String
    get() = when (this) {
        Destination.Dashboard -> "Home"
        Destination.Ledger -> "Ledger"
        Destination.Analytics -> "Analytics"
        Destination.More -> "More"
        Destination.Entry -> "Add"
        Destination.Categories -> "Categories"
        Destination.Export -> "Export"
    }

private val Destination.icon
    get() = when (this) {
        Destination.Dashboard -> LfIcons.Dashboard
        Destination.Ledger -> LfIcons.Ledger
        Destination.Analytics -> LfIcons.Analytics
        else -> LfIcons.More
    }
