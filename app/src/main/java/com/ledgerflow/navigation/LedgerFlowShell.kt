package com.ledgerflow.navigation

import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavDestination
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.ledgerflow.core.designsystem.component.LfBottomBar
import com.ledgerflow.core.designsystem.component.LfNavItem
import com.ledgerflow.core.designsystem.component.LfButton
import com.ledgerflow.core.designsystem.component.LfButtonStyle
import com.ledgerflow.core.designsystem.component.LfScaffold
import com.ledgerflow.core.designsystem.theme.LfTheme
import com.ledgerflow.core.designsystem.icon.LfIcons
import com.ledgerflow.feature.analytics.AnalyticsScreen
import com.ledgerflow.feature.export.ExportRoute
import com.ledgerflow.feature.categories.CategoriesScreen
import com.ledgerflow.feature.categories.CategoriesViewModel
import com.ledgerflow.feature.dashboard.DashboardScreen
import com.ledgerflow.feature.entry.EntryScreen
import com.ledgerflow.feature.entry.EntryViewModel
import com.ledgerflow.feature.ledger.BinScreen
import com.ledgerflow.feature.inbox.InboxScreen
import com.ledgerflow.feature.inbox.InboxViewModel
import com.ledgerflow.feature.inbox.ReviewScreen
import com.ledgerflow.feature.inbox.ReviewViewModel
import com.ledgerflow.feature.ledger.BinViewModel
import com.ledgerflow.feature.ledger.LedgerScreen
import com.ledgerflow.feature.ledger.LedgerViewModel
import com.ledgerflow.feature.settings.MoreScreen
import com.ledgerflow.feature.settings.MoreViewModel

/**
 * The unlocked app: bottom bar, centre action, nav graph (SPEC.md §9.3).
 *
 * Reached only from [com.ledgerflow.AppRoute.Ready], so every screen inside can
 * assume an open database.
 *
 * **The centre action opens the dial §9.3 describes**, as of P2-6. It did not
 * before: two of the dial's three options -- Inbox and Scan receipt -- did not
 * exist, and a menu whose other entries are greyed out is worse than no menu.
 * The Inbox is that second live option. `Scan receipt` still does not exist and
 * is still absent rather than disabled, on the same reasoning; it joins at P4.
 */
@Composable
internal fun LedgerFlowShell(
    modifier: Modifier = Modifier,
    navController: NavHostController = rememberNavController(),
) {
    val backStackEntry by navController.currentBackStackEntryAsState()
    val current = backStackEntry?.destination

    val shellViewModel: ShellViewModel = hiltViewModel()
    val pendingCount by shellViewModel.pendingCount.collectAsStateWithLifecycle()
    var dialOpen by rememberSaveable { mutableStateOf(false) }

    if (dialOpen) {
        CentreActionDial(
            pendingCount = pendingCount,
            onDismiss = { dialOpen = false },
            onManualEntry = {
                dialOpen = false
                navController.navigate(Destination.Entry())
            },
            onInbox = {
                dialOpen = false
                navController.navigate(Destination.Inbox)
            },
        )
    }

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
                    onAddClick = { dialOpen = true },
                    addContentDescription = "Add an entry",
                )
            }
        },
    ) { padding ->
        LedgerFlowNavHost(
            navController = navController,
            modifier = Modifier.padding(padding),
        )
    }
}

/**
 * The graph itself, split out from the scaffold above it.
 *
 * Two jobs, two functions: [LedgerFlowShell] decides what chrome is on screen
 * and this decides what is under it. They were one function until the Ledger
 * destination gained a ViewModel and pushed it past detekt's length limit --
 * which was the right signal, since by then the chrome logic was six lines
 * buried under a screen-by-screen list.
 *
 * The destinations are split again by *chrome* rather than by feature: the four
 * that keep the bottom bar, and the four full-screen ones that hide it. That is
 * the distinction [LedgerFlowShell] actually acts on, so it is the one worth
 * being able to read off the graph.
 */
@Composable
private fun LedgerFlowNavHost(
    navController: NavHostController,
    modifier: Modifier = Modifier,
) {
    NavHost(
        navController = navController,
        startDestination = Destination.Dashboard,
        modifier = modifier,
    ) {
        tabDestinations(navController)
        fullScreenDestinations(navController)
    }
}

