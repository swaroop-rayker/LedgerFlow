package com.ledgerflow.feature.settings.diagnostics

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ledgerflow.core.domain.diagnostics.DiagnosticsWindow
import com.ledgerflow.core.domain.diagnostics.IngestDiagnostics
import com.ledgerflow.core.domain.usecase.GetIngestDiagnosticsUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Everything the diagnostics screen asks its ViewModel to do. */
public sealed interface DiagnosticsEvent {

    public data class WindowSelected(val window: DiagnosticsWindow) : DiagnosticsEvent

    /**
     * The screen came back to the foreground. Re-read, because messages keep
     * arriving while the user is elsewhere and a report is a snapshot.
     */
    public data object Resumed : DiagnosticsEvent
}

/** The report, or why there is none yet. */
public sealed interface DiagnosticsReport {

    /** Not read yet. The screen shows nothing rather than zeroes it has not counted. */
    public data object Loading : DiagnosticsReport

    /** The vault could not be opened to count. */
    public data object Unavailable : DiagnosticsReport

    /**
     * `@Immutable` on the wrapper because [IngestDiagnostics] lives in
     * `:core:domain`, which the Compose compiler does not see. It is data
     * classes of `val`s, enums and read-only lists built fresh per read, so
     * nothing in it changes after construction.
     */
    @Immutable
    public data class Ready(val diagnostics: IngestDiagnostics) : DiagnosticsReport
}

/** The diagnostics screen's state (SPEC.md §13 P5). */
@Immutable
public data class DiagnosticsUiState(
    /** 30 days by default (owner, 2026-09-30). */
    val window: DiagnosticsWindow = DiagnosticsWindow.LAST_30_DAYS,
    val report: DiagnosticsReport = DiagnosticsReport.Loading,
)

@HiltViewModel
public class DiagnosticsViewModel @Inject constructor(
    private val getDiagnostics: GetIngestDiagnosticsUseCase,
) : ViewModel() {

    private val mutableState = MutableStateFlow(DiagnosticsUiState())
    public val state: StateFlow<DiagnosticsUiState> = mutableState.asStateFlow()

    /**
     * The read in flight, cancelled by the next one: switching windows twice
     * quickly must not let the slower, older read land last and show the
     * report for a window that is no longer selected.
     */
    private var load: Job? = null

    // No read in `init`: the route sends [DiagnosticsEvent.Resumed] on first
    // display as well as on every return, and reading in both places would
    // count everything twice each time the screen opens.

    public fun onEvent(event: DiagnosticsEvent) {
        when (event) {
            is DiagnosticsEvent.WindowSelected -> {
                if (event.window == mutableState.value.window) return
                mutableState.update { it.copy(window = event.window) }
                reload()
            }

            DiagnosticsEvent.Resumed -> reload()
        }
    }

    private fun reload() {
        load?.cancel()
        val window = mutableState.value.window
        load = viewModelScope.launch {
            val report = getDiagnostics(window)
                ?.let(DiagnosticsReport::Ready)
                ?: DiagnosticsReport.Unavailable
            mutableState.update { it.copy(report = report) }
        }
    }
}
