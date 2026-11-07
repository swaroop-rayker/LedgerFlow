package com.ledgerflow.feature.onboarding.restore

import android.accounts.Account
import android.accounts.AccountManager
import android.text.format.Formatter
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.clearAndSetSemantics
import com.ledgerflow.core.designsystem.theme.LfLayout
import com.ledgerflow.core.designsystem.theme.LfTheme

/**
 * The Backup panel's contents when restoring from Google Drive (ADR-0034 §7):
 * what is happening, then the backups as the same radio rows a folder's are,
 * then what picking one downloads. A tap downloads (owner, 2026-11-07); the
 * words and Restore below are the screen's own.
 */
@Composable
internal fun DriveBackups(drive: DriveRestoreState, isWorking: Boolean, onEvent: (RestoreEvent) -> Unit) {
    val listing = drive.listing
    val note = when {
        drive.problem != null -> drive.problem
        listing == null && drive.looking -> "Looking in Google Drive…"
        listing == null -> "Choose the Google account your backups were copied to."
        else -> null
    }
    if (note != null) Note(note)
    listing ?: return

    Note("In Google Drive" + (drive.account?.let { ", $it" } ?: ""))
    listing.backups.forEachIndexed { index, backup ->
        val selected = backup.name == (drive.staged ?: drive.downloading)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .defaultMinSize(minHeight = LfLayout.minTouchTarget)
                .selectable(
                    selected = selected,
                    enabled = !isWorking && drive.downloading == null,
                    onClick = { onEvent(RestoreEvent.Drive.BackupPicked(backup.name)) },
                ),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(LfTheme.space.itemGap),
        ) {
            RadioButton(selected = selected, onClick = null, modifier = Modifier.clearAndSetSemantics {})
            Text(
                text = backupLabel(backup.name) + if (index == 0) " · newest" else "",
                style = LfTheme.type.bodySmall,
                color = LfTheme.colors.text.primary,
            )
        }
    }
    Note(downloadLine(drive))
}

/** What picking downloads, how far it has got, or that it is ready. */
@Composable
private fun downloadLine(drive: DriveRestoreState): String {
    val listing = drive.listing ?: return ""
    val receipts = listing.receipts.size
    val size = Formatter.formatShortFileSize(LocalContext.current, listing.receiptBytes)
    val progress = drive.progress
    return when {
        drive.downloading != null && progress != null -> "Downloading… ${progress.first} of ${progress.second} files"
        drive.staged != null && receipts == 0 -> "Downloaded. Now type its 24 words."
        drive.staged != null -> "Downloaded, with ${plural(receipts, "receipt")}. Now type its 24 words."
        receipts == 0 -> "Tap a backup to download it."
        else -> "Tap a backup to download it with ${plural(receipts, "receipt")} ($size)."
    }
}

@Composable
private fun Note(text: String) {
    Text(text = text, style = LfTheme.type.bodySmall, color = LfTheme.colors.text.secondary)
}

private fun plural(count: Int, noun: String): String = if (count == 1) "1 $noun" else "$count ${noun}s"

/**
 * Android's account picker, then Google's consent sheet, each launched once
 * per request (the same steps as Settings > Data > Google Drive). The picker
 * needs no permission; it only says which account the grant is for.
 */
@Composable
internal fun DriveLaunchers(drive: DriveRestoreState?, onEvent: (RestoreEvent) -> Unit) {
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        onEvent(RestoreEvent.Drive.AccountChosen(result.data?.getStringExtra(AccountManager.KEY_ACCOUNT_NAME)))
    }
    val consentSheet = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) {
        onEvent(RestoreEvent.Drive.ConsentReturned(it.data))
    }
    if (drive?.choosingAccount == true) {
        LaunchedEffect(Unit) {
            picker.launch(
                AccountManager.newChooseAccountIntent(
                    drive.account?.let { Account(it, GOOGLE_ACCOUNT_TYPE) },
                    null,
                    arrayOf(GOOGLE_ACCOUNT_TYPE),
                    null,
                    null,
                    null,
                    null,
                ),
            )
            onEvent(RestoreEvent.Drive.AccountPickerLaunched)
        }
    }
    val consent = drive?.consent ?: return
    LaunchedEffect(consent) {
        consentSheet.launch(IntentSenderRequest.Builder(consent.intentSender).build())
        onEvent(RestoreEvent.Drive.ConsentLaunched)
    }
}

private const val GOOGLE_ACCOUNT_TYPE = "com.google"
