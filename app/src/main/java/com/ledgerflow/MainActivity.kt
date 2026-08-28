package com.ledgerflow

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.compose.ui.Modifier
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ledgerflow.core.designsystem.component.LfScaffold
import com.ledgerflow.core.designsystem.theme.LfTheme
import com.ledgerflow.feature.onboarding.OnboardingScreen
import com.ledgerflow.feature.onboarding.OnboardingViewModel
import com.ledgerflow.feature.onboarding.recovery.RecoveryScreen
import com.ledgerflow.feature.onboarding.upgrade.UpgradeBlockedScreen
import com.ledgerflow.feature.onboarding.upgrade.UpgradingScreen
import com.ledgerflow.feature.onboarding.recovery.RecoveryViewModel
import com.ledgerflow.navigation.LedgerFlowShell
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * Single Activity (SPEC.md §9.3).
 *
 * `enableEdgeToEdge()` is called before `setContent` and stays there. Android
 * 15+ enforces edge-to-edge anyway; calling it explicitly means the behaviour
 * is the same on every API level we support rather than changing under us at
 * 35. Every screen consumes `WindowInsets.safeDrawing` through `LfScaffold`,
 * which is BUG5's countermeasure -- retrofitting insets later is the expensive
 * path.
 */
@AndroidEntryPoint
public class MainActivity : ComponentActivity() {

    /**
     * The deep link waiting to be honoured (SPEC.md §5.1). P2-7.
     *
     * **Held rather than handled, because the nav graph may not exist yet.**
     * `LedgerFlowApp` gates the whole graph behind [AppRoute.Ready], so a
     * notification tap on a cold start arrives while the vault is still
     * opening and there is nothing to navigate. Navigation Compose's own
     * `navDeepLink` reads the Activity's intent when the graph is set, which
     * would happen to work for that case and silently not for the others —
     * this is explicit instead, and covers a warm tap through [onNewIntent] as
     * well.
     *
     * Cleared once the shell has acted on it, so a configuration change does
     * not re-navigate the user to a candidate they have already reviewed.
     */
    private val deepLink = MutableStateFlow<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        deepLink.value = intent?.dataString

        setContent {
            LfTheme {
                LedgerFlowApp(
                    deepLink = deepLink.collectAsStateWithLifecycle().value,
                    onDeepLinkHandled = { deepLink.value = null },
                )
            }
        }
    }

    /**
     * A tap while the app is already running.
     *
     * `singleTop` in the manifest is what routes it here instead of stacking a
     * second Activity. `setIntent` keeps `getIntent()` honest for anything that
     * reads it later; without it the Activity would keep reporting the intent
     * it was originally launched with.
     */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        deepLink.value = intent.dataString
    }
}

/**
 * The vault decides what is on screen (SPEC.md §7.3).
 *
 * There is no route to the ledger that does not pass through an unlocked vault,
 * and no branch here that destroys anything: a failure to unlock lands on
 * [RecoveryScreen], which is a screen the user acts on.
 *
 * Navigation Compose and the bottom bar land in the next step; until then
 * [AppRoute.Ready] renders a placeholder rather than a half-built nav graph.
 */
@Composable
private fun LedgerFlowApp(
    deepLink: String?,
    onDeepLinkHandled: () -> Unit,
) {
    val appViewModel: AppViewModel = hiltViewModel()
    val route by appViewModel.route.collectAsStateWithLifecycle()

    when (val current = route) {
        AppRoute.Loading -> LoadingScreen()

        AppRoute.Onboarding -> {
            val viewModel: OnboardingViewModel = hiltViewModel()
            // collectAsStateWithLifecycle, never bare collectAsState
            // (CLAUDE.md §5): the latter keeps collecting while backgrounded.
            val state by viewModel.state.collectAsStateWithLifecycle()
            OnboardingScreen(
                state = state,
                onEvent = viewModel::onEvent,
                onGeneratePhrase = viewModel::generatePhraseAndContinue,
                kitFileName = viewModel::suggestedKitFileName,
            )
        }

        is AppRoute.Recovery -> {
            val viewModel: RecoveryViewModel = hiltViewModel()
            val state by viewModel.state.collectAsStateWithLifecycle()
            LaunchedEffect(current.reason) { viewModel.setReason(current.reason) }
            RecoveryScreen(state = state, onEvent = viewModel::onEvent)
        }

        // §8.1: a dedicated screen that owns the upgrade, not a spinner over
        // whatever happened to be showing.
        is AppRoute.Upgrading -> UpgradingScreen(from = current.from, to = current.to)

        is AppRoute.UpgradeBlocked -> UpgradeBlockedScreen(reason = current.reason)

        AppRoute.Ready -> {
            // Asked here rather than at launch: the permission exists to
            // announce a pending_transaction row, and a user still in
            // onboarding has no ledger for one to belong to. Asking at the
            // first moment the app is actually usable is also the moment the
            // request makes sense to read.
            RequestNotificationPermissionOnce()
            LedgerFlowShell(
                deepLink = deepLink,
                onDeepLinkHandled = onDeepLinkHandled,
            )
        }
    }
}

/**
 * `POST_NOTIFICATIONS`, once per install (SPEC.md §5.1, API 33+). P2-7.
 *
 * **This is the minimum that makes P2-7 work, and it is not the permission UX.**
 * P2-8 owns the explainer, the notification-listener grant, the rebind and the
 * §5.2 health banner; this is a bare system dialog so that the notification half
 * is demonstrable before that lands. It should be *replaced* by P2-8's flow, not
 * decorated.
 *
 * `rememberSaveable` keeps it to one ask per process even across a rotation. It
 * deliberately does not re-ask on a later launch after a denial: Android stops
 * showing the dialog after two refusals anyway, and a request that silently
 * cannot appear is worse than none — the user's route back is system settings,
 * which is exactly what P2-8's banner is for.
 */
@Composable
private fun RequestNotificationPermissionOnce() {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return

    val context = LocalContext.current
    var asked by rememberSaveable { mutableStateOf(false) }
    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { /* Denied is a legitimate answer; the Inbox never depended on it. */ }

    LaunchedEffect(Unit) {
        if (asked) return@LaunchedEffect
        asked = true
        val granted = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.POST_NOTIFICATIONS,
        ) == PackageManager.PERMISSION_GRANTED
        if (!granted) launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
    }
}

@Composable
private fun LoadingScreen() {
    LfScaffold { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).padding(LfTheme.spacing.lg),
            verticalArrangement = Arrangement.spacedBy(LfTheme.spacing.sm),
        ) {
            Text(
                text = "Unlocking",
                style = LfTheme.typography.titleM,
                color = LfTheme.colors.textPrimary,
            )
        }
    }
}

