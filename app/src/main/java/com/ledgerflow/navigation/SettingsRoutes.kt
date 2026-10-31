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
import com.ledgerflow.feature.settings.AboutLink
import com.ledgerflow.feature.settings.SettingsEvent
import com.ledgerflow.feature.settings.about.AboutDocument
import com.ledgerflow.feature.settings.about.AboutDocumentScreen
import com.ledgerflow.feature.settings.about.AboutDocumentViewModel
import com.ledgerflow.feature.settings.about.HelpScreen
import com.ledgerflow.feature.settings.about.HelpViewModel
import com.ledgerflow.feature.settings.about.LicenceScreen
import com.ledgerflow.feature.settings.about.LicenceViewModel
import com.ledgerflow.feature.settings.about.LicencesScreen
import com.ledgerflow.feature.settings.about.LicencesViewModel
import com.ledgerflow.feature.settings.about.ReportBugScreen
import com.ledgerflow.feature.settings.about.ReportBugViewModel
import com.ledgerflow.feature.settings.about.aboutDocumentNamed
import com.ledgerflow.feature.settings.about.shareBugReport
import com.ledgerflow.feature.settings.SettingsIndexScreen
import com.ledgerflow.feature.settings.SettingsPage
import com.ledgerflow.feature.settings.SettingsSectionScreen
import com.ledgerflow.feature.settings.SettingsUiState
import com.ledgerflow.feature.settings.SettingsViewModel
import com.ledgerflow.feature.settings.permissions.DataPermissionsScreen
import com.ledgerflow.feature.settings.permissions.DataPermissionsViewModel
import com.ledgerflow.feature.settings.permissions.openPermissionSettings
import com.ledgerflow.feature.settings.settingsPageNamed

/**
 * Profile, the Settings index and the section pages (design-system step D4).
 *
 * Back unwinds them in the order they were opened: a section, then Settings,
 * then Profile -- or, for Organise and Data, which Profile opens itself
 * (BUG65), straight back to Profile. Settings and its pages are full-screen,
 * as Categories is, so the bar shows on Profile alone.
 */
internal fun NavGraphBuilder.profileAndSettingsDestinations(navController: NavHostController) {
    composable<Destination.Profile> {
        // Organise and Data open straight from Profile (BUG65), so Back from
        // them returns here rather than to Settings.
        ProfileScreen(
            onPage = { page -> navController.navigate(settingsRouteFor(page)) },
            onSettings = { navController.navigate(Destination.Settings) },
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

/**
 * The pages Security & privacy opens (V2-3): Data permissions. Its rows open
 * Android's own settings, so the page is re-read on every resume.
 */
internal fun NavGraphBuilder.securityDestinations(navController: NavHostController) {
    composable<Destination.DataPermissions> {
        val viewModel: DataPermissionsViewModel = hiltViewModel()
        val state by viewModel.state.collectAsStateWithLifecycle()
        val context = LocalContext.current
        // The user changes these in Android's settings: the rows say what is
        // true when they come back.
        LifecycleResumeEffect(Unit) {
            viewModel.refresh()
            onPauseOrDispose { }
        }
        DataPermissionsScreen(
            state = state,
            onOpen = { kind -> openPermissionSettings(context, kind) },
            onBack = navController::popBackStack,
        )
    }
}

/** The pages About opens (V2-3, V2-PLAN §3.8). */
internal fun NavGraphBuilder.aboutDestinations(navController: NavHostController) {
    composable<Destination.AboutDocument> { entry ->
        val document = aboutDocumentNamed(entry.toRoute<Destination.AboutDocument>().document)
        if (document == null) {
            LaunchedEffect(Unit) { navController.popBackStack() }
            return@composable
        }
        val viewModel: AboutDocumentViewModel = hiltViewModel()
        val state by viewModel.state.collectAsStateWithLifecycle()
        LaunchedEffect(document) { viewModel.load(document) }
        state?.let { AboutDocumentScreen(state = it, onBack = navController::popBackStack) }
    }
    composable<Destination.Help> {
        val viewModel: HelpViewModel = hiltViewModel()
        val state by viewModel.state.collectAsStateWithLifecycle()
        HelpScreen(state = state, onToggle = viewModel::toggle, onBack = navController::popBackStack)
    }
    composable<Destination.ReportBug> {
        val viewModel: ReportBugViewModel = hiltViewModel()
        val state by viewModel.state.collectAsStateWithLifecycle()
        val context = LocalContext.current
        ReportBugScreen(
            state = state,
            onDescribe = viewModel::describe,
            // The text the screen shows, and nothing else (V2-PLAN §3.8).
            onShare = { shareBugReport(context, state.report) },
            onBack = navController::popBackStack,
        )
    }
    composable<Destination.Licences> {
        val viewModel: LicencesViewModel = hiltViewModel()
        val state by viewModel.state.collectAsStateWithLifecycle()
        LicencesScreen(
            state = state,
            onOpen = { id -> navController.navigate(Destination.Licence(id)) },
            onBack = navController::popBackStack,
        )
    }
    composable<Destination.Licence> { entry ->
        val id = entry.toRoute<Destination.Licence>().component
        val viewModel: LicenceViewModel = hiltViewModel()
        val state by viewModel.state.collectAsStateWithLifecycle()
        LaunchedEffect(id) { viewModel.load(id) }
        state?.let { LicenceScreen(state = it, onBack = navController::popBackStack) }
    }
}

/** Where each About row leads. */
internal fun aboutRouteFor(link: AboutLink): Destination = when (link) {
    AboutLink.WhatsNew -> Destination.AboutDocument(AboutDocument.WhatsNew.name)
    AboutLink.Help -> Destination.Help
    AboutLink.ReportBug -> Destination.ReportBug
    AboutLink.PrivacyPolicy -> Destination.AboutDocument(AboutDocument.PrivacyPolicy.name)
    AboutLink.Terms -> Destination.AboutDocument(AboutDocument.Terms.name)
    AboutLink.Licences -> Destination.Licences
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
        onDataPermissions = { navigate(Destination.DataPermissions) },
        onAbout = { link -> navigate(aboutRouteFor(link)) },
        onBudgetAlerts = onBudgetAlerts,
        onEvent = onEvent,
    )
}
