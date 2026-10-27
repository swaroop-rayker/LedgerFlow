package com.ledgerflow.feature.onboarding

import android.content.Intent
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
import com.ledgerflow.core.designsystem.component.LfErrorNotice
import com.ledgerflow.core.designsystem.component.LfKeyboards
import com.ledgerflow.core.designsystem.component.LfScaffold
import com.ledgerflow.core.designsystem.component.LfTextField
import com.ledgerflow.core.designsystem.format.bidiIsolated
import com.ledgerflow.core.designsystem.pattern.LfActionBar
import com.ledgerflow.core.designsystem.pattern.LfConfirmSheet
import com.ledgerflow.core.designsystem.pattern.LfFlowStep
import com.ledgerflow.core.designsystem.theme.LfTheme
import com.ledgerflow.core.domain.vault.RecoveryKitFormat
import com.ledgerflow.core.ui.phrase.LfRecoveryKitWarningDialog
import com.ledgerflow.core.ui.phrase.phraseSecret

/**
 * The onboarding gate (SPEC.md §7.4).
 *
 * Stateless: every screen takes a state and emits events (CLAUDE.md §5). All
 * screens sit inside [LfScaffold], which consumes the system bars and the
 * display cutout for layout and the keyboard exactly once, so edge-to-edge is
 * handled from the very first screen rather than retrofitted (BUG5).
 * (Deliberately not `safeDrawing` -- that set includes the IME; see
 * `LfScaffold` for the two measured failures that produced the split.)
 *
 * The step's one primary action is pinned to the scaffold rather than scrolled
 * with the content -- see [PrimaryActionBar].
 */
