package com.ledgerflow.feature.onboarding

import android.content.Intent
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.PreviewFontScale
import androidx.compose.ui.tooling.preview.PreviewLightDark
import androidx.compose.ui.tooling.preview.PreviewScreenSizes
import com.ledgerflow.core.designsystem.component.LfButton
import com.ledgerflow.core.designsystem.component.LfButtonStyle
import com.ledgerflow.core.designsystem.component.LfCard
import com.ledgerflow.core.designsystem.component.LfDialog
import com.ledgerflow.core.designsystem.component.LfDialogEmphasis
import com.ledgerflow.core.designsystem.component.LfScaffold
import com.ledgerflow.core.designsystem.component.LfTextField
import com.ledgerflow.core.designsystem.theme.LfTheme
import com.ledgerflow.core.domain.vault.RecoveryKitFormat

/**
 * The onboarding gate (SPEC.md §7.4).
 *
 * Stateless: every screen takes a state and emits events (CLAUDE.md §5). All
 * screens sit inside [LfScaffold], which consumes `WindowInsets.safeDrawing`,
 * so edge-to-edge is handled from the very first screen rather than retrofitted
 * (BUG5).
 */
@Composable
public fun OnboardingScreen(
    state: OnboardingUiState,
    onEvent: (OnboardingEvent) -> Unit,
    onGeneratePhrase: () -> Unit,
    modifier: Modifier = Modifier,
    kitFileName: (RecoveryKitFormat) -> String = { "LedgerFlow-Recovery-Kit.${it.extension}" },
) {
    // SAF, wired for real. Phase 0 emitted these events with empty URIs, which
    // meant the two gate steps that write to the filesystem did not.
    val chooseBackupTree = rememberBackupTreeLauncher(onEvent)
    RecoveryKitPicker(state, onEvent, kitFileName)
    state.kitConfirmFormat?.let { RecoveryKitConfirmDialog(it, onEvent) }

    LfScaffold(modifier = modifier) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(LfTheme.spacing.lg)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(LfTheme.spacing.lg),
        ) {
            when (state.step) {
                OnboardingStep.BaseCurrency -> BaseCurrencyStep(state, onEvent, onGeneratePhrase)
                OnboardingStep.PhraseDisplay -> PhraseDisplayStep(state, onEvent)
                OnboardingStep.WordChallenge -> WordChallengeStep(state, onEvent)
                OnboardingStep.RecoveryKit -> RecoveryKitStep(onEvent)
                OnboardingStep.BackupLocation -> BackupLocationStep(
                    onEvent = onEvent,
                    onChooseFolder = { chooseBackupTree() },
                )
                OnboardingStep.Complete -> CompleteStep(state)
            }
            state.errorMessage?.let { ErrorFooter(it, onEvent) }
        }
    }
}

/**
 * Opens the document picker once per confirmed request.
 *
 * Keyed on the format so a config change during the picker does not re-launch
 * it, and the ViewModel is told immediately that the request was consumed --
 * which is what stops a second picker appearing behind the first.
 */
@Composable
private fun RecoveryKitPicker(
    state: OnboardingUiState,
    onEvent: (OnboardingEvent) -> Unit,
    kitFileName: (RecoveryKitFormat) -> String,
) {
    val format = state.kitPickerRequest ?: state.kitConfirmFormat ?: RecoveryKitFormat.Text
    val createKit = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument(format.mimeType),
    ) { uri -> onEvent(OnboardingEvent.RecoveryKitFileChosen(uri?.toString())) }

    state.kitPickerRequest?.let { requested ->
        LaunchedEffect(requested) {
            createKit.launch(kitFileName(requested))
            onEvent(OnboardingEvent.RecoveryKitPickerLaunched)
        }
    }
}

@Composable
private fun rememberBackupTreeLauncher(onEvent: (OnboardingEvent) -> Unit): () -> Unit {
    val context = LocalContext.current
    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree(),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        // Without persisting it, the grant dies with the process and the nightly
        // BackupWorker wakes up to a SecurityException -- the silent-backup-failure
        // shape of BUG4. The moment of the grant is the only point where the
        // flags are still valid to take.
        runCatching {
            context.contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
            )
        }
        onEvent(OnboardingEvent.BackupLocationGranted(uri.toString()))
    }
    return { launcher.launch(null) }
}

@Composable
private fun ErrorFooter(message: String, onEvent: (OnboardingEvent) -> Unit) {
    Text(text = message, style = LfTheme.typography.bodyM, color = LfTheme.colors.debit)
    LfButton(
        text = "Dismiss",
        onClick = { onEvent(OnboardingEvent.ErrorDismissed) },
        style = LfButtonStyle.Text,
    )
}

