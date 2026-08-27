package com.ledgerflow.navigation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ledgerflow.core.domain.usecase.ObservePendingCountUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn

/**
 * What the shell itself needs to render, as opposed to any one screen.
 *
 * Only the `Inbox (n)` count so far (SPEC.md §9.3). It lives here rather than on
 * `AppViewModel` because that one decides *routing* — which of onboarding,
 * recovery or the app is on screen — and a badge is not a routing decision.
 * Folding it in would have made the class that answers "is the vault open?" also
 * answer "how many bank messages are waiting?".
 */
@HiltViewModel
public class ShellViewModel @Inject constructor(
    observePendingCount: ObservePendingCountUseCase,
) : ViewModel() {

    /**
     * Pending and unsuppressed — work the user actually has to do.
     *
     * A suppressed duplicate is deliberately not counted (§3.1 keeps it visible,
     * not actionable): a badge that sent someone to a screen where nothing needs
     * their attention teaches them to ignore the badge.
     */
    public val pendingCount: StateFlow<Int> = observePendingCount().stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS),
        initialValue = 0,
    )

    private companion object {
        const val STOP_TIMEOUT_MS = 5_000L
    }
}
