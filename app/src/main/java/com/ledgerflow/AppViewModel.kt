package com.ledgerflow

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ledgerflow.core.domain.usecase.ObserveVaultStateUseCase
import com.ledgerflow.core.domain.usecase.OpenVaultOnLaunchUseCase
import com.ledgerflow.core.domain.vault.RecoveryReason
import com.ledgerflow.core.domain.vault.VaultState
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.scan
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** What the single Activity should be showing. */
public sealed interface AppRoute {
    public data object Loading : AppRoute
    public data object Onboarding : AppRoute
    public data class Recovery(val reason: RecoveryReason) : AppRoute
    public data object Ready : AppRoute
}

/**
 * The app shell's router, driven by the vault (SPEC.md §7.3).
 *
 * It observes the vault rather than holding it: [ObserveVaultStateUseCase] is
 * the only vault-facing dependency here besides the launch attempt, so the shell
 * cannot initialise or recover a vault by accident.
 */
@HiltViewModel
public class AppViewModel @Inject constructor(
    observeVaultState: ObserveVaultStateUseCase,
    private val openVaultOnLaunch: OpenVaultOnLaunchUseCase,
) : ViewModel() {

    /**
     * [VaultState.Working] deliberately does not map to a route.
     *
     * It occurs *inside* onboarding (creating the DEK and database) and inside
     * recovery (running the KDF). Both screens show their own progress, and both
     * own the words the user is mid-way through. Swapping either out for a
     * generic spinner would throw away that context and, on the onboarding path,
     * briefly replace "Setting up your ledger" with a blank screen. So Working
     * holds the previous route and the screen underneath keeps rendering.
     */
    public val route: StateFlow<AppRoute> = observeVaultState()
        .scan<VaultState, AppRoute>(AppRoute.Loading) { previous, state ->
            when (state) {
                VaultState.Initializing -> AppRoute.Loading
                VaultState.NeedsOnboarding -> AppRoute.Onboarding
                VaultState.Unlocked -> AppRoute.Ready
                is VaultState.NeedsRecovery -> AppRoute.Recovery(state.reason)
                VaultState.Working -> previous
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), AppRoute.Loading)

    init {
        // §7.3 step 1. Idempotent, so a config change does not re-open anything.
        viewModelScope.launch { openVaultOnLaunch() }
    }

    private companion object {
        private const val STOP_TIMEOUT_MS = 5_000L
    }
}
