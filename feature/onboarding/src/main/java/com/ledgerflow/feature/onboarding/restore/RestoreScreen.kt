package com.ledgerflow.feature.onboarding.restore

import android.content.Intent
import android.view.View
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.tooling.preview.PreviewFontScale
import androidx.compose.ui.tooling.preview.PreviewLightDark
import androidx.compose.ui.tooling.preview.PreviewScreenSizes
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ledgerflow.core.designsystem.component.LfActionAlignment
import com.ledgerflow.core.designsystem.component.LfActionRow
import com.ledgerflow.core.designsystem.component.LfButton
import com.ledgerflow.core.designsystem.component.LfButtonStyle
import com.ledgerflow.core.designsystem.component.LfCard
import com.ledgerflow.core.designsystem.component.LfScaffold
import com.ledgerflow.core.designsystem.component.LfScreenTitle
import com.ledgerflow.core.designsystem.theme.LfTheme
import com.ledgerflow.core.domain.backup.RestoreOutcome
import com.ledgerflow.core.domain.vault.PhraseEntry
import com.ledgerflow.core.ui.phrase.LfPhraseEntry
import com.ledgerflow.core.ui.phrase.LfPhraseScanner
import java.text.DateFormat
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

/**
 * The restore flow, wired to its ViewModel — the app shell's entry point.
 *
 * @param resuming a restore on this phone was interrupted; the screen is the
 *   only way on, so there is no back.
 * @param onBack returns to onboarding's first screen. Null when [resuming].
 */
@Composable
public fun RestoreRoute(resuming: Boolean, onBack: (() -> Unit)?) {
    val viewModel: RestoreViewModel = hiltViewModel()
    val state by viewModel.state.collectAsStateWithLifecycle()
    RestoreScreen(state = state, resuming = resuming, onEvent = viewModel::onEvent, onBack = onBack)
}

/**
 * Restore from a backup (SPEC.md §7.3 step 3, §16 Q11).
 *
 * The one action is pinned, as on the Recovery and Back up now screens: at font
 * scale 2.0 the word chips push a scrolling button below the fold (BUG5).
 */
@Composable
public fun RestoreScreen(
    state: RestoreUiState,
    resuming: Boolean,
    onEvent: (RestoreEvent) -> Unit,
    onBack: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    if (state.isScanning) {
        LfPhraseScanner(
            onScanned = { onEvent(RestoreEvent.Scanner.Read(it)) },
            onDismiss = { onEvent(RestoreEvent.Scanner.Dismissed) },
            modifier = modifier,
        )
        return
    }

    val closeKeyboard = rememberCloseKeyboard()
    val leave = onBack?.let { leavingVia(it, onEvent) }
    if (leave != null) RestoreBackHandler(state, leave, closeKeyboard)

    LfScaffold(
        modifier = modifier,
        bottomBar = { SubmitBar(state, onEvent, onSubmit = closeKeyboard) },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(LfTheme.spacing.sm),
        ) {
            // The action takes its own band above the heading (BUG17).
            if (leave != null && !state.isDone) {
                LfActionRow(
                    modifier = Modifier.padding(horizontal = LfTheme.spacing.md),
                    alignment = LfActionAlignment.Start,
                ) {
                    LfButton(
                        text = "Back",
                        onClick = leave,
                        enabled = !state.isWorking,
                        style = LfButtonStyle.Inline,
                    )
                }
            }
            LfScreenTitle(title = if (resuming) "Finish restoring" else "Restore a backup")
            RestoreBody(state, resuming, onEvent)
        }
    }
}

/**
 * Everything below the title: the report once it exists, and until then the
 * explanation, the backup choice and the words.
 */
@Composable
private fun RestoreBody(state: RestoreUiState, resuming: Boolean, onEvent: (RestoreEvent) -> Unit) {
    Column(
        modifier = Modifier.padding(horizontal = LfTheme.spacing.lg),
        verticalArrangement = Arrangement.spacedBy(LfTheme.spacing.md),
    ) {
        val result = state.result
        if (result is RestoreOutcome.Done) {
            ResultMessage(result)
            return@Column
        }
        Explanation(resuming)
        BackupChoice(state, onEvent)
        result?.let { ResultMessage(it) }
        LfPhraseEntry(
            words = state.entry.words,
            draft = state.entry.draft,
            suggestions = state.entry.suggestions,
            draftIsUnknown = state.entry.draftIsUnknown,
            requiredWordCount = state.entry.requiredWordCount,
            onDraftChange = { onEvent(RestoreEvent.DraftChanged(it)) },
            onSuggestionTap = { onEvent(RestoreEvent.WordCommitted(it)) },
            onWordRemove = { onEvent(RestoreEvent.WordRemoved(it)) },
            onScanRequested = { onEvent(RestoreEvent.Scanner.Requested) },
        )
        state.scanMessage?.let {
            Text(
                text = "$it Type the words instead.",
                style = LfTheme.typography.bodyM,
                color = LfTheme.colors.textSecondary,
            )
        }
    }
}

