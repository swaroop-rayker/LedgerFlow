package com.ledgerflow

import android.os.Bundle
import androidx.activity.ComponentActivity
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
import androidx.compose.ui.Modifier
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ledgerflow.core.designsystem.component.LfScaffold
import com.ledgerflow.core.designsystem.theme.LfTheme
import com.ledgerflow.feature.onboarding.OnboardingScreen
import com.ledgerflow.feature.onboarding.OnboardingViewModel
import com.ledgerflow.feature.onboarding.recovery.RecoveryScreen
import com.ledgerflow.feature.onboarding.recovery.RecoveryViewModel
import dagger.hilt.android.AndroidEntryPoint

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

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        setContent {
            LfTheme {
                LedgerFlowApp()
            }
        }
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
private fun LedgerFlowApp() {
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

        AppRoute.Ready -> UnlockedPlaceholder()
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

/**
 * Proof the database opened, and nothing more.
 *
 * Replaced by the real shell -- NavHost, bottom bar, FAB -- in the next step.
 * Kept deliberately blunt so it cannot be mistaken for a finished screen.
 */
@Composable
private fun UnlockedPlaceholder() {
    LfScaffold { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).padding(LfTheme.spacing.lg),
            verticalArrangement = Arrangement.spacedBy(LfTheme.spacing.sm),
        ) {
            Text(
                text = "Vault unlocked",
                style = LfTheme.typography.displayL,
                color = LfTheme.colors.textPrimary,
            )
            Text(
                text = "The database is open and the canary verified. The ledger, " +
                    "entry form and navigation arrive in the next steps.",
                style = LfTheme.typography.bodyL,
                color = LfTheme.colors.textSecondary,
            )
        }
    }
}
