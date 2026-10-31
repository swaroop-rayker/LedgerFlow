package com.ledgerflow.feature.onboarding.recovery

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
import androidx.compose.ui.tooling.preview.PreviewFontScale
import androidx.compose.ui.tooling.preview.PreviewLightDark
import androidx.compose.ui.tooling.preview.PreviewScreenSizes
import com.ledgerflow.core.designsystem.component.LfButton
import com.ledgerflow.core.designsystem.component.LfButtonStyle
import com.ledgerflow.core.designsystem.component.LfCard
import com.ledgerflow.core.designsystem.component.LfScaffold
import com.ledgerflow.core.designsystem.pattern.LfActionBar
import com.ledgerflow.core.designsystem.pattern.LfFlowStep
import com.ledgerflow.core.designsystem.theme.LfTextAlign
import com.ledgerflow.core.designsystem.theme.LfTheme
import com.ledgerflow.core.domain.vault.PhraseEntry
import com.ledgerflow.core.domain.vault.RecoveryReason
import com.ledgerflow.core.ui.phrase.LfPhraseEntry
import com.ledgerflow.core.ui.phrase.LfPhraseScanner
import com.ledgerflow.feature.onboarding.phrase.rejectionMessage

/**
 * The Recovery screen (SPEC.md §7.3 step 2).
 *
 * **There is no wipe, no reset and no "start over" on this screen.** A user who
 * arrives here has not lost anything yet, and the screen must not suggest
 * otherwise. Everything here is additive: type words, remove a word, try again.
 */
@Composable
public fun RecoveryScreen(
    state: RecoveryUiState,
    onEvent: (RecoveryEvent) -> Unit,
    modifier: Modifier = Modifier,
) {
    // The scanner takes the whole screen while it is open: a viewfinder beside
    // a word list is two things asking for the same attention, and the camera
    // is a moment rather than a mode (ADR-0028).
    if (state.isScanning) {
        LfPhraseScanner(
            onScanned = { onEvent(RecoveryEvent.Scanner.Read(it)) },
            onDismiss = { onEvent(RecoveryEvent.Scanner.Dismissed) },
            modifier = modifier,
        )
        return
    }

    LfFlowStep(
        title = "Enter your recovery phrase",
        body = state.reason.explanation(),
        modifier = modifier,
        actionBar = { SubmitBar(state, onEvent) },
    ) {
        Reassurance()

        // Shared with "Back up now", including the keyboard setting that
        // keeps the words out of the keyboard's dictionary (BUG25).
        LfPhraseEntry(
            words = state.words,
            draft = state.draft,
            suggestions = state.suggestions,
            draftIsUnknown = state.draftIsUnknown,
            requiredWordCount = state.requiredWordCount,
            onDraftChange = { onEvent(RecoveryEvent.DraftChanged(it)) },
            onSuggestionTap = { onEvent(RecoveryEvent.WordCommitted(it)) },
            onWordRemove = { onEvent(RecoveryEvent.WordRemoved(it)) },
            onScanRequested = { onEvent(RecoveryEvent.Scanner.Requested) },
        )

        state.scanMessage?.let { ScanMessage(it) }
        state.failure?.let { FailureMessage(it) }
    }
}

/**
 * Pinned to the scaffold rather than scrolled with the content.
 *
 * At 2.0x font scale the word chips push a scrolling button well below the
 * fold, and the one action on this screen must never need hunting for (BUG5).
 * The label doubles as the progress indicator so there is no separate counter
 * competing for attention.
 */
@Composable
private fun SubmitBar(state: RecoveryUiState, onEvent: (RecoveryEvent) -> Unit) {
    LfActionBar {
        LfButton(
            text = if (state.isComplete) "Unlock" else "${state.remaining} words to go",
            onClick = { onEvent(RecoveryEvent.Submitted) },
            enabled = state.isComplete,
            loading = state.isWorking,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun Reassurance() {
    // Reassurance before the work, not after it. This screen is the one place a
    // user reasonably fears their data is gone; saying plainly that it is not is
    // the most useful sentence on the page.
    LfCard {
        Text(
            text = "Your data is still here and still encrypted. These 24 words unlock " +
                "it. Nothing is deleted by getting this wrong.",
            style = LfTheme.type.bodySmall,
            color = LfTheme.colors.text.secondary,
        )
    }
}

/** A scan that was ours and unusable. Not an error state: the field still works. */
@Composable
private fun ScanMessage(message: String) {
    Text(
        text = "$message Type the words instead.",
        style = LfTheme.type.bodySmall,
        color = LfTheme.colors.text.secondary,
        textAlign = LfTextAlign.start,
    )
}

@Composable
private fun FailureMessage(failure: RecoveryFailure) {
    Text(
        text = failure.message(),
        style = LfTheme.type.bodySmall,
        // A form's error line, in the problem colour rather than money-out's
        // (E2). Not a notice: the pinned Unlock is the retry, and a notice
        // would need a second one.
        color = LfTheme.colors.status.danger,
        textAlign = LfTextAlign.start,
    )
}

/**
 * One sentence per reason.
 *
 * Deliberately free of jargon: nobody outside this repo knows what a Keystore
 * or a DEK is, and a recovery screen written in implementation vocabulary reads
 * as "something broke badly" regardless of what it says.
 */
private fun RecoveryReason.explanation(): String = when (this) {
    RecoveryReason.KeystoreUnavailable ->
        "This device's security key changed — that usually happens after a screen " +
            "lock change, a fingerprint re-enrolment, or restoring the phone."

    RecoveryReason.KeystoreWrapMissing ->
        "The quick-unlock key for this device is missing."

    RecoveryReason.KeystoreWrapDamaged ->
        "The quick-unlock key for this device could not be read."

    RecoveryReason.CanaryMismatch ->
        "Your data does not match the key on this device — this happens after " +
            "restoring a backup onto a fresh install."

    RecoveryReason.DatabaseUnopenable ->
        "Your ledger could not be opened with the key on this device."
}

private fun RecoveryFailure.message(): String = when (this) {
    RecoveryFailure.PhraseDidNotMatch ->
        "Those 24 words are a valid phrase, but not the one for this data. " +
            "Check you're using the Recovery Kit for this install."

    is RecoveryFailure.Other ->
        "That didn't work. Your data has not been changed — you can try again."

    is RecoveryFailure.PhraseRejected -> validation.rejectionMessage()
}

// ── Previews (CLAUDE.md §5) ───────────────────────────────────────────────

@PreviewScreenSizes
@PreviewFontScale
@PreviewLightDark
@Composable
private fun RecoveryEmptyPreview() {
    LfTheme {
        RecoveryScreen(
            state = RecoveryUiState(
                entry = PhraseEntry(requiredWordCount = 24, draft = "aban", suggestions = listOf("abandon")),
            ),
            onEvent = {},
        )
    }
}

@PreviewScreenSizes
@PreviewFontScale
@PreviewLightDark
@Composable
private fun RecoveryPartialPreview() {
    LfTheme {
        RecoveryScreen(
            state = RecoveryUiState(
                reason = RecoveryReason.CanaryMismatch,
                entry = PhraseEntry(words = List(7) { "abandon" }, requiredWordCount = 24),
                failure = RecoveryFailure.PhraseDidNotMatch,
            ),
            onEvent = {},
        )
    }
}
