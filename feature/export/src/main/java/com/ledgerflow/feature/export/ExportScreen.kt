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
import com.ledgerflow.core.designsystem.component.LfSegmentedControl
import com.ledgerflow.core.designsystem.theme.LfTheme
import com.ledgerflow.core.domain.export.ExportFormat

/**
 * Export the ledger as one Excel workbook (ADR-0004) or zipped CSV (ADR-0017),
 * SPEC.md §5.9.
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
                FileCard(state, suggestedFileName, onEvent)
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
private fun FileCard(state: ExportUiState, suggestedFileName: String, onEvent: (ExportEvent) -> Unit) {
    LfCard {
        Column(verticalArrangement = Arrangement.spacedBy(LfTheme.spacing.sm)) {
            // The format is chosen on the card that describes it, so the
            // description under the control always answers for the selection.
            LfSegmentedControl(
                options = FORMATS.map { it.title },
                selectedIndex = FORMATS.indexOf(state.format),
                onSelect = { index -> onEvent(ExportEvent.FormatSelected(FORMATS[index])) },
            )
            Text(
                text = state.format.description,
                style = LfTheme.typography.bodyM,
                color = LfTheme.colors.textSecondary,
            )
            // `Start`, not the default `Center`: these are facts sitting under a
            // heading, not controls the eye has to find, and centring would
            // float them away from the text they belong to. Still an
            // `LfActionRow` so they wrap as whole chips at font scale 2.0 rather
            // than breaking a label (BUG9).
            LfActionRow(alignment = LfActionAlignment.Start) {
                // Short enough for font scale 2.0 (BUG44: "Opens in any
                // spreadsheet" needed 321 dp of a 248 dp chip and was clipped).
                LfChip(label = "Any spreadsheet")
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
                        "${status.fileCount} ${status.format.containerNoun}.",
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
            text = if (state.status == ExportStatus.Working) "Exporting…" else "Export ${state.format.title}",
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
 *
 * **The sentence names bank messages explicitly**, and that is not padding. The
 * export gained `sms_raw` and `notification_raw` when the ingest tables were
 * added to it, so the zip now carries the bank's own texts verbatim -- account
 * digits, reference numbers, and messages that never became an entry at all.
 * "Every entry, note and amount" was an accurate description of the file before
 * that and an understatement after it, and a warning the user can rely on has to
 * name the most sensitive thing in the file rather than the most obvious.
 */
@Composable
private fun WarningDialog(onEvent: (ExportEvent) -> Unit) {
    LfDialog(
        title = "Export without encryption?",
        body = "The file holds every entry, note and amount in plain text, plus " +
            "the bank messages LedgerFlow captured. Anyone who opens it can read " +
            "all of it. Choose somewhere private, and delete it when you are finished.",
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
    // One launcher per format: a CreateDocument contract's MIME type is fixed
    // at construction, and it is what the picker files the document as.
    val createXlsx = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument(ExportFormat.XLSX.mime),
    ) { uri -> onEvent(ExportEvent.DestinationChosen(uri?.toString())) }
    val createCsv = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument(ExportFormat.CSV.mime),
    ) { uri -> onEvent(ExportEvent.DestinationChosen(uri?.toString())) }

    if (state.pickerRequest) {
        LaunchedEffect(Unit) {
            when (state.format) {
                ExportFormat.XLSX -> createXlsx.launch(suggestedFileName)
                ExportFormat.CSV -> createCsv.launch(suggestedFileName)
            }
            onEvent(ExportEvent.PickerLaunched)
        }
    }
}

private fun rowNoun(count: Int): String = if (count == 1) "row" else "rows"

/** The control's order; XLSX first, as the default. */
private val FORMATS = listOf(ExportFormat.XLSX, ExportFormat.CSV)

private val ExportFormat.title: String
    get() = when (this) {
        ExportFormat.XLSX -> "Excel"
        ExportFormat.CSV -> "CSV"
    }

private val ExportFormat.description: String
    get() = when (this) {
        ExportFormat.XLSX -> "One workbook. Monthly totals and spending and income by category " +
            "come first, then every table on its own sheet."
        ExportFormat.CSV -> "One file per table, zipped. For feeding another tool."
    }

private val ExportFormat.containerNoun: String
    get() = when (this) {
        ExportFormat.XLSX -> "sheets"
        ExportFormat.CSV -> "files"
    }

private val ExportFormat.mime: String
    get() = when (this) {
        ExportFormat.XLSX -> "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
        ExportFormat.CSV -> "application/zip"
    }

// ── Previews (CLAUDE.md §5) ───────────────────────────────────────────────

@PreviewScreenSizes
@PreviewFontScale
@PreviewLightDark
@Composable
private fun ExportPreview() {
    LfTheme {
        ExportScreen(
            state = ExportUiState(),
            suggestedFileName = "LedgerFlow-export-2026-08-21.xlsx",
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
            state = ExportUiState(
                status = ExportStatus.Done(fileCount = 24, rowCount = 1_482, format = ExportFormat.XLSX),
            ),
            suggestedFileName = "LedgerFlow-export-2026-08-21.xlsx",
            onEvent = {},
            onBack = {},
        )
    }
}
