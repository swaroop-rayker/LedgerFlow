package com.ledgerflow.feature.dashboard

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ledgerflow.core.common.time.LocalDates
import com.ledgerflow.core.domain.backup.BackupReminder
import com.ledgerflow.core.domain.backup.ObserveLastBackupUseCase
import com.ledgerflow.core.domain.diagnostics.DismissCrashReportsUseCase
import com.ledgerflow.core.domain.diagnostics.ObserveCrashReportsUseCase
import com.ledgerflow.core.domain.diagnostics.SaveCrashReportsUseCase
import com.ledgerflow.core.domain.usecase.AcknowledgeCaptureGapUseCase
import com.ledgerflow.core.domain.usecase.GetNotificationCaptureHealthUseCase
import com.ledgerflow.core.domain.usecase.ObserveCaptureGapUseCase
import com.ledgerflow.core.model.LedgerType
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.onStart
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
    private val lastBackup: ObserveLastBackupUseCase,
    private val observeCaptureGap: ObserveCaptureGapUseCase,
    private val acknowledgeCaptureGap: AcknowledgeCaptureGapUseCase,
    private val observeCrashReports: ObserveCrashReportsUseCase,
    private val saveCrashReports: SaveCrashReportsUseCase,
    private val dismissCrashReports: DismissCrashReportsUseCase,
    private val home: HomeReads,
) : ViewModel() {

    private val ledger = home.ledger

    private val _state = MutableStateFlow(DashboardUiState())
    public val state: StateFlow<DashboardUiState> = _state.asStateFlow()

    /**
     * Asks for the month's figures again (V2-PLAN BUG-C). Fired by every
     * change to either book and by every resume.
     *
     * **Queued behind the read in progress, never cancelling it.** At launch
     * three requests land together (the first read, the first resume, both
     * books' first emission); with `collectLatest` each one threw away the
     * read in progress and began again. Now that read finishes and shows, and
     * requests arriving meanwhile collapse into one follow-up read (the buffer
     * holds one; `conflate` keeps it that way), so a burst of approvals reads
     * once more, not once each. Measured on LF Bench, this did **not** change
     * time to full display (~2.65 s either way, 2026-10-16): the cost is in the
     * read itself, not in restarting it.
     */
    private val summaryRequests = MutableSharedFlow<Unit>(extraBufferCapacity = 1)

    /**
     * The last verified backup, once read. Kept rather than only its verdict,
     * because the verdict goes stale on its own: a backup six days old when
     * Home opened is eight days old two days later, with nothing emitting.
     */
    private var lastBackupRead: Boolean = false
    private var lastBackupAt: Long? = null

    init {
        // The observation's first emission is also the initial read, so there is
        // no separate `refresh()` here -- the resume effect on the route fires
        // one on first composition anyway.
        viewModelScope.launch {
            getCaptureHealth.observe().collect { health ->
                _state.update { it.copy(captureHealth = health) }
            }
        }
        // A backup made on the Back up now screen lands here as a new date,
        // and the reminder goes without a resume.
        viewModelScope.launch {
            lastBackup().collect { at ->
                lastBackupRead = true
                lastBackupAt = at
                judgeBackup()
            }
        }
        // Written from a receiver while this screen may be open, so observed
        // rather than read on resume (§16 Q16).
        viewModelScope.launch {
            observeCaptureGap().collect { gap -> _state.update { it.copy(captureGap = gap) } }
        }
        viewModelScope.launch {
            observeCrashReports().collect { reports -> _state.update { it.copy(crashReports = reports) } }
        }
        viewModelScope.launch {
            home.observePendingCount().collect { count -> _state.update { it.copy(pendingCount = count) } }
        }
        // Two reads, one per book, never one list of both (Law 2). Either
        // re-emits on any write to the ledger, which is also how an approval
        // made from a notification while Home is open reaches the figures.
        viewModelScope.launch {
            combine(
                ledger.observeRecent(LedgerType.DEBIT, RECENT_ROWS),
                ledger.observeRecent(LedgerType.CREDIT, RECENT_ROWS),
            ) { spending, income -> spending to income }
                .collect { (spending, income) ->
                    _state.update { it.copy(recentSpending = spending, recentIncome = income) }
                    summaryRequests.tryEmit(Unit)
                }
        }
        viewModelScope.launch {
            summaryRequests
                .onStart { emit(Unit) }
                .conflate()
                .collect { loadSummary() }
        }
    }

    private suspend fun loadSummary() {
        val currency = ledger.baseCurrency() ?: DashboardUiState.DEFAULT_CURRENCY
        val summary = home.getHomeSummary(today = LocalDates.of(home.clock.nowMillis()))
        _state.update { it.copy(summary = summary, baseCurrency = currency) }
    }

    /** What the file picker suggests for "Save report". */
    public fun crashReportFileName(): String = saveCrashReports.suggestedFileName()

    /** The user picked a file: write the reports there, and let them go only if that worked. */
    public fun saveCrashReports(destinationUri: String) {
        viewModelScope.launch {
            val saved = saveCrashReports.invoke(destinationUri)
            _state.update { it.copy(crashSaveFailed = !saved) }
        }
    }

    /** "Dismiss" on the crash card. */
    public fun dismissCrashReports() {
        viewModelScope.launch {
            dismissCrashReports.invoke()
            _state.update { it.copy(crashSaveFailed = false) }
        }
    }

    /** "Got it" on the capture-gap card: the user has seen the window. */
    public fun acknowledgeCaptureGap() {
        viewModelScope.launch { acknowledgeCaptureGap.invoke() }
    }

    private fun judgeBackup() {
        if (!lastBackupRead) return
        val now = lastBackup.now()
        val at = lastBackupAt
        _state.update {
            it.copy(
                backupReminder = BackupReminder.of(at, now),
                lastBackup = if (at == null) LastBackup.Never else LastBackup.DaysAgo((now - at) / DAY_MILLIS),
            )
        }
    }

    /** §5.2's resume poll. The grant half; see the class KDoc for why both exist. */
    public fun refresh() {
        viewModelScope.launch {
            val health = getCaptureHealth()
            _state.update { it.copy(captureHealth = health) }
        }
        // Time passed while the app was away; the same date may now be stale.
        judgeBackup()
        // ...and "this month" may now be next month.
        summaryRequests.tryEmit(Unit)
    }

    private companion object {
        /** V2-PLAN's "last five". Home orients; the Ledger is the list. */
        const val RECENT_ROWS = 5

        const val DAY_MILLIS = 24L * 60L * 60L * 1000L
    }
}
