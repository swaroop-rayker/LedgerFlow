package com.ledgerflow.feature.onboarding.restore

import com.ledgerflow.core.domain.drive.ClearDriveRestoreStagingUseCase
import com.ledgerflow.core.domain.drive.DriveBackupListing
import com.ledgerflow.core.domain.drive.DriveStaging
import com.ledgerflow.core.domain.drive.DriveUnavailable
import com.ledgerflow.core.domain.drive.ListDriveBackupsUseCase
import com.ledgerflow.core.domain.drive.StageDriveBackupUseCase
import com.ledgerflow.core.drive.DriveSignIn
import com.ledgerflow.core.drive.DriveSignInStep
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** What restoring from Google Drive needs, handed to [RestoreViewModel] as one dependency. */
public class DriveRestoreActions @Inject constructor(
    public val signIn: DriveSignIn,
    public val list: ListDriveBackupsUseCase,
    public val stage: StageDriveBackupUseCase,
    public val clear: ClearDriveRestoreStagingUseCase,
)

/**
 * Restore from Google Drive (ADR-0034 §7; owner's placement A and download on
 * pick, 2026-11-07), as steps over [RestoreUiState.drive]: choose the account in
 * Android's picker, sign in for `drive.file`, list "LedgerFlow backups", and on
 * a tap download the backup and its receipts into the staging folder. The
 * restore itself is the screen's existing one, reading
 * [com.ledgerflow.core.domain.backup.RestoreSource.Staged]; nothing here opens a
 * backup.
 */
internal class DriveRestoreFlow(
    private val actions: DriveRestoreActions,
    private val scope: CoroutineScope,
    private val state: MutableStateFlow<RestoreUiState>,
) {

    fun onEvent(event: RestoreEvent.Drive) {
        when (event) {
            RestoreEvent.Drive.Requested -> start()
            RestoreEvent.Drive.AccountPickerLaunched -> update { it.copy(choosingAccount = false) }
            is RestoreEvent.Drive.AccountChosen -> chosen(event.account)
            RestoreEvent.Drive.ConsentLaunched -> update { it.copy(consent = null) }
            is RestoreEvent.Drive.ConsentReturned -> look { settle(actions.signIn.complete(event.data)) }
            is RestoreEvent.Drive.BackupPicked -> pick(event.fileName)
        }
    }

    /** Leaving the screen, or choosing a folder or a file instead: nothing downloaded stays. */
    fun forget() {
        if (state.value.drive == null) return
        state.update { it.copy(drive = null) }
        scope.launch { actions.clear() }
    }

    private fun start() {
        state.update {
            it.copy(
                drive = DriveRestoreState(choosingAccount = true, account = it.drive?.account),
                treeUri = null,
                backups = emptyList(),
                selectedBackup = null,
                folderUnreadable = false,
                singleFileUri = null,
                result = null,
            )
        }
    }

    private fun chosen(account: String?) {
        if (account == null) {
            // Dismissed before anything was listed: back to where the screen was.
            if (state.value.drive?.listing == null) forget()
            return
        }
        update { it.copy(account = account, problem = null) }
        look { settle(actions.signIn.begin(account)) }
    }

    private suspend fun settle(step: DriveSignInStep) {
        when (step) {
            DriveSignInStep.Granted -> list()
            is DriveSignInStep.NeedsConsent -> update { it.copy(consent = step.intent) }
            DriveSignInStep.Declined ->
                update { it.copy(problem = "Google Drive wasn't allowed, so nothing was listed.") }
            is DriveSignInStep.Unavailable -> update { it.copy(problem = unavailable(step.reason)) }
        }
    }

    private suspend fun list() {
        val account = state.value.drive?.account ?: return
        when (val listing = actions.list(account)) {
            is DriveBackupListing.Found -> update { it.copy(listing = listing, problem = null) }
            DriveBackupListing.NothingThere -> update {
                it.copy(problem = "No LedgerFlow backups in this account's Google Drive.")
            }
            DriveBackupListing.NeedsSignIn -> update { it.copy(problem = "Sign in again: tap From Google Drive.") }
            is DriveBackupListing.Unavailable -> update { it.copy(problem = unavailable(listing.reason)) }
            is DriveBackupListing.Failed -> update {
                it.copy(problem = "Couldn't reach Google Drive. Check the connection and tap From Google Drive.")
            }
        }
    }

    private fun pick(fileName: String) {
        val drive = state.value.drive ?: return
        val account = drive.account ?: return
        val listing = drive.listing ?: return
        val backup = listing.backups.firstOrNull { it.name == fileName } ?: return
        if (drive.downloading != null || drive.staged == fileName) return
        val total = 1 + listing.receipts.size
        update { it.copy(downloading = fileName, progress = 0 to total, staged = null, problem = null) }
        state.update { it.copy(result = null) }
        scope.launch {
            val staged = actions.stage(account, backup, listing.receipts) { done, total ->
                update { it.copy(progress = done to total) }
            }
            update {
                when (staged) {
                    is DriveStaging.Staged -> it.copy(downloading = null, progress = null, staged = staged.fileName)
                    DriveStaging.NeedsSignIn ->
                        it.copy(downloading = null, progress = null, problem = "Sign in again: tap From Google Drive.")
                    is DriveStaging.Failed -> it.copy(
                        downloading = null,
                        progress = null,
                        problem = "The download didn't finish. Tap the backup to try again. Nothing was restored.",
                    )
                }
            }
        }
    }

    /** Signing in or listing, with the screen told it is busy. */
    private fun look(block: suspend () -> Unit) {
        update { it.copy(looking = true) }
        scope.launch {
            try {
                block()
            } finally {
                update { it.copy(looking = false) }
            }
        }
    }

    private fun update(change: (DriveRestoreState) -> DriveRestoreState) {
        state.update { current -> current.drive?.let { current.copy(drive = change(it)) } ?: current }
    }

    private fun unavailable(reason: DriveUnavailable): String = when (reason) {
        DriveUnavailable.NoPlayServices -> "Google Drive needs Google Play services, which this phone doesn't have."
        DriveUnavailable.NotConfigured -> "Google sign-in isn't set up for this build."
        DriveUnavailable.Failed -> "Couldn't reach Google. Check the connection and tap From Google Drive."
    }
}
