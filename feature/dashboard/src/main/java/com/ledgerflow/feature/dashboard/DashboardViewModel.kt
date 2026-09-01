package com.ledgerflow.feature.dashboard

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ledgerflow.core.domain.usecase.GetNotificationCaptureHealthUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Home's ViewModel (SPEC.md §5.2).
 *
 * **Both a poll and an observation, because the banner's happy path needs them
 * in sequence.** The user taps it, grants access in system Settings, and returns
 * — that is the resume poll. The listener then binds a moment later, while Home
 * is already on screen and no resume is coming — that is the observation. With
 * only the poll, the banner would still be sitting there telling them to do the
 * thing they had just done, until they navigated away and back.
 *
 * One `_state`, two writers, and they cannot disagree: both run the same
 * evaluation over the same two inputs, so the later one is simply the fresher
 * answer.
 */
@HiltViewModel
public class DashboardViewModel @Inject constructor(
    private val getCaptureHealth: GetNotificationCaptureHealthUseCase,
) : ViewModel() {

    private val _state = MutableStateFlow(DashboardUiState())
    public val state: StateFlow<DashboardUiState> = _state.asStateFlow()

    init {
        // The observation's first emission is also the initial read, so there is
        // no separate `refresh()` here -- the resume effect on the route fires
        // one on first composition anyway.
        viewModelScope.launch {
            getCaptureHealth.observe().collect { health ->
                _state.update { it.copy(captureHealth = health) }
            }
        }
    }

    /** §5.2's resume poll. The grant half; see the class KDoc for why both exist. */
    public fun refresh() {
        viewModelScope.launch {
            val health = getCaptureHealth()
            _state.update { it.copy(captureHealth = health) }
        }
    }
}