@Composable
private fun StepHeading(title: String, body: String) {
    Column(verticalArrangement = Arrangement.spacedBy(LfTheme.spacing.sm)) {
        Text(text = title, style = LfTheme.typography.displayL, color = LfTheme.colors.textPrimary)
        Text(text = body, style = LfTheme.typography.bodyL, color = LfTheme.colors.textSecondary)
    }
}

@Composable
private fun BaseCurrencyStep(
    state: OnboardingUiState,
    onEvent: (OnboardingEvent) -> Unit,
    onContinue: () -> Unit,
) {
    StepHeading(
        title = "Choose your currency",
        // Stated plainly because §5.8 makes it permanent in v1. Discovering
        // afterwards that it cannot be changed would be a bad surprise.
        body = "All your amounts are stored in this currency. It cannot be changed later.",
    )
    LfCard {
        Column {
            SupportedCurrencies.forEach { currency ->
                val selected = currency.code == state.selectedCurrency
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .selectable(
                            selected = selected,
                            onClick = { onEvent(OnboardingEvent.CurrencySelected(currency.code)) },
                        )
                        .padding(vertical = LfTheme.spacing.sm),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(LfTheme.spacing.sm),
                ) {
                    // The row owns the selection semantics; the radio must not
                    // announce itself separately or TalkBack reads it twice.
                    RadioButton(
                        selected = selected,
                        onClick = null,
                        modifier = Modifier.clearAndSetSemantics {},
                    )
                    Text(
                        text = "${currency.symbol}  ${currency.displayName}",
                        style = LfTheme.typography.bodyL,
                        color = LfTheme.colors.textPrimary,
                    )
                }
            }
        }
    }
    LfButton(text = "Continue", onClick = onContinue, loading = state.isWorking)
}

@Composable
private fun PhraseDisplayStep(state: OnboardingUiState, onEvent: (OnboardingEvent) -> Unit) {
    StepHeading(
        title = "Your recovery phrase",
        body = "These 24 words are the only way to recover your data if you lose this " +
            "device. Write them down on paper and keep them somewhere safe. " +
            "Anyone with these words can read your backups.",
    )

    LfCard {
        if (!state.phraseRevealed) {
            // Hidden until deliberately revealed: the phrase should not be
            // sitting on screen while the user is still working out what it is.
            Column(verticalArrangement = Arrangement.spacedBy(LfTheme.spacing.md)) {
                Text(
                    text = "Hidden until you're ready. Make sure nobody is looking over " +
                        "your shoulder.",
                    style = LfTheme.typography.bodyM,
                    color = LfTheme.colors.textSecondary,
                )
                LfButton(
                    text = "Reveal phrase",
                    onClick = { onEvent(OnboardingEvent.PhraseRevealed) },
                    style = LfButtonStyle.Tonal,
                )
            }
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(LfTheme.spacing.xs)) {
                state.mnemonic.forEachIndexed { index, word ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .semantics { contentDescription = "Word ${index + 1}: $word" },
                        horizontalArrangement = Arrangement.spacedBy(LfTheme.spacing.sm),
                    ) {
                        Text(
                            text = "${index + 1}".padStart(2, ' '),
                            style = LfTheme.typography.mnemonicWord,
                            color = LfTheme.colors.textTertiary,
                        )
                        Text(
                            text = word,
                            style = LfTheme.typography.mnemonicWord,
                            color = LfTheme.colors.textPrimary,
                        )
                    }
                }
            }
        }
    }

    LfButton(
        text = "I've written them down",
        onClick = { onEvent(OnboardingEvent.PhraseAcknowledged) },
        enabled = state.phraseRevealed,
    )
}

@Composable
private fun WordChallengeStep(state: OnboardingUiState, onEvent: (OnboardingEvent) -> Unit) {
    StepHeading(
        title = "Confirm your phrase",
        body = "Enter the words at these positions to confirm you wrote them down.",
    )

    state.challengePositions.forEachIndexed { index, position ->
        LfTextField(
            value = state.challengeAnswers.getOrElse(index) { "" },
            onValueChange = { onEvent(OnboardingEvent.ChallengeAnswerChanged(index, it)) },
            label = "Word $position",
            isError = state.challengeError,
        )
    }

    if (state.challengeError) {
        Text(
            text = "That didn't match. Check your written copy — we've picked three " +
                "different words to try again.",
            style = LfTheme.typography.bodyM,
            color = LfTheme.colors.debit,
        )
    }

    LfButton(
        text = "Confirm",
        onClick = { onEvent(OnboardingEvent.ChallengeSubmitted) },
        enabled = state.challengeSatisfied,
    )

    // There is deliberately NO skip button here, and there must never be one
    // (SPEC.md §7.4, CLAUDE.md §7). A "remind me later" would defeat the entire
    // durability design. If you are adding one, stop.
}

