package com.ledgerflow.feature.settings.drive

import android.accounts.Account
import android.accounts.AccountManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.PreviewFontScale
import androidx.compose.ui.tooling.preview.PreviewLightDark
import androidx.compose.ui.tooling.preview.PreviewScreenSizes
import com.ledgerflow.core.designsystem.component.LfActionAlignment
import com.ledgerflow.core.designsystem.component.LfActionRow
import com.ledgerflow.core.designsystem.component.LfAdaptiveRow
import com.ledgerflow.core.designsystem.component.LfButton
import com.ledgerflow.core.designsystem.component.LfButtonStyle
import com.ledgerflow.core.designsystem.component.LfDialog
import com.ledgerflow.core.designsystem.component.LfDivider
import com.ledgerflow.core.designsystem.component.LfNotice
import com.ledgerflow.core.designsystem.component.LfNoticeTone
import com.ledgerflow.core.designsystem.pattern.LfPanel
import com.ledgerflow.core.designsystem.pattern.LfSettingsPage
import com.ledgerflow.core.designsystem.theme.LfTheme
import com.ledgerflow.core.domain.drive.DriveAttempt
import com.ledgerflow.core.domain.drive.DriveLayout
import com.ledgerflow.core.domain.drive.DriveOutcome
import com.ledgerflow.core.domain.drive.DriveStatus
import com.ledgerflow.feature.settings.PanelRow

/**
 * Settings > Data > Google Drive (ADR-0034; owner's layout A, 2026-11-07): the
 * status and its one action in the first panel, what Drive backup does in the
 * second. Stateless; [GoogleDriveViewModel] decides.
 */
@Composable
public fun GoogleDriveScreen(
    state: GoogleDriveUiState,
    onEvent: (GoogleDriveEvent) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val snackbar = remember { SnackbarHostState() }
    AccountPicker(state, onEvent)
    ConsentLauncher(state, onEvent)
    state.message?.let { message ->
        LaunchedEffect(message) {
            snackbar.showSnackbar(message)
            onEvent(GoogleDriveEvent.MessageShown)
        }
    }
    if (state.confirmingOff) TurnOffDialog(state.receiptsOnlyInBackups, onEvent)

    LfSettingsPage(
        title = "Google Drive",
        subtitle = "Encrypted copies of your nightly backup, in your own Drive.",
        snackbarHostState = snackbar,
        modifier = modifier,
        onBack = onBack,
    ) {
        item(key = "status", contentType = "panel") { StatusPanel(state, onEvent) }
        // ADR-0035 §6: only once receipts live nowhere else, so deleting the
        // Drive folder is known to delete them.
        if (state.receiptsOnlyInBackups > 0) {
            item(key = "only-in-backups", contentType = "notice") {
                LfNotice(
                    tone = LfNoticeTone.Warning,
                    title = onlyInBackupsTitle(state.receiptsOnlyInBackups),
                    body = ONLY_IN_BACKUPS_BODY,
                )
            }
        }
        item(key = "details", contentType = "panel") {
            if (state.status.enabled) DetailsPanel(state, onEvent) else AboutPanel()
        }
    }
}

/** The status sentence and the one thing to do about it. */
@Composable
private fun StatusPanel(state: GoogleDriveUiState, onEvent: (GoogleDriveEvent) -> Unit) {
    LfPanel {
        Text(
            text = driveSentence(state.status),
            style = LfTheme.type.body,
            color = when (driveTone(state.status)) {
                DriveTone.Good -> LfTheme.colors.status.success
                DriveTone.NeedsYou -> LfTheme.colors.status.warning
                DriveTone.Neutral -> LfTheme.colors.text.primary
            },
            modifier = Modifier.padding(LfTheme.space.cardInset),
        )
        LfDivider()
        LfActionRow(
            alignment = LfActionAlignment.End,
            modifier = Modifier.fillMaxWidth().padding(horizontal = LfTheme.space.rowInset),
        ) {
            val (label, event) = when {
                !state.status.enabled -> "Turn on" to GoogleDriveEvent.TurnOn
                state.needsSignIn -> "Sign in again" to GoogleDriveEvent.TurnOn
                else -> "Copy now" to GoogleDriveEvent.CopyNow
            }
            LfButton(
                text = label,
                style = LfButtonStyle.Inline,
                enabled = !state.isWorking,
                onClick = { onEvent(event) },
            )
        }
    }
}

/** On: who, how, how many, and the way out. */
@Composable
private fun DetailsPanel(state: GoogleDriveUiState, onEvent: (GoogleDriveEvent) -> Unit) {
    LfPanel {
        Fact("Account", state.status.account ?: "Your Google account")
        LfDivider()
        Fact("Uses", "Any network, mobile data included")
        LfDivider()
        Fact("Keeps", "The newest ${DriveLayout.KEEP} backups and your receipts")
        LfDivider()
        PanelRow(onClick = if (state.isWorking) null else ({ onEvent(GoogleDriveEvent.TurnOffRequested) })) {
            Text(text = "Turn off", style = LfTheme.type.body, color = LfTheme.colors.text.primary)
        }
    }
}