/** The four that keep the bottom bar (§9.3). */
private fun NavGraphBuilder.tabDestinations(navController: NavHostController) {
    composable<Destination.Dashboard> { DashboardScreen() }
    composable<Destination.Ledger> {
        val viewModel: LedgerViewModel = hiltViewModel()
        val state by viewModel.state.collectAsStateWithLifecycle()
        LedgerScreen(
            state = state,
            entries = viewModel.entries,
            onEvent = viewModel::onEvent,
            // The Ledger lists unsaved entries but does not edit them -- that is
            // the entry form's job, and features never reach each other
            // directly. It hands up an id; the graph decides where it goes
            // (CLAUDE.md §3).
            onOpenDraft = { draftId ->
                navController.navigate(Destination.Entry(draftId = draftId))
            },
        )
    }
    composable<Destination.Analytics> { AnalyticsScreen() }
    composable<Destination.More> {
        val viewModel: MoreViewModel = hiltViewModel()
        val state by viewModel.state.collectAsStateWithLifecycle()
        MoreScreen(
            state = state,
            onCategories = { navController.navigate(Destination.Categories) },
            onExport = { navController.navigate(Destination.Export) },
            onDeletedEntries = { navController.navigate(Destination.DeletedEntries) },
        )
    }
}

/**
 * The ones that hide the bottom bar.
 *
 * Each carries its own way out rather than relying on the gesture alone -- a
 * full-screen destination with no visible exit is one people back out of by
 * accident, and two of these are holding unsaved work while they do it.
 */
private fun NavGraphBuilder.fullScreenDestinations(navController: NavHostController) {
    composable<Destination.Entry> {
        val viewModel: EntryViewModel = hiltViewModel()
        val state by viewModel.state.collectAsStateWithLifecycle()
        EntryScreen(
            state = state,
            onEvent = viewModel::onEvent,
            onDone = { navController.popBackStack() },
        )
    }
    composable<Destination.Categories> {
        val viewModel: CategoriesViewModel = hiltViewModel()
        val state by viewModel.state.collectAsStateWithLifecycle()
        CategoriesScreen(
            state = state,
            onEvent = viewModel::onEvent,
            onBack = { navController.popBackStack() },
        )
    }
    composable<Destination.DeletedEntries> {
        val viewModel: BinViewModel = hiltViewModel()
        val state by viewModel.state.collectAsStateWithLifecycle()
        BinScreen(
            state = state,
            onEvent = viewModel::onEvent,
            onBack = { navController.popBackStack() },
        )
    }
    composable<Destination.Export> { ExportRoute(onBack = navController::popBackStack) }
    composable<Destination.Inbox> {
        val viewModel: InboxViewModel = hiltViewModel()
        val state by viewModel.state.collectAsStateWithLifecycle()
        InboxScreen(
            state = state,
            onEvent = viewModel::onEvent,
            onReview = { pendingId -> navController.navigate(Destination.InboxReview(pendingId)) },
        )
    }
    composable<Destination.InboxReview> {
        val viewModel: ReviewViewModel = hiltViewModel()
        val state by viewModel.state.collectAsStateWithLifecycle()
        ReviewScreen(
            state = state,
            onEvent = viewModel::onEvent,
            onDone = { navController.popBackStack() },
            onBack = { navController.popBackStack() },
        )
    }
}

/**
 * §9.3's centre speed dial, with the options that actually exist.
 *
 * A sheet rather than a floating cluster of mini-FABs: two options do not need
 * an animated fan, and a sheet gets the insets, the scrim, the back gesture and
 * the touch targets right without any of it being hand-placed. `Scan receipt`
 * is **absent rather than disabled** until P4 — a control that cannot do
 * anything is worse than its absence.
 *
 * The Inbox row carries its count in the label rather than as a superscript
 * badge, so it survives font scale 2.0 by wrapping like any other label (BUG9).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CentreActionDial(
    pendingCount: Int,
    onDismiss: () -> Unit,
    onManualEntry: () -> Unit,
    onInbox: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = LfTheme.colors.surfaceRaised) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(
                    start = LfTheme.spacing.md,
                    end = LfTheme.spacing.md,
                    bottom = LfTheme.spacing.md,
                ),
            verticalArrangement = Arrangement.spacedBy(LfTheme.spacing.sm),
        ) {
            LfButton(
                text = "Manual entry",
                onClick = onManualEntry,
                modifier = Modifier.fillMaxWidth(),
            )
            LfButton(
                text = if (pendingCount > 0) "Inbox ($pendingCount)" else "Inbox",
                style = LfButtonStyle.Tonal,
                onClick = onInbox,
                modifier = Modifier.fillMaxWidth(),
            )
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
        is Destination.Entry -> "Add"
        Destination.Categories -> "Categories"
        Destination.Export -> "Export"
        Destination.DeletedEntries -> "Deleted"
        Destination.Inbox -> "Inbox"
        is Destination.InboxReview -> "Review"
    }

private val Destination.icon
    get() = when (this) {
        Destination.Dashboard -> LfIcons.Dashboard
        Destination.Ledger -> LfIcons.Ledger
        Destination.Analytics -> LfIcons.Analytics
        else -> LfIcons.More
    }