@Composable
private fun RecoveryKitStep(onEvent: (OnboardingEvent) -> Unit) {
    StepHeading(
        title = "Save your Recovery Kit",
        body = "Your 24 words plus instructions for restoring your data. The text " +
            "file is what goes in a password manager; the PDF is what you print.",
    )
    LfButton(
        text = "Save as text file",
        onClick = { onEvent(OnboardingEvent.RecoveryKitRequested(RecoveryKitFormat.Text)) },
    )
    LfButton(
        text = "Save as PDF",
        onClick = { onEvent(OnboardingEvent.RecoveryKitRequested(RecoveryKitFormat.Pdf)) },
        style = LfButtonStyle.Tonal,
    )
    LfButton(
        text = "Skip — I've written them down",
        onClick = { onEvent(OnboardingEvent.RecoveryKitDismissed) },
        style = LfButtonStyle.Text,
    )
}

/**
 * The D-07 confirmation.
 *
 * The Recovery Kit is written in plaintext, and that decision was made on the
 * basis that the user is *told* so at the moment it matters. This dialog is that
 * telling — it is the entire mitigation, so it says what the file is, what it
 * grants, and where it is going, in those words.
 */
@Composable
private fun RecoveryKitConfirmDialog(
    format: RecoveryKitFormat,
    onEvent: (OnboardingEvent) -> Unit,
) {
    LfDialog(
        title = "This file is your master key",
        body = "The ${format.label()} contains your 24 words in plain text — it is not " +
            "encrypted. Anyone who opens it can read every backup this app will ever " +
            "write. You're about to save it to shared storage, which may sync to the " +
            "cloud. Store it the way you'd store a spare house key.",
        confirmText = "I understand — save it",
        emphasis = LfDialogEmphasis.Warning,
        onConfirm = { onEvent(OnboardingEvent.RecoveryKitConfirmed) },
        onDismiss = { onEvent(OnboardingEvent.RecoveryKitCancelled) },
    )
}

private fun RecoveryKitFormat.label(): String = when (this) {
    RecoveryKitFormat.Text -> "text file"
    RecoveryKitFormat.Pdf -> "PDF"
}

@Composable
private fun BackupLocationStep(
    onEvent: (OnboardingEvent) -> Unit,
    onChooseFolder: () -> Unit,
) {
    StepHeading(
        title = "Where should backups go?",
        body = "LedgerFlow writes an encrypted backup every night. Only your recovery " +
            "phrase can open it, so the location doesn't need to be private.",
    )
    LfButton(text = "Choose a folder", onClick = onChooseFolder)
    LfButton(
        text = "Not now",
        onClick = { onEvent(OnboardingEvent.BackupLocationDeclined) },
        style = LfButtonStyle.Text,
    )
}

@Composable
private fun CompleteStep(state: OnboardingUiState) {
    StepHeading(
        title = if (state.isWorking) "Setting up your ledger" else "You're set up",
        body = if (state.isWorking) {
            "Generating your encryption key and creating the database."
        } else {
            "Your ledger is encrypted and recoverable."
        },
    )
}

// ── Previews (CLAUDE.md §5: every top-level screen) ───────────────────────

@PreviewScreenSizes
@PreviewFontScale
@PreviewLightDark
@Composable
private fun OnboardingCurrencyPreview() {
    LfTheme {
        OnboardingScreen(state = OnboardingUiState(), onEvent = {}, onGeneratePhrase = {})
    }
}

@PreviewScreenSizes
@PreviewFontScale
@PreviewLightDark
@Composable
private fun OnboardingPhrasePreview() {
    LfTheme {
        OnboardingScreen(
            state = OnboardingUiState(
                step = OnboardingStep.PhraseDisplay,
                mnemonic = List(24) { "abandon" },
                phraseRevealed = true,
            ),
            onEvent = {},
            onGeneratePhrase = {},
        )
    }
}

@PreviewScreenSizes
@PreviewFontScale
@PreviewLightDark
@Composable
private fun OnboardingChallengePreview() {
    LfTheme {
        OnboardingScreen(
            state = OnboardingUiState(
                step = OnboardingStep.WordChallenge,
                challengePositions = listOf(3, 11, 19),
                challengeError = true,
            ),
            onEvent = {},
            onGeneratePhrase = {},
        )
    }
}
