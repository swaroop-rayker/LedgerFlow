package com.ledgerflow.feature.export

import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.PreviewFontScale
import androidx.compose.ui.tooling.preview.PreviewLightDark
import androidx.compose.ui.tooling.preview.PreviewScreenSizes
import com.ledgerflow.core.designsystem.component.LfActionAlignment
import com.ledgerflow.core.designsystem.component.LfActionRow
import com.ledgerflow.core.designsystem.component.LfButton
import com.ledgerflow.core.designsystem.component.LfButtonStyle
import com.ledgerflow.core.designsystem.component.LfCard
import com.ledgerflow.core.designsystem.component.LfChip
import com.ledgerflow.core.designsystem.component.LfChipStyle
import com.ledgerflow.core.designsystem.component.LfDialog
import com.ledgerflow.core.designsystem.component.LfDialogEmphasis
import com.ledgerflow.core.designsystem.component.LfScaffold
import com.ledgerflow.core.designsystem.component.LfScreenTitle
import com.ledgerflow.core.designsystem.theme.LfTheme

/**
 * Export the ledger as zipped CSV (SPEC.md §5.9, ADR-0017).
 *
 * Stateless: state in, one event lambda out (CLAUDE.md §5).
 *
 * **The screen's real job is the warning, not the button.** Everything else here
 * is one tap and a system picker; what needs designing is that the artifact is a
 * complete, unencrypted copy of the user's financial history, and that once it
 * is written the app has no further say in where it goes. The page flags that
 * standing, as a chip; the confirmation says it in full before every export, in
 * the treatment reserved for the Recovery Kit and the bin's erase.
 */
@Composable
public fun ExportScreen(
    state: ExportUiState,
    suggestedFileName: String,
    onEvent: (ExportEvent) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (state.confirming) WarningDialog(onEvent)
    DestinationPicker(state, suggestedFileName, onEvent)

    LfScaffold(
        modifier = modifier,
        bottomBar = { ExportBar(state, onEvent) },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(LfTheme.spacing.sm),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                LfScreenTitle(title = "Export", modifier = Modifier.weight(1f))
                LfButton(
                    text = "Done",
                    style = LfButtonStyle.Text,
                    onClick = onBack,
                    modifier = Modifier.padding(end = LfTheme.spacing.md),
                )
            }

            Column(
                modifier = Modifier.padding(horizontal = LfTheme.spacing.lg),
                verticalArrangement = Arrangement.spacedBy(LfTheme.spacing.sm),
            ) {
                CsvFileCard(suggestedFileName)
                state.status.let { status -> StatusCard(status, onEvent) }
            }
        }
    }
}

/**
 * What the export is, in one card.
 *
 * This was two cards and two paragraphs -- "What you get" and "This file is not
 * encrypted" -- and between them they said more about the export than anyone
 * standing on this screen needs in order to decide. Two facts matter: it opens
 * in a spreadsheet, and it is not protected. Both are properties of the file, so
 * they belong to the same card rather than to two competing ones.
 *
 * **Chips rather than sentences**, because a property is not an explanation. It
 * also keeps the warning from being the longest paragraph on the screen, which
 * is what people scroll past.
 *
 * The warning chip keeps the `warn` outline and text, so it still carries weight
 * at a glance -- and it is [LfChipStyle.Warning] rather than `Error`, because
 * nothing here has failed. The full sentence lives in the confirmation, which
 * fires before every export and cannot be skipped, so this is a flag rather than
 * the whole of the user's protection.
 */
@Composable
private fun CsvFileCard(suggestedFileName: String) {
    LfCard {
        Column(verticalArrangement = Arrangement.spacedBy(LfTheme.spacing.sm)) {
            Text(
                text = "CSV file",
                style = LfTheme.typography.bodyL,
                color = LfTheme.colors.textPrimary,
            )
            // `Start`, not the default `Center`: these are facts sitting under a
            // heading, not controls the eye has to find, and centring would
            // float them away from the text they belong to. Still an
            // `LfActionRow` so they wrap as whole chips at font scale 2.0 rather
            // than breaking a label (BUG9).
            LfActionRow(alignment = LfActionAlignment.Start) {
                LfChip(label = "Opens in any spreadsheet")
                LfChip(label = "Not encrypted", style = LfChipStyle.Warning)
            }
            Text(
                text = suggestedFileName,
                style = LfTheme.typography.label,
                color = LfTheme.colors.textTertiary,
            )
        }
    }
}