/** Off: what turning on would mean, before anyone signs in. */
@Composable
private fun AboutPanel() {
    LfPanel {
        Column(
            modifier = Modifier.padding(LfTheme.space.cardInset),
            verticalArrangement = Arrangement.spacedBy(LfTheme.space.itemGap),
        ) {
            Note("Each nightly backup and your receipts are copied after they're written, already encrypted.")
            Note("Google can't open them: that needs your 24 words, which never leave you.")
            Note("LedgerFlow sees only the files it puts in your Drive, never the rest.")
        }
    }
}

/** A label and its value, side by side while they fit and stacked when they don't (font scale 2.0). */
@Composable
private fun Fact(label: String, value: String) {
    PanelRow {
        LfAdaptiveRow(
            leading = { Text(text = label, style = LfTheme.type.body, color = LfTheme.colors.text.primary) },
            trailing = {
                Text(text = value, style = LfTheme.type.bodySmall, color = LfTheme.colors.text.secondary)
            },
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun Note(text: String) {
    Text(text = text, style = LfTheme.type.bodySmall, color = LfTheme.colors.text.secondary)
}

/**
 * Not a warning: nothing is destroyed. It says what stops and what stays, so
 * nobody turns it off believing their copies go with it.
 */
@Composable
private fun TurnOffDialog(receiptsOnlyInBackups: Int, onEvent: (GoogleDriveEvent) -> Unit) {
    LfDialog(
        title = "Turn off Drive backup?",
        body = turnOffBody(receiptsOnlyInBackups),
        confirmText = "Turn off",
        onConfirm = { onEvent(GoogleDriveEvent.TurnOffConfirmed) },
        onDismiss = { onEvent(GoogleDriveEvent.TurnOffDismissed) },
    )
}

/** "3 receipts are kept only in your backups". */
internal fun onlyInBackupsTitle(count: Int): String =
    if (count == 1) "1 receipt is kept only in your backups" else "$count receipts are kept only in your backups"

internal const val ONLY_IN_BACKUPS_BODY: String =
    "They were removed from this phone. Deleting \"LedgerFlow backups\" on Drive's website deletes them."

/**
 * What stops and what stays; and, once receipts were removed from the phone,
 * that removal by age stops too and those receipts are only in backups.
 */
internal fun turnOffBody(receiptsOnlyInBackups: Int): String {
    val base = "New backups stay on this phone only. The copies already in your Drive stay there; " +
        "delete them in Drive if you want them gone."
    if (receiptsOnlyInBackups == 0) return base
    return "$base Removing receipts by age stops. ${onlyInBackupsTitle(receiptsOnlyInBackups)}: " +
        "deleting \"LedgerFlow backups\" on Drive's website deletes them."
}

/**
 * Android's own account picker, Google accounts only, opened once per request.
 * It needs no permission and grants nothing: it only says which account the
 * consent sheet is for, so the app knows which grant to use and to revoke.
 */
@Composable
private fun AccountPicker(state: GoogleDriveUiState, onEvent: (GoogleDriveEvent) -> Unit) {
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        onEvent(GoogleDriveEvent.AccountChosen(result.data?.getStringExtra(AccountManager.KEY_ACCOUNT_NAME)))
    }
    if (!state.choosingAccount) return
    val current = state.status.account
    LaunchedEffect(Unit) {
        launcher.launch(
            AccountManager.newChooseAccountIntent(
                current?.let { Account(it, GOOGLE_ACCOUNT_TYPE) },
                null,
                arrayOf(GOOGLE_ACCOUNT_TYPE),
                null,
                null,
                null,
                null,
            ),
        )
        onEvent(GoogleDriveEvent.AccountPickerLaunched)
    }
}

private const val GOOGLE_ACCOUNT_TYPE = "com.google"

/** Launches Google's consent sheet once per request, and hands back its answer. */
@Composable
private fun ConsentLauncher(state: GoogleDriveUiState, onEvent: (GoogleDriveEvent) -> Unit) {
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
        onEvent(GoogleDriveEvent.ConsentReturned(result.data))
    }
    val consent = state.consent ?: return
    LaunchedEffect(consent) {
        launcher.launch(IntentSenderRequest.Builder(consent.intentSender).build())
        onEvent(GoogleDriveEvent.ConsentLaunched)
    }
}

@PreviewScreenSizes
@PreviewFontScale
@PreviewLightDark
@Composable
private fun GoogleDriveScreenPreview() {
    LfTheme {
        GoogleDriveScreen(
            state = GoogleDriveUiState(
                status = DriveStatus(
                    enabled = true,
                    lastAttempt = DriveAttempt(PREVIEW_AT, DriveOutcome.Copied(1, 2, 3_100_000, 0)),
                    lastCopiedAt = PREVIEW_AT,
                    account = "you@gmail.com",
                ),
            ),
            onEvent = {},
            onBack = {},
        )
    }
}

private const val PREVIEW_AT = 1_794_000_000_000L