/**
 * What happens to the words, said before they are typed — and, the fact the
 * owner's decision turns on, that no new phrase is issued.
 */
@Composable
private fun Explanation(resuming: Boolean) {
    LfCard {
        Text(
            text = if (resuming) {
                "A restore on this phone didn't finish. Choose the backup again and type the " +
                    "same 24 words. Nothing is opened until it's done."
            } else {
                "Your ledger comes back protected by the backup's own 24 words — you won't be " +
                    "given new ones. They're used once and forgotten, never saved or sent."
            },
            style = LfTheme.typography.bodyM,
            color = LfTheme.colors.textSecondary,
        )
    }
}

/**
 * The folder first: it is what "Back up now" writes into, and only a folder
 * grant reaches the receipt images beside the `.lfbk` (ADR-0023). A single
 * file is the fallback for one that travelled alone.
 */
@Composable
private fun BackupChoice(state: RestoreUiState, onEvent: (RestoreEvent) -> Unit) {
    val context = LocalContext.current
    val folderPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            // Persisted now, at the only moment its flags are valid to take:
            // the folder becomes this install's backup folder.
            runCatching {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
                )
            }
        }
        onEvent(RestoreEvent.FolderChosen(uri?.toString()))
    }
    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        onEvent(RestoreEvent.SingleFileChosen(uri?.toString()))
    }

    LfCard {
        Column(verticalArrangement = Arrangement.spacedBy(LfTheme.spacing.xs)) {
            Text(
                text = "Backup",
                style = LfTheme.typography.label,
                color = LfTheme.colors.textPrimary,
            )
            ChosenBackup(state, onEvent)
            LfActionRow(alignment = LfActionAlignment.Start) {
                LfButton(
                    text = if (state.treeUri == null) "Choose backup folder" else "Choose another folder",
                    onClick = { folderPicker.launch(null) },
                    enabled = !state.isWorking,
                    style = LfButtonStyle.Inline,
                )
                LfButton(
                    text = "Use a single file",
                    // A `.lfbk` has no registered type, so the picker cannot filter for it.
                    onClick = { filePicker.launch(arrayOf("*/*")) },
                    enabled = !state.isWorking,
                    style = LfButtonStyle.Inline,
                )
            }
        }
    }
}

@Composable
private fun ChosenBackup(state: RestoreUiState, onEvent: (RestoreEvent) -> Unit) {
    val note: String? = when {
        state.singleFileUri != null ->
            "One file chosen. Receipt images can't come with a single file."
        state.treeUri == null ->
            "Choose the folder your backups were saved to."
        state.folderUnreadable ->
            "That folder couldn't be read. Choose it again."
        state.backups.isEmpty() ->
            "No LedgerFlow backups in that folder."
        else -> null
    }
    if (note != null) {
        Text(text = note, style = LfTheme.typography.bodyM, color = LfTheme.colors.textSecondary)
        return
    }
    state.backups.forEachIndexed { index, name ->
        val selected = name == state.selectedBackup
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .selectable(
                    selected = selected,
                    enabled = !state.isWorking,
                    onClick = { onEvent(RestoreEvent.BackupSelected(name)) },
                ),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(LfTheme.spacing.sm),
        ) {
            // The row owns the selection semantics, as on the currency list.
            RadioButton(selected = selected, onClick = null, modifier = Modifier.clearAndSetSemantics {})
            Text(
                text = backupLabel(name) + if (index == 0) " · newest" else "",
                style = LfTheme.typography.bodyM,
                color = LfTheme.colors.textPrimary,
            )
        }
    }
}

@Composable
private fun ResultMessage(outcome: RestoreOutcome) {
    Text(
        text = outcome.message(),
        style = LfTheme.typography.bodyM,
        color = if (outcome.isSuccess) LfTheme.colors.credit else LfTheme.colors.debit,
    )
}

