package com.ledgerflow.feature.onboarding.restore

import android.content.Intent
import android.view.View
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.tooling.preview.PreviewFontScale
import androidx.compose.ui.tooling.preview.PreviewLightDark
import androidx.compose.ui.tooling.preview.PreviewScreenSizes
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ledgerflow.core.designsystem.component.LfActionAlignment
import com.ledgerflow.core.designsystem.component.LfActionRow
import com.ledgerflow.core.designsystem.component.LfAdaptiveRow
import com.ledgerflow.core.designsystem.component.LfButton
import com.ledgerflow.core.designsystem.component.LfButtonStyle
import com.ledgerflow.core.designsystem.component.LfCard
import com.ledgerflow.core.designsystem.component.LfNotice
import com.ledgerflow.core.designsystem.component.LfNoticeTone
import com.ledgerflow.core.designsystem.component.LfScaffold
import com.ledgerflow.core.designsystem.component.LfScreenTitle
import com.ledgerflow.core.designsystem.component.LfTextField
import com.ledgerflow.core.designsystem.format.DisplayDates
import com.ledgerflow.core.designsystem.pattern.LfActionBar
import com.ledgerflow.core.designsystem.pattern.LfFormPage
import com.ledgerflow.core.designsystem.theme.LfLayout
import com.ledgerflow.core.designsystem.theme.LfTheme
import com.ledgerflow.core.domain.backup.LedgerCounts
import com.ledgerflow.core.domain.backup.RestoreOutcome
import com.ledgerflow.core.domain.backup.RestorePreview
import com.ledgerflow.core.domain.vault.PhraseEntry
import com.ledgerflow.core.ui.phrase.LfPhraseEntry
import com.ledgerflow.core.ui.phrase.LfPhraseScanner
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

/**
 * The restore flow, wired to its ViewModel — the app shell's entry point.
 *
 * @param mode which restore: at first run, finishing an interrupted one (the
 *   screen is then the only way on, so there is no back), or from Settings.
 * @param onBack returns to where the restore was opened from. Null when
 *   finishing an interrupted one.
 */
@Composable
public fun RestoreRoute(mode: RestoreMode, onBack: (() -> Unit)?) {
    val viewModel: RestoreViewModel = hiltViewModel()
    val state by viewModel.state.collectAsStateWithLifecycle()
    LaunchedEffect(mode) { viewModel.setMode(mode) }
    RestoreScreen(state = state, onEvent = viewModel::onEvent, onBack = onBack)
}

/**
 * Restore from a backup (SPEC.md §7.3 step 3, §16 Q11; from Settings, ADR-0033).
 *
 * The one action is pinned, as on the Recovery and Back up now screens: at font
 * scale 2.0 the word chips push a scrolling button below the fold (BUG5).
 */
@Composable
public fun RestoreScreen(
    state: RestoreUiState,
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
    val preview = state.preview
    if (state.isConfirming && preview != null) {
        ReplaceConfirmation(state, preview, onEvent, modifier)
        return
    }

    val closeKeyboard = rememberCloseKeyboard()
    val leave = onBack?.let { leavingVia(it, onEvent) }
    if (leave != null) RestoreBackHandler(state, leave, closeKeyboard)
    val resuming = state.mode == RestoreMode.Resuming

    LfFormPage(
        title = if (resuming) "Finish restoring" else "Restore a backup",
        actionBar = { SubmitBar(state, onEvent, onSubmit = closeKeyboard) },
        modifier = modifier,
        // The back arrow beside the heading (step F5, owner 2026-10-29), as on
        // the Settings pages; off while a restore runs, and absent once it is
        // done or when there is nowhere to go back to.
        onBack = if (leave != null && !state.isDone) leave else null,
        backEnabled = !state.isWorking,
    ) {
        RestoreBody(state, onEvent)
    }
}

/**
 * Everything below the title: the report once it exists, and until then the
 * explanation, the backup choice and the words.
 */
@Composable
private fun RestoreBody(state: RestoreUiState, onEvent: (RestoreEvent) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(LfTheme.space.sectionGap)) {
        val result = state.result
        if (result is RestoreOutcome.Done) {
            ResultMessage(result)
            return@Column
        }
        Explanation(state.mode)
        if (state.mode == RestoreMode.Resuming && (state.canPutBack || state.putBackFailed)) {
            PutBackChoice(state, onEvent)
        }
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
                style = LfTheme.type.bodySmall,
                color = LfTheme.colors.text.secondary,
            )
        }
    }
}

/**
 * What happens to the words, said before they are typed — and, the fact the
 * owner's decision turns on, that no new phrase is issued.
 */