@Composable
public fun OnboardingScreen(
    state: OnboardingUiState,
    onEvent: (OnboardingEvent) -> Unit,
    onGeneratePhrase: () -> Unit,
    modifier: Modifier = Modifier,
    kitFileName: (RecoveryKitFormat) -> String = { "LedgerFlow-Recovery-Kit.${it.extension}" },
    onRestoreRequested: () -> Unit = {},
) {
    // SAF, wired for real. Phase 0 emitted these events with empty URIs, which
    // meant the two gate steps that write to the filesystem did not.
    val chooseBackupTree = rememberBackupTreeLauncher(onEvent)
    RecoveryKitPicker(state, onEvent, kitFileName)
    state.kitConfirmFormat?.let { RecoveryKitConfirmDialog(it, onEvent) }
    if (state.confirmingBackupDecline) SkipBackupsSheet(onEvent)

    val copy = state.stepCopy()
    LfFlowStep(
        title = copy.title,
        body = copy.body,
        modifier = modifier,
        actionBar = {
            PrimaryActionBar(
                state = state,
                onEvent = onEvent,
                onContinue = onGeneratePhrase,
                onChooseFolder = { chooseBackupTree() },
            )
        },
    ) {
        when (state.step) {
            OnboardingStep.BaseCurrency -> {
                BaseCurrencyStep(state, onEvent)
                RestoreLink(onRestoreRequested)
            }
            OnboardingStep.PhraseDisplay -> PhraseDisplayStep(state, onEvent)
            OnboardingStep.WordChallenge -> WordChallengeStep(state, onEvent)
            OnboardingStep.RecoveryKit -> RecoveryKitStep(onEvent)
            OnboardingStep.BackupLocation -> BackupLocationStep(onEvent)
            // Only a heading (the template's) while the key is made, and a
            // way forward if making it failed (BUG66).
            OnboardingStep.Complete -> if (state.setupFailed) SetupFailedNotice(onEvent)
        }
        state.errorMessage?.let { ErrorFooter(it, onEvent) }
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
    val format = state.kitPickerRequest ?: state.kitConfirmFormat ?: PRIMARY_KIT_FORMAT
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

/**
 * What the step's primary action is, if it has one.
 *
 * Separated from [PrimaryActionBar] so the mapping is an exhaustive `when` over
 * [OnboardingStep] with no `else` (CLAUDE.md §5): a seventh step then fails to
 * compile here rather than shipping a screen whose only action is missing.
 */
private data class PrimaryAction(
    val label: String,
    val enabled: Boolean,
    val loading: Boolean,
    val onClick: () -> Unit,
)

private fun primaryActionOf(
    state: OnboardingUiState,
    onEvent: (OnboardingEvent) -> Unit,
    onContinue: () -> Unit,
    onChooseFolder: () -> Unit,
): PrimaryAction? = when (state.step) {
    OnboardingStep.BaseCurrency -> PrimaryAction(
        label = "Continue",
        enabled = true,
        loading = state.isWorking,
        onClick = onContinue,
    )

    OnboardingStep.PhraseDisplay -> PrimaryAction(
        // Seventeen characters, so it fits a phone at font scale 2.0 (BUG44:
        // "I've written them down" was clipped to "I've written them do").
        label = "I wrote them down",
        enabled = state.phraseRevealed,
        loading = false,
        onClick = { onEvent(OnboardingEvent.PhraseAcknowledged) },
    )

    OnboardingStep.WordChallenge -> PrimaryAction(
        label = "Confirm",
        enabled = state.challengeSatisfied,
        loading = false,
        onClick = { onEvent(OnboardingEvent.ChallengeSubmitted) },
    )

    // The PDF (owner, 2026-09-26): the words and the QR code in one file, so
    // the obvious path gives a new user a kit they can scan. The text file,
    // which cannot carry a code, is the secondary option in the step's body.
    OnboardingStep.RecoveryKit -> PrimaryAction(
        label = "Save as PDF",
        enabled = true,
        loading = false,
        onClick = { onEvent(OnboardingEvent.RecoveryKitRequested(PRIMARY_KIT_FORMAT)) },
    )

    OnboardingStep.BackupLocation -> PrimaryAction(
        label = "Choose a folder",
        enabled = true,
        loading = false,
        onClick = onChooseFolder,
    )

    // Nothing to press. The step is a progress report that advances itself.
    OnboardingStep.Complete -> null
}

/**
 * The step's primary action, pinned to the scaffold rather than scrolled with
 * the content.
 *
 * Every step's CTA used to sit at the bottom of the scrolling `Column`. That is
 * reachable at font scale 1.0 and not at 2.0: measured on the device, **three
 * of the five gate steps had their CTA off screen** — the phrase step behind
 * twenty-four words, the word challenge behind three fields and an error, and,
 * least expected, the very first screen, where the currency list alone pushes
 * "Continue" past the fold. Onboarding is a gate, so that is not a degraded
 * experience; it is a user who cannot enter the app at all unless they think to
 * scroll. §9.6 requires 2.0 to work, not merely to render, and `RecoveryScreen`
 * already pins its one action for the same reason.
 *
 * **Only the primary action moves.** "Save as text file", "Skip", "Not now" stay in
 * the content: they are alternatives to the action, and a bar of three stacked
 * buttons at 2.0 costs the scrolling area more than it gives back — the header
 * and body copy are what the user has to read before choosing at all. The
 * corollary is that a step must never put its *only* way forward in a secondary
 * action.
 *
 * An [LfActionBar]: the screen edge across, to line up with the content, and a
 * small inset vertically, since [LfScaffold] has already inset this bar for the
 * navigation bar (CLAUDE.md §5).
 */
@Composable
private fun PrimaryActionBar(
    state: OnboardingUiState,
    onEvent: (OnboardingEvent) -> Unit,
    onContinue: () -> Unit,
    onChooseFolder: () -> Unit,
) {
    val action = primaryActionOf(state, onEvent, onContinue, onChooseFolder) ?: return
    LfActionBar {
        LfButton(
            text = action.label,
            onClick = action.onClick,
            enabled = action.enabled,
            loading = action.loading,
            modifier = Modifier.fillMaxWidth(),
        )
    }
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

/** Each step's heading, drawn by the template above the step's content. */
private class StepCopy(val title: String, val body: String)

private fun OnboardingUiState.stepCopy(): StepCopy = when (step) {
    OnboardingStep.BaseCurrency -> StepCopy(
        title = "Choose your currency",
        // Stated plainly because §5.8 makes it permanent in v1. Discovering
        // afterwards that it cannot be changed would be a bad surprise.
        body = "All your amounts are stored in this currency. It cannot be changed later.",
    )
    OnboardingStep.PhraseDisplay -> StepCopy(
        title = "Your recovery phrase",
        body = "These 24 words are the only way to recover your data if you lose this " +
            "device. Write them down on paper and keep them somewhere safe. " +
            "Anyone with these words can read your backups.",
    )
    OnboardingStep.WordChallenge -> StepCopy(
        title = "Confirm your phrase",
        body = "Enter the words at these positions to confirm you wrote them down.",
    )
    OnboardingStep.RecoveryKit -> StepCopy(
        title = "Save your Recovery Kit",
        body = "Your 24 words and a QR code you can scan instead of typing them, on one " +
            "printable page, with instructions for restoring your data. A text file — the " +
            "words only, no code — is there for a password manager.",
    )
    OnboardingStep.BackupLocation -> StepCopy(
        title = "Where should backups go?",
        body = "LedgerFlow writes an encrypted backup every night. Only your recovery " +
            "phrase can open it, so the location doesn't need to be private.",
    )
    OnboardingStep.Complete -> when {
        isWorking -> StepCopy(
            title = "Setting up your ledger",
            body = "Generating your encryption key and creating the database.",
        )
        // BUG66: this said "You're set up" over the failure, with only "Dismiss"
        // under it. Success never reaches this branch for long -- the vault
        // opening is what moves the app on -- so a step still showing after the
        // work stopped is a failure until proved otherwise.
        setupFailed -> StepCopy(
            title = "Couldn't finish setting up",
            body = "Nothing was lost, and your recovery phrase is unchanged. You won't " +
                "need to write it down again.",
        )
        else -> StepCopy(
            title = "You're set up",
            body = "Your ledger is encrypted and recoverable.",
        )
    }
}

/** The failed setup's one way forward: the same setup again (BUG66). */
@Composable
private fun SetupFailedNotice(onEvent: (OnboardingEvent) -> Unit) {
    LfErrorNotice(
        title = "Your ledger wasn't created",
        actionLabel = "Try again",
        onAction = { onEvent(OnboardingEvent.SetupRetried) },
    )
}

/**
 * §16 Q11's branch: a quiet link, not a second primary action. Onboarding stays
 * the path the screen leads with; someone moving from another phone finds the
 * way out on the first screen, before choosing a currency their backup already
 * carries.
 */
@Composable
private fun RestoreLink(onRestoreRequested: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(LfTheme.spacing.xs)) {
        Text(
            text = "Moving from another phone? You'll need a backup and its 24 words.",
            style = LfTheme.typography.bodyM,
            color = LfTheme.colors.textSecondary,
        )
        LfButton(
            text = "Restore from a backup",
            onClick = onRestoreRequested,
            style = LfButtonStyle.Inline,
        )
    }
}

@Composable
private fun BaseCurrencyStep(state: OnboardingUiState, onEvent: (OnboardingEvent) -> Unit) {
    LfCard {
        Column {
            SupportedCurrencies.forEach { currency ->
                val selected = currency.code == state.selectedCurrency
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        // A full-size target (BUG43): the radio has no onClick, so
                        // it brings no 48 dp minimum, and this row is a choice the
                        // user cannot change later (CLAUDE.md §0).
                        .defaultMinSize(minHeight = LfTheme.spacing.minTouchTarget)
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
                        // Isolated: the dirham's Arabic symbol would otherwise make
                        // this line right-to-left and put the name first (BUG61).
                        text = "${bidiIsolated(currency.symbol)}  ${currency.displayName}",
                        style = LfTheme.typography.bodyL,
                        color = LfTheme.colors.textPrimary,
                    )
                }
            }
        }
    }
}