@Composable
private fun SubmitBar(state: RestoreUiState, onEvent: (RestoreEvent) -> Unit, onSubmit: () -> Unit) {
    Column(modifier = Modifier.fillMaxWidth().padding(LfTheme.spacing.sm)) {
        if (state.isDone) {
            LfButton(
                text = "Open my ledger",
                onClick = { onEvent(RestoreEvent.Continued) },
                modifier = Modifier.fillMaxWidth(),
            )
        } else {
            LfButton(
                text = when {
                    state.source == null -> "Choose a backup"
                    state.entry.isComplete -> "Restore"
                    else -> "${state.entry.remaining} words to go"
                },
                onClick = {
                    // The answer appears above the words; an open keyboard
                    // hides it, and is BUG29's precondition.
                    onSubmit()
                    onEvent(RestoreEvent.Submitted)
                },
                enabled = state.canSubmit,
                loading = state.isWorking,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

/**
 * "Back up now"'s names carry a UTC timestamp; shown in local time, which is
 * how the user remembers taking it. Any other name is shown as it is.
 */
internal fun backupLabel(fileName: String, zone: TimeZone = TimeZone.getDefault()): String {
    val stamp = STAMPED_NAME.matchEntire(fileName)?.groupValues?.get(1) ?: return fileName
    val parsed = runCatching {
        SimpleDateFormat("yyyyMMdd-HHmmss", Locale.ROOT)
            .apply { timeZone = TimeZone.getTimeZone("UTC") }
            .parse(stamp)
    }.getOrNull() ?: return fileName
    return DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT)
        .apply { timeZone = zone }
        .format(parsed)
}

private val STAMPED_NAME = Regex("""ledgerflow-(\d{8}-\d{6})\.lfbk""")

/**
 * The ViewModel belongs to the activity and outlives this screen, so leaving is
 * when the words are forgotten (BUG30) — by either way out.
 */
private fun leavingVia(back: () -> Unit, onEvent: (RestoreEvent) -> Unit): () -> Unit = {
    onEvent(RestoreEvent.Left)
    back()
}

@Composable
private fun rememberCloseKeyboard(): () -> Unit {
    val keyboard = LocalSoftwareKeyboardController.current
    val focus = LocalFocusManager.current
    return {
        focus.clearFocus()
        keyboard?.hide()
    }
}

/**
 * Leaving after the restore would strand a restored vault behind onboarding,
 * so back is off once it is done. Before that the handler stays registered
 * throughout (BUG29): switching it off while a restore ran and on again after
 * it re-registered it above the keyboard's own callback, so back skipped the
 * keyboard and left the screen with the words on it.
 */
@Composable
private fun RestoreBackHandler(state: RestoreUiState, leave: () -> Unit, closeKeyboard: () -> Unit) {
    val view = LocalView.current
    BackHandler(enabled = !state.isDone) {
        when (restoreBack(keyboardOpen = view.keyboardOpen(), isWorking = state.isWorking)) {
            RestoreBack.CloseKeyboard -> closeKeyboard()
            RestoreBack.Stay -> Unit
            RestoreBack.Leave -> leave()
        }
    }
}

/** Read from the window at the moment back arrives, not from composition state. */
private fun View.keyboardOpen(): Boolean =
    ViewCompat.getRootWindowInsets(this)?.isVisible(WindowInsetsCompat.Type.ime()) == true

/** What back does on the restore screen (BUG29). */
internal enum class RestoreBack { CloseKeyboard, Stay, Leave }

/**
 * An open keyboard is closed first, whatever else is true: back was leaving the
 * screen — and the 24 words on it — when it should only have closed the
 * keyboard. While a restore runs, back waits for the answer.
 */
internal fun restoreBack(keyboardOpen: Boolean, isWorking: Boolean): RestoreBack = when {
    keyboardOpen -> RestoreBack.CloseKeyboard
    isWorking -> RestoreBack.Stay
    else -> RestoreBack.Leave
}

// ── Previews (CLAUDE.md §5) ───────────────────────────────────────────────

@PreviewScreenSizes
@PreviewFontScale
@PreviewLightDark
@Composable
private fun RestoreChoosingPreview() {
    LfTheme {
        RestoreScreen(
            state = RestoreUiState(
                treeUri = "content://tree",
                backups = listOf("ledgerflow-20260918-101500.lfbk", "ledgerflow-20260911-093000.lfbk"),
                selectedBackup = "ledgerflow-20260918-101500.lfbk",
                entry = PhraseEntry(requiredWordCount = 24, draft = "aban", suggestions = listOf("abandon")),
            ),
            resuming = false,
            onEvent = {},
            onBack = {},
        )
    }
}

@PreviewScreenSizes
@PreviewFontScale
@PreviewLightDark
@Composable
private fun RestoreDonePreview() {
    LfTheme {
        RestoreScreen(
            state = RestoreUiState(
                entry = PhraseEntry(requiredWordCount = 24),
                result = RestoreOutcome.Done(
                    rows = 412,
                    imagesRestored = 9,
                    imagesNotFound = 3,
                    imagesUnreadable = 0,
                    imagesFailed = 0,
                ),
            ),
            resuming = false,
            onEvent = {},
            onBack = {},
        )
    }
}