@Composable
private fun Explanation(mode: RestoreMode) {
    LfCard {
        Text(
            text = when (mode) {
                RestoreMode.Resuming ->
                    "A restore on this phone didn't finish. Choose the backup again and type the " +
                        "same 24 words. Nothing is opened until it's done."
                RestoreMode.FirstRun ->
                    "Your ledger comes back protected by the backup's own 24 words — you won't be " +
                        "given new ones. They're used once and forgotten, never saved or sent."
                RestoreMode.Replace ->
                    "The backup replaces this phone's ledger. First its words open it, and you see " +
                        "what's in it; nothing changes until you confirm. The words are used once " +
                        "and forgotten, never saved or sent."
            },
            style = LfTheme.type.bodySmall,
            color = LfTheme.colors.text.secondary,
        )
    }
}

/**
 * Finishing an interrupted restore from Settings: the other way on is back to
 * the ledger the phone had (ADR-0033 e). Second to finishing, not instead of
 * it, and plainly worded about what it removes.
 */
@Composable
private fun PutBackChoice(state: RestoreUiState, onEvent: (RestoreEvent) -> Unit) {
    LfCard {
        Column(verticalArrangement = Arrangement.spacedBy(LfTheme.space.inlineGap)) {
            Text(
                text = if (state.putBackFailed) {
                    "The ledger this phone had couldn't be put back. Nothing was removed. " +
                        "Try again, or finish the restore."
                } else {
                    "Or go back to the ledger this phone had before the restore started. " +
                        "What the restore had written so far is removed; the backup is untouched."
                },
                style = LfTheme.type.bodySmall,
                color = if (state.putBackFailed) LfTheme.colors.status.danger else LfTheme.colors.text.secondary,
            )
            if (state.canPutBack) {
                LfActionRow(alignment = LfActionAlignment.Start) {
                    LfButton(
                        text = "Put back my ledger",
                        onClick = { onEvent(RestoreEvent.PutBack) },
                        enabled = !state.isWorking,
                        style = LfButtonStyle.Inline,
                    )
                }
            }
        }
    }
}

/**
 * From Settings, after the backup has opened (ADR-0033 c, mockup A): what is in
 * the backup and what is on the phone, each book on its own line (Law 2), what
 * is lost, and DELETE typed before Replace does anything (§7.3).
 */
