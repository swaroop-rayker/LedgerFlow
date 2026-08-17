package com.ledgerflow

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ledgerflow.core.designsystem.theme.LfTheme
import com.ledgerflow.feature.onboarding.OnboardingScreen
import com.ledgerflow.feature.onboarding.OnboardingViewModel
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
                // hiltViewModel() rather than by viewModels(): scoping moves to
                // the nav back stack entry once the NavHost lands, and starting
                // from the activity-scoped delegate would mean every screen
                // sharing one ViewModel until someone noticed.
                val onboardingViewModel: OnboardingViewModel = hiltViewModel()

                // collectAsStateWithLifecycle, never bare collectAsState
                // (CLAUDE.md §5): the latter keeps collecting while the app is
                // backgrounded.
                val state by onboardingViewModel.state.collectAsStateWithLifecycle()

                OnboardingScreen(
                    state = state,
                    onEvent = onboardingViewModel::onEvent,
                    onGeneratePhrase = onboardingViewModel::generatePhraseAndContinue,
                )
            }
        }
    }
}