@Composable
private fun StatusCard(status: ExportStatus, onEvent: (ExportEvent) -> Unit) {
    when (status) {
        ExportStatus.Idle, ExportStatus.Working -> Unit

        is ExportStatus.Done -> LfCard {
            Column(verticalArrangement = Arrangement.spacedBy(LfTheme.spacing.xs)) {
                Text(
                    text = "Exported",
                    style = LfTheme.typography.bodyL,
                    color = LfTheme.colors.credit,
                )
                Text(
                    // The counts are the receipt. "Done" alone gives the user no
                    // way to tell a real export from one that wrote empty files.
                    text = "${status.rowCount} ${rowNoun(status.rowCount)} across " +
                        "${status.fileCount} files.",
                    style = LfTheme.typography.bodyM,
                    color = LfTheme.colors.textSecondary,
                )
                LfButton(
                    text = "OK",
                    style = LfButtonStyle.Text,
                    onClick = { onEvent(ExportEvent.StatusDismissed) },
                )
            }
        }

        is ExportStatus.Failed -> LfCard {
            Column(verticalArrangement = Arrangement.spacedBy(LfTheme.spacing.xs)) {
                Text(
                    text = "Not exported",
                    style = LfTheme.typography.bodyL,
                    color = LfTheme.colors.debit,
                )
                Text(
                    text = status.message,
                    style = LfTheme.typography.bodyM,
                    color = LfTheme.colors.textSecondary,
                )
                LfButton(
                    text = "OK",
                    style = LfButtonStyle.Text,
                    onClick = { onEvent(ExportEvent.StatusDismissed) },
                )
            }
        }
    }
}

/**
 * The one action, pinned.
 *
 * `xs` vertical padding, not `lg`: `LfScaffold` has already inset this bar for
 * the navigation bar, and a second full inset below the button spends screen
 * height on space the system bar was already reserving (BUG5, §8).
 */
@Composable
private fun ExportBar(state: ExportUiState, onEvent: (ExportEvent) -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(
                start = LfTheme.spacing.lg,
                end = LfTheme.spacing.lg,
                top = LfTheme.spacing.xs,
                bottom = LfTheme.spacing.xs,
            ),
    ) {
        LfButton(
            text = if (state.status == ExportStatus.Working) "Exporting…" else "Export CSV",
            modifier = Modifier.fillMaxWidth(),
            loading = state.status == ExportStatus.Working,
            enabled = state.status != ExportStatus.Working,
            onClick = { onEvent(ExportEvent.ExportRequested) },
        )
    }
}

/**
 * The mis-tap guard, and the last point at which the user can stop.
 *
 * `Warning` emphasis, which also stops an outside tap standing in for an answer
 * — the treatment otherwise reserved for the Recovery Kit and the bin's erase.
 * It does not offer to encrypt instead, because that is a different artifact
 * with a different name (`.lfbk`) reached from a different screen, and a dialog
 * that offered would be promising something this button cannot do.
 */
@Composable
private fun WarningDialog(onEvent: (ExportEvent) -> Unit) {
    LfDialog(
        title = "Export without encryption?",
        body = "The file holds every entry, note and amount in plain text. " +
            "Anyone who opens it can read all of it. Choose somewhere private, " +
            "and delete it when you are finished.",
        confirmText = "Choose location",
        emphasis = LfDialogEmphasis.Warning,
        onConfirm = { onEvent(ExportEvent.WarningAccepted) },
        onDismiss = { onEvent(ExportEvent.WarningDismissed) },
    )
}

/**
 * Opens the document picker once per confirmed request.
 *
 * The request is consumed the instant the launcher fires, which is what stops a
 * config change during the picker from putting a second one behind the first —
 * the same shape `OnboardingScreen` uses for the Recovery Kit.
 */
@Composable
private fun DestinationPicker(
    state: ExportUiState,
    suggestedFileName: String,
    onEvent: (ExportEvent) -> Unit,
) {
    val create = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument(ZIP_MIME),
    ) { uri -> onEvent(ExportEvent.DestinationChosen(uri?.toString())) }

    if (state.pickerRequest) {
        LaunchedEffect(Unit) {
            create.launch(suggestedFileName)
            onEvent(ExportEvent.PickerLaunched)
        }
    }
}

private fun rowNoun(count: Int): String = if (count == 1) "row" else "rows"

private const val ZIP_MIME = "application/zip"

// ── Previews (CLAUDE.md §5) ───────────────────────────────────────────────

@PreviewScreenSizes
@PreviewFontScale
@PreviewLightDark
@Composable
private fun ExportPreview() {
    LfTheme {
        ExportScreen(
            state = ExportUiState(),
            suggestedFileName = "LedgerFlow-export-2026-08-21.zip",
            onEvent = {},
            onBack = {},
        )
    }
}

@PreviewScreenSizes
@PreviewFontScale
@PreviewLightDark
@Composable
private fun ExportDonePreview() {
    LfTheme {
        ExportScreen(
            state = ExportUiState(status = ExportStatus.Done(fileCount = 11, rowCount = 1_482)),
            suggestedFileName = "LedgerFlow-export-2026-08-21.zip",
            onEvent = {},
            onBack = {},
        )
    }
}