@Composable
private fun ReplaceConfirmation(
    state: RestoreUiState,
    preview: RestorePreview.Ready,
    onEvent: (RestoreEvent) -> Unit,
    modifier: Modifier = Modifier,
) {
    val leave = { onEvent(RestoreEvent.ConfirmationLeft) }
    BackHandler(enabled = !state.isWorking, onBack = leave)
    LfFormPage(
        title = "Replace this phone's ledger?",
        actionBar = {
            LfActionBar {
                LfButton(
                    text = "Replace",
                    onClick = { onEvent(RestoreEvent.Replaced) },
                    enabled = state.canReplace,
                    loading = state.isWorking,
                    // The Warning emphasis's confirm, as in LfDialog: an
                    // irreversible step is never the loudest button on screen.
                    style = LfButtonStyle.Tonal,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        modifier = modifier,
        onBack = leave,
        backEnabled = !state.isWorking,
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(LfTheme.space.sectionGap)) {
            CountsCard(title = backupTitle(state), counts = preview.backup)
            CountsCard(title = "On this phone now", counts = preview.phone)
            LfNotice(
                tone = LfNoticeTone.Warning,
                title = "Anything on this phone that isn't in the backup is lost.",
                body = replaceConsequence(preview.sameWords),
            )
            LfTextField(
                value = state.confirmation,
                onValueChange = { onEvent(RestoreEvent.ConfirmationChanged(it)) },
                label = "Type ${RestoreUiState.CONFIRMATION_WORD} to replace",
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters),
            )
        }
    }
}

/** One side of the comparison: a heading and three counts, words left and figures right. */
@Composable
private fun CountsCard(title: String, counts: LedgerCounts) {
    LfCard {
        Column(verticalArrangement = Arrangement.spacedBy(LfTheme.space.inlineGap)) {
            Text(text = title, style = LfTheme.type.label, color = LfTheme.colors.text.primary)
            CountLine("Expenses", counts.expenses)
            CountLine("Income", counts.income)
            CountLine("In the Inbox", counts.inbox)
        }
    }
}

@Composable
private fun CountLine(label: String, count: Int) {
    LfAdaptiveRow(
        leading = { Text(text = label, style = LfTheme.type.bodySmall, color = LfTheme.colors.text.secondary) },
        trailing = {
            Text(text = count.toString(), style = LfTheme.type.figureLabel, color = LfTheme.colors.text.primary)
        },
        modifier = Modifier.fillMaxWidth(),
    )
}

/** "The backup, 3 Nov 2026, 3:00 AM" when its name carries the time; otherwise just the backup. */
private fun backupTitle(state: RestoreUiState): String {
    val name = state.drive?.staged ?: state.selectedBackup ?: return "The backup"
    val label = backupLabel(name)
    return if (label == name) "The backup" else "The backup, $label"
}

/**
 * What else changes. The words sentence appears only when they do change:
 * the phone's Recovery Kit would quietly stop working otherwise (ADR-0033 d).
 */
internal fun replaceConsequence(sameWords: Boolean): String = buildString {
    append("Entries, Inbox items and receipts added since the backup was made are gone once it's restored.")
    if (!sameWords) {
        append(
            " This backup was made with different words. Afterwards only its words open your " +
                "ledger, and this phone's Recovery Kit no longer does.",
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

    DriveLaunchers(state.drive, onEvent)

    LfCard {
        Column(verticalArrangement = Arrangement.spacedBy(LfTheme.space.inlineGap)) {
            Text(
                text = "Backup",
                style = LfTheme.type.label,
                color = LfTheme.colors.text.primary,
            )
            val drive = state.drive
            if (drive != null) DriveBackups(drive, state.isWorking, onEvent) else ChosenBackup(state, onEvent)
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
                // ADR-0034 §7: the copies Drive backup made.
                LfButton(
                    text = "From Google Drive",
                    onClick = { onEvent(RestoreEvent.Drive.Requested) },
                    enabled = !state.isWorking && state.drive?.downloading == null,
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
        Text(text = note, style = LfTheme.type.bodySmall, color = LfTheme.colors.text.secondary)
        return
    }
    state.backups.forEachIndexed { index, name ->
        val selected = name == state.selectedBackup
        Row(
            modifier = Modifier
                .fillMaxWidth()
                // A full-size target (BUG43): the radio has no onClick, so it
                // brings no 48 dp minimum; these rows were 24 dp, 4 dp apart.
                .defaultMinSize(minHeight = LfLayout.minTouchTarget)
                .selectable(
                    selected = selected,
                    enabled = !state.isWorking,
                    onClick = { onEvent(RestoreEvent.BackupSelected(name)) },
                ),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(LfTheme.space.itemGap),
        ) {
            // The row owns the selection semantics, as on the currency list.
            RadioButton(selected = selected, onClick = null, modifier = Modifier.clearAndSetSemantics {})
            Text(
                text = backupLabel(name) + if (index == 0) " · newest" else "",
                style = LfTheme.type.bodySmall,
                color = LfTheme.colors.text.primary,
            )
        }
    }
}

/**
 * The restore's answer. Success is a success notice; a failure is the form's
 * error line, in the problem colour, with the pinned Restore as its retry. It
 * was money-in green and money-out coral, which say something else (E2).
 */
@Composable
private fun ResultMessage(outcome: RestoreOutcome) {
    if (outcome.isSuccess) {
        LfNotice(tone = LfNoticeTone.Success, title = outcome.message())
    } else {
        Text(
            text = outcome.message(),
            style = LfTheme.type.bodySmall,
            color = LfTheme.colors.status.danger,
        )
    }
}

@Composable
private fun SubmitBar(state: RestoreUiState, onEvent: (RestoreEvent) -> Unit, onSubmit: () -> Unit) {
    LfActionBar {
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
                    // From Settings the words only open the backup; Replace
                    // is on the next page.
                    state.entry.isComplete && state.mode == RestoreMode.Replace -> "Continue"
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
    return DisplayDates.mediumDateTime(parsed.time, zone.toZoneId())
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
            onEvent = {},
            onBack = {},
        )
    }
}

@PreviewScreenSizes
@PreviewFontScale
@PreviewLightDark
@Composable
private fun RestoreReplaceConfirmPreview() {
    LfTheme {
        RestoreScreen(
            state = RestoreUiState(
                mode = RestoreMode.Replace,
                treeUri = "content://tree",
                backups = listOf("ledgerflow-20261103-033000.lfbk"),
                selectedBackup = "ledgerflow-20261103-033000.lfbk",
                entry = PhraseEntry(requiredWordCount = 24),
                preview = RestorePreview.Ready(
                    backup = LedgerCounts(expenses = 412, income = 38, inbox = 5),
                    phone = LedgerCounts(expenses = 430, income = 41, inbox = 9),
                    sameWords = false,
                ),
                confirmation = "DEL",
            ),
            onEvent = {},
            onBack = {},
        )
    }
}
