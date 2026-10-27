package com.ledgerflow.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.compose.composable
import androidx.navigation.toRoute
import com.ledgerflow.core.domain.analytics.AlertChannelState
import com.ledgerflow.feature.budget.notify.BudgetNotifications
import com.ledgerflow.feature.settings.ProfileScreen
import com.ledgerflow.feature.settings.SettingsEvent
import com.ledgerflow.feature.settings.SettingsIndexScreen
import com.ledgerflow.feature.settings.SettingsPage
import com.ledgerflow.feature.settings.SettingsSectionScreen
import com.ledgerflow.feature.settings.SettingsUiState
import com.ledgerflow.feature.settings.SettingsViewModel
import com.ledgerflow.feature.settings.settingsPageNamed

/**
 * Profile, the Settings index and the section pages (design-system step D4).
 *
 * Back unwinds them in the order they were opened: a section, then Settings,
 * then Profile. Settings and its pages are full-screen, as Categories is, so
 * the bar shows on Profile alone.
 */
internal fun NavGraphBuilder.profileAndSettingsDestinations(navController: NavHostController) {
    composable<Destination.Profile> {
        ProfileScreen(
            onSettings = { navController.navigate(Destination.Settings) },
            onExport = { navController.navigate(Destination.Export) },
        )
    }
    composable<Destination.Settings> {
        val viewModel: SettingsViewModel = hiltViewModel()
        val state by viewModel.state.collectAsStateWithLifecycle()
        // The index shows capture and the lock as they are now, so it re-reads
        // on resume like the pages do.
        LifecycleResumeEffect(Unit) {
            viewModel.refresh()
            onPauseOrDispose { }
        }
        SettingsIndexScreen(
            state = state,
            onPage = { page -> navController.navigate(settingsRouteFor(page)) },
            onBack = navController::popBackStack,
        )
    }
    composable<Destination.SettingsSection> { entry ->
        val page = settingsPageNamed(entry.toRoute<Destination.SettingsSection>().section)
        if (page == null) {
            // A name no page has (a page renamed under a restored back stack):
            // leave rather than draw nothing.
            LaunchedEffect(Unit) { navController.popBackStack() }
            return@composable
        }
        val viewModel: SettingsViewModel = hiltViewModel()
        val state by viewModel.state.collectAsStateWithLifecycle()
        val context = LocalContext.current
        // §5.2's resume poll, for the row that reports whether capture works.
        // The user's route to fixing it leaves the app entirely, so the resume
        // afterwards is the only moment the row can stop being wrong.
        LifecycleResumeEffect(Unit) {
            viewModel.refresh()
            onPauseOrDispose { }
        }
        SettingsSectionRoute(
            page = page,
            state = state,
            navigate = navController::navigate,
            onBack = navController::popBackStack,
            // Android's own page for the channel (V2-PLAN §3.4): the channel is
            // the switch. The resume poll above re-reads it on the way back.
            onBudgetAlerts = {
                val intent = BudgetNotifications.settingsIntent(
                    context,
                    state.budgetAlerts ?: AlertChannelState.ON,
                )
                // An OEM build without the page is a row that does nothing,
                // never a crash.
                runCatching { context.startActivity(intent) }
            },
            onEvent = viewModel::onEvent,
        )
    }
}

/** The route that opens [page]; `settingsPageNamed` reads it back. */
internal fun settingsRouteFor(page: SettingsPage): Destination = Destination.SettingsSection(page.name)

/**
 * A section page with its rows wired to their destinations.
 *
 * Split from the graph so `ProfileSettingsRoutesTest` can tap every row and
 * see where it goes without a ViewModel: the wiring is the part a move like D4
 * can break while every screen still looks right.
 */
@Composable
internal fun SettingsSectionRoute(
    page: SettingsPage,
    state: SettingsUiState,
    navigate: (Destination) -> Unit,
    onBack: () -> Unit,
    onBudgetAlerts: () -> Unit,
    onEvent: (SettingsEvent) -> Unit,
) {
    SettingsSectionScreen(
        page = page,
        state = state,
        onBack = onBack,
        onCategories = { navigate(Destination.Categories) },
        onBudgets = { navigate(Destination.Budgets) },
        onExport = { navigate(Destination.Export) },
        onDeletedEntries = { navigate(Destination.DeletedEntries) },
        onBackUp = { navigate(Destination.BackUpNow) },
        onNotificationAccess = { navigate(Destination.NotificationAccess) },
        onDiagnostics = { navigate(Destination.Diagnostics) },
        onPaymentApps = { navigate(Destination.PaymentApps) },
        onBudgetAlerts = onBudgetAlerts,
        onEvent = onEvent,
    )
}