@Composable
private fun PhraseDisplayStep(state: OnboardingUiState, onEvent: (OnboardingEvent) -> Unit) {
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
            Column(
                modifier = Modifier.phraseSecret(),
                verticalArrangement = Arrangement.spacedBy(LfTheme.spacing.xs),
            ) {
                state.mnemonic.forEachIndexed { index, word ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .phraseSecret()
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
}

@Composable
private fun WordChallengeStep(state: OnboardingUiState, onEvent: (OnboardingEvent) -> Unit) {
    state.challengePositions.forEachIndexed { index, position ->
        LfTextField(
            modifier = Modifier.phraseSecret(),
            value = state.challengeAnswers.getOrElse(index) { "" },
            onValueChange = { onEvent(OnboardingEvent.ChallengeAnswerChanged(index, it)) },
            label = "Word $position",
            isError = state.challengeError,
            // The keyboard must not learn the words (LfKeyboards.RecoveryWord).
            keyboardOptions = LfKeyboards.RecoveryWord,
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

    // "Confirm" is pinned in [PrimaryActionBar]. There is deliberately NO skip
    // button here or there, and there must never be one (SPEC.md §7.4,
    // CLAUDE.md §7). A "remind me later" would defeat the entire durability
    // design. If you are adding one, stop.
}

@Composable
private fun RecoveryKitStep(onEvent: (OnboardingEvent) -> Unit) {
    // "Save as PDF" is the pinned primary; these two are the alternatives to it.
    LfButton(
        text = "Save as text file",
        onClick = { onEvent(OnboardingEvent.RecoveryKitRequested(RecoveryKitFormat.Text)) },
        style = LfButtonStyle.Tonal,
    )
    // "Skip the kit", not "Skip — I've written them down": the long form needed
    // 405 dp at font scale 2.0 and was clipped (BUG44). Skipping still raises
    // the explicit warning before anything is dismissed.
    LfButton(
        text = "Skip the kit",
        onClick = { onEvent(OnboardingEvent.RecoveryKitDismissed) },
        style = LfButtonStyle.Text,
    )
}

/**
 * The D-07 confirmation — the shared one, so onboarding and "Back up now" say
 * the same thing about the same file. The PDF carries a QR code (ADR-0028).
 */
@Composable
private fun RecoveryKitConfirmDialog(
    format: RecoveryKitFormat,
    onEvent: (OnboardingEvent) -> Unit,
) {
    LfRecoveryKitWarningDialog(
        fileLabel = format.label(),
        hasQrCode = format == RecoveryKitFormat.Pdf,
        onConfirm = { onEvent(OnboardingEvent.RecoveryKitConfirmed) },
        onDismiss = { onEvent(OnboardingEvent.RecoveryKitCancelled) },
    )
}

/**
 * What the Recovery Kit step's main button saves: the PDF, which carries the
 * words **and** a QR code in one file (ADR-0028; owner, 2026-09-26). Before this
 * it was the text file, so a new user taking the obvious path got a kit with
 * nothing to scan.
 */
internal val PRIMARY_KIT_FORMAT: RecoveryKitFormat = RecoveryKitFormat.Pdf

private fun RecoveryKitFormat.label(): String = when (this) {
    RecoveryKitFormat.Text -> "text file"
    RecoveryKitFormat.Pdf -> "PDF"
}

@Composable
private fun BackupLocationStep(onEvent: (OnboardingEvent) -> Unit) {
    LfButton(
        text = "Not now",
        onClick = { onEvent(OnboardingEvent.BackupLocationDeclineRequested) },
        style = LfButtonStyle.Text,
    )
}

/**
 * The warning `OnboardingStep.BackupLocation` always promised and "Not now"
 * never showed (BUG67). A sheet, not a `Warning` dialog: declining can be
 * undone from Back up now at any time, so it is consequential rather than
 * irreversible (§5.2).
 */
@Composable
private fun SkipBackupsSheet(onEvent: (OnboardingEvent) -> Unit) {
    LfConfirmSheet(
        title = "Skip nightly backups?",
        body = "Nothing is backed up until you choose a folder in Profile > Data > Back up " +
            "now. If this phone is lost or reset before then, your ledger goes with it.",
        confirmLabel = "Not now",
        onConfirm = { onEvent(OnboardingEvent.BackupLocationDeclined) },
        onDismiss = { onEvent(OnboardingEvent.BackupLocationDeclineCancelled) },
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

@PreviewScreenSizes
@PreviewFontScale
@PreviewLightDark
@Composable
private fun OnboardingSetupFailedPreview() {
    LfTheme {
        OnboardingScreen(
            state = OnboardingUiState(step = OnboardingStep.Complete, setupFailed = true),
            onEvent = {},
            onGeneratePhrase = {},
        )
    }
}
