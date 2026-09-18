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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.PreviewFontScale
import androidx.compose.ui.tooling.preview.PreviewLightDark
import androidx.compose.ui.tooling.preview.PreviewScreenSizes
import com.ledgerflow.core.designsystem.component.LfButton
import com.ledgerflow.core.designsystem.component.LfButtonStyle
import com.ledgerflow.core.designsystem.component.LfCard
import com.ledgerflow.core.designsystem.component.LfScaffold
import com.ledgerflow.core.designsystem.theme.LfTheme
import com.ledgerflow.core.domain.vault.PhraseEntry
import com.ledgerflow.core.domain.vault.RecoveryReason
import com.ledgerflow.core.ui.phrase.LfPhraseEntry
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
    LfScaffold(
        modifier = modifier,
        bottomBar = { SubmitBar(state, onEvent) },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = LfTheme.spacing.lg)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(LfTheme.spacing.lg),
        ) {
            Header(state)

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
            )

            state.failure?.let { FailureMessage(it) }
        }
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
    Column(
        modifier = Modifier.fillMaxWidth().padding(LfTheme.spacing.lg),
        verticalArrangement = Arrangement.spacedBy(LfTheme.spacing.sm),
    ) {
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
private fun Header(state: RecoveryUiState) {
    Column(verticalArrangement = Arrangement.spacedBy(LfTheme.spacing.sm)) {
        Text(
            text = "Enter your recovery phrase",
            style = LfTheme.typography.displayL,
            color = LfTheme.colors.textPrimary,
        )
        Text(
            text = state.reason.explanation(),
            style = LfTheme.typography.bodyL,
            color = LfTheme.colors.textSecondary,
        )
    }
    // Reassurance before the work, not after it. This screen is the one place a
    // user reasonably fears their data is gone; saying plainly that it is not is
    // the most useful sentence on the page.
    LfCard {
        Text(
            text = "Your data is still here and still encrypted. These 24 words unlock " +
                "it. Nothing is deleted by getting this wrong.",
            style = LfTheme.typography.bodyM,
            color = LfTheme.colors.textSecondary,
        )
    }
}

@Composable
private fun FailureMessage(failure: RecoveryFailure) {
    Text(
        text = failure.message(),
        style = LfTheme.typography.bodyM,
        color = LfTheme.colors.debit,
        textAlign = TextAlign.Start,
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
