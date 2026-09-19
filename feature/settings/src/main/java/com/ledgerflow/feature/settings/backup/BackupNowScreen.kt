package com.ledgerflow.feature.settings.backup

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.tooling.preview.PreviewFontScale
import androidx.compose.ui.tooling.preview.PreviewLightDark
import androidx.compose.ui.tooling.preview.PreviewScreenSizes
import com.ledgerflow.core.designsystem.component.LfActionAlignment
import com.ledgerflow.core.designsystem.component.LfActionRow
import com.ledgerflow.core.designsystem.component.LfButton
import com.ledgerflow.core.designsystem.component.LfButtonStyle
import com.ledgerflow.core.designsystem.component.LfCard
import com.ledgerflow.core.designsystem.component.LfScaffold
import com.ledgerflow.core.designsystem.component.LfScreenTitle
import com.ledgerflow.core.designsystem.theme.LfTheme
import com.ledgerflow.core.domain.backup.BackupOutcome
import com.ledgerflow.core.domain.vault.PhraseEntry
import com.ledgerflow.core.ui.phrase.LfPhraseEntry

/**
 * "Back up now" (SPEC.md §16 Q23).
 *
 * The one action is pinned, as on the Recovery screen: at font scale 2.0 the
 * word chips push a scrolling button below the fold, and it must never need
 * hunting for (BUG5).
 */
@Composable
public fun BackupNowScreen(
    state: BackupNowUiState,
    onEvent: (BackupNowEvent) -> Unit,
    modifier: Modifier = Modifier,
) {
    LfScaffold(
        modifier = modifier,
        bottomBar = { SubmitBar(state, onEvent) },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(LfTheme.spacing.md),
        ) {
            LfScreenTitle(title = "Back up now")
            Column(
                modifier = Modifier.padding(horizontal = LfTheme.spacing.lg),
                verticalArrangement = Arrangement.spacedBy(LfTheme.spacing.md),
            ) {
                Explanation()
                if (state.needsFolder) FolderPrompt(onEvent)
                state.folderName?.let { FolderLine(it, state.folderChanged, state.isWorking, onEvent) }
                state.result?.let { ResultMessage(it) }
                LfPhraseEntry(
                    words = state.entry.words,
                    draft = state.entry.draft,
                    suggestions = state.entry.suggestions,
                    draftIsUnknown = state.entry.draftIsUnknown,
                    requiredWordCount = state.entry.requiredWordCount,
                    onDraftChange = { onEvent(BackupNowEvent.DraftChanged(it)) },
                    onSuggestionTap = { onEvent(BackupNowEvent.WordCommitted(it)) },
                    onWordRemove = { onEvent(BackupNowEvent.WordRemoved(it)) },
                )
            }
        }
    }
}

/**
 * What happens to the words, said before they are typed. A screen that asks
 * for the one secret protecting every backup owes that sentence up front.
 */
@Composable
private fun Explanation() {
    LfCard {
        Text(
            text = "Your 24 words seal the backup. They're checked, used once and forgotten — " +
                "never saved or sent.",
            style = LfTheme.typography.bodyM,
            color = LfTheme.colors.textSecondary,
        )
    }
}

/**
 * The system folder picker, and the grant persisted at the only moment its
 * flags are valid to take — the same reason onboarding gives: without it the
 * grant dies with the process and the next backup fails.
 */
@Composable
private fun FolderPrompt(onEvent: (BackupNowEvent) -> Unit) {
    val launch = rememberFolderPicker(onEvent)
    LfCard {
        Column(verticalArrangement = Arrangement.spacedBy(LfTheme.spacing.sm)) {
            Text(
                text = "Choose where backups go — ideally a cloud drive, so they survive losing " +
                    "the phone.",
                style = LfTheme.typography.bodyM,
                color = LfTheme.colors.textPrimary,
            )
            LfButton(
                text = "Choose folder",
                onClick = launch,
                style = LfButtonStyle.Outlined,
            )
        }
    }
}

/**
 * Where backups go, and the way to move them — to a cloud drive, say. Without
 * it the only way to change folders was to lose the current one (BUG27).
 * Backups already written stay where they are, and after a change the screen
 * says so: the new folder starts empty.
 */
@Composable
private fun FolderLine(
    name: String,
    changed: Boolean,
    isWorking: Boolean,
    onEvent: (BackupNowEvent) -> Unit,
) {
    val launch = rememberFolderPicker(onEvent)
    LfCard {
        Column(verticalArrangement = Arrangement.spacedBy(LfTheme.spacing.xs)) {
            Text(
                text = "Backups go to $name.",
                style = LfTheme.typography.bodyM,
                color = LfTheme.colors.textPrimary,
            )
            if (changed) {
                Text(
                    text = "Earlier backups stay in the previous folder.",
                    style = LfTheme.typography.bodyM,
                    color = LfTheme.colors.textSecondary,
                )
            }
            LfActionRow(alignment = LfActionAlignment.Start) {
                LfButton(
                    text = "Change folder",
                    onClick = launch,
                    enabled = !isWorking,
                    style = LfButtonStyle.Inline,
                )
            }
        }
    }
}

@Composable
private fun ResultMessage(outcome: BackupOutcome) {
    Text(
        text = outcome.message(),
        style = LfTheme.typography.bodyM,
        color = if (outcome.isSuccess) LfTheme.colors.credit else LfTheme.colors.debit,
    )
}

@Composable
private fun SubmitBar(state: BackupNowUiState, onEvent: (BackupNowEvent) -> Unit) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(LfTheme.spacing.sm),
    ) {
        LfButton(
            text = if (state.entry.isComplete) "Back up" else "${state.entry.remaining} words to go",
            onClick = { onEvent(BackupNowEvent.Submitted) },
            enabled = state.canSubmit,
            loading = state.isWorking,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/** The system folder picker, with the grant persisted as it returns. */
@Composable
private fun rememberFolderPicker(onEvent: (BackupNowEvent) -> Unit): () -> Unit {
    val context = LocalContext.current
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            runCatching {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
                )
            }
        }
        onEvent(BackupNowEvent.FolderChosen(uri?.toString()))
    }
    return { picker.launch(null) }
}

// ── Previews (CLAUDE.md §5) ───────────────────────────────────────────────

@PreviewScreenSizes
@PreviewFontScale
@PreviewLightDark
@Composable
private fun BackupNowNeedsFolderPreview() {
    LfTheme {
        BackupNowScreen(
            state = BackupNowUiState(
                entry = PhraseEntry(requiredWordCount = 24, draft = "aban", suggestions = listOf("abandon")),
                folderChecked = true,
            ),
            onEvent = {},
        )
    }
}

@PreviewScreenSizes
@PreviewFontScale
@PreviewLightDark
@Composable
private fun BackupNowDonePreview() {
    LfTheme {
        BackupNowScreen(
            state = BackupNowUiState(
                entry = PhraseEntry(requiredWordCount = 24),
                folderName = "LedgerFlow backups",
                folderChecked = true,
                folderChanged = true,
                result = BackupOutcome.Done(
                    fileName = "ledgerflow-20260918-101500.lfbk",
                    rows = 412,
                    imagesWritten = 3,
                    imagesAlreadyThere = 9,
                    imagesUnreadable = 0,
                    imagesFailed = 0,
                    olderBackupsRemoved = 1,
                ),
            ),
            onEvent = {},
        )
    }
}
