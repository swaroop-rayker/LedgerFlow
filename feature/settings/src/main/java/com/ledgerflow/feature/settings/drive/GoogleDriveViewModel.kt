package com.ledgerflow.feature.settings.drive

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ledgerflow.core.domain.drive.DriveStatus
import com.ledgerflow.core.domain.drive.ObserveDriveStatusUseCase
import com.ledgerflow.core.domain.drive.SetDriveEnabledUseCase
import com.ledgerflow.core.drive.DriveSignIn
import com.ledgerflow.core.drive.DriveSignInStep
import com.ledgerflow.feature.settings.work.DriveUploadWorker
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Settings > Data > Google Drive (ADR-0034; owner's layout A, 2026-11-07). */
@Immutable
public data class GoogleDriveUiState(
    val status: DriveStatus = DriveStatus(enabled = false, lastAttempt = null, lastCopiedAt = null),
    /** Signing in or turning off: the actions wait. */
    val isWorking: Boolean = false,
    /** Google's consent sheet, to launch once; [GoogleDriveEvent.ConsentLaunched] clears it. */
    val consent: PendingIntent? = null,
    val confirmingOff: Boolean = false,
    val message: String? = null,
) {
    /** The status asks for a sign-in: the page offers it in place of Copy now. */
    val needsSignIn: Boolean get() = status.enabled && driveTone(status) == DriveTone.NeedsYou
}

public sealed interface GoogleDriveEvent {
    public data object TurnOn : GoogleDriveEvent

    public data object ConsentLaunched : GoogleDriveEvent

    /** The consent sheet's result intent; null when it was dismissed. */
    public data class ConsentReturned(val data: Intent?) : GoogleDriveEvent

    public data object CopyNow : GoogleDriveEvent

    public data object TurnOffRequested : GoogleDriveEvent

    public data object TurnOffConfirmed : GoogleDriveEvent

    public data object TurnOffDismissed : GoogleDriveEvent

    public data object MessageShown : GoogleDriveEvent
}

/** Queues a copy to Drive; a seam so the page's tests need no WorkManager. */
public fun interface DriveCopyQueue {
    public fun enqueue()
}

/** The app's queue: [DriveUploadWorker]. */
public class WorkManagerDriveCopyQueue @Inject constructor(
    @param:ApplicationContext private val context: Context,
) : DriveCopyQueue {
    override fun enqueue(): Unit = DriveUploadWorker.enqueue(context)
}

/**
 * Turning on asks Play services for the `drive.file` grant: silently when it
 * stands, through Google's consent sheet when it does not. Only a granted
 * answer turns the switch on, and then the first copy is queued at once
 * rather than waiting for the night. Turning off stops uploads and revokes the
 * grant; **the files already on Drive are left there** (ADR-0034 §6).
 */
@HiltViewModel
public class GoogleDriveViewModel @Inject constructor(
    observeStatus: ObserveDriveStatusUseCase,
    private val setEnabled: SetDriveEnabledUseCase,
    private val signIn: DriveSignIn,
    private val copies: DriveCopyQueue,
) : ViewModel() {

    private val local = MutableStateFlow(GoogleDriveUiState())

    public val state: StateFlow<GoogleDriveUiState> = combine(observeStatus(), local) { status, extra ->
        extra.copy(status = status)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), GoogleDriveUiState())

    public fun onEvent(event: GoogleDriveEvent) {
        when (event) {
            GoogleDriveEvent.TurnOn -> work { settle(signIn.begin()) }
            GoogleDriveEvent.ConsentLaunched -> local.update { it.copy(consent = null) }
            is GoogleDriveEvent.ConsentReturned -> work { settle(signIn.complete(event.data)) }
            GoogleDriveEvent.CopyNow -> {
                copies.enqueue()
                post("Copying to Drive when there's a connection.")
            }
            GoogleDriveEvent.TurnOffRequested -> local.update { it.copy(confirmingOff = true) }
            GoogleDriveEvent.TurnOffDismissed -> local.update { it.copy(confirmingOff = false) }
            GoogleDriveEvent.TurnOffConfirmed -> {
                val account = state.value.status.lastAttempt?.account
                local.update { it.copy(confirmingOff = false) }
                work {
                    setEnabled(false)
                    signIn.revoke(account)
                    post("Drive backup is off. Your copies in Drive are still there.")
                }
            }
            GoogleDriveEvent.MessageShown -> local.update { it.copy(message = null) }
        }
    }

    private suspend fun settle(step: DriveSignInStep) {
        when (step) {
            is DriveSignInStep.Granted -> {
                setEnabled(true)
                copies.enqueue()
                post("Drive backup is on. The first copy starts when there's a connection.")
            }
            is DriveSignInStep.NeedsConsent -> local.update { it.copy(consent = step.intent) }
            DriveSignInStep.Declined -> post("Drive backup stays off.")
            is DriveSignInStep.Unavailable -> post(unavailableSentence(step.reason))
        }
    }

    private fun work(block: suspend () -> Unit) {
        local.update { it.copy(isWorking = true) }
        viewModelScope.launch {
            try {
                block()
            } finally {
                local.update { it.copy(isWorking = false) }
            }
        }
    }

    private fun post(message: String) = local.update { it.copy(message = message) }

    private companion object {
        const val STOP_TIMEOUT_MS = 5_000L
    }
}
