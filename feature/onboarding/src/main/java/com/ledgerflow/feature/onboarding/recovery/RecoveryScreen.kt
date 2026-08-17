package com.ledgerflow.feature.onboarding.recovery

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
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
import com.ledgerflow.core.designsystem.component.LfChip
import com.ledgerflow.core.designsystem.component.LfChipStyle
import com.ledgerflow.core.designsystem.component.LfScaffold
import com.ledgerflow.core.designsystem.component.LfTextField
import com.ledgerflow.core.designsystem.theme.LfTheme
import com.ledgerflow.core.domain.vault.PhraseValidation
import com.ledgerflow.core.domain.vault.RecoveryReason

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
            EnteredWords(state, onEvent)

            LfTextField(
                value = state.draft,
                onValueChange = { onEvent(RecoveryEvent.DraftChanged(it)) },
                label = "Word ${(state.words.size + 1).coerceAtMost(state.requiredWordCount)}",
                isError = state.draftIsUnknown,
                supportingText = when {
                    state.draftIsUnknown -> "Not a word in the recovery list."
                    else -> "Type a word, then press space."
                },
            )

            Suggestions(state, onEvent)

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
private fun EnteredWords(state: RecoveryUiState, onEvent: (RecoveryEvent) -> Unit) {
    if (state.words.isEmpty()) return
    // A flow layout would be prettier; a column of rows is what survives a 2.0x
    // font scale without a chip being clipped mid-word (§9.6).
    Column(verticalArrangement = Arrangement.spacedBy(LfTheme.spacing.xs)) {
        state.words.chunked(WORDS_PER_ROW).forEachIndexed { rowIndex, row ->
            Row(horizontalArrangement = Arrangement.spacedBy(LfTheme.spacing.xs)) {
                row.forEachIndexed { columnIndex, word ->
                    val position = rowIndex * WORDS_PER_ROW + columnIndex
                    LfChip(
                        label = word,
                        leading = "${position + 1}",
                        style = LfChipStyle.Selected,
                        contentDescription = "Word ${position + 1}, $word. Tap to remove.",
                        onClick = { onEvent(RecoveryEvent.WordRemoved(position)) },
                    )
                }
            }
        }
    }
}

@Composable
private fun Suggestions(state: RecoveryUiState, onEvent: (RecoveryEvent) -> Unit) {
    if (state.suggestions.isEmpty()) return
    LazyRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(LfTheme.spacing.sm),
    ) {
        items(
            count = state.suggestions.size,
            key = { index -> state.suggestions[index] },
            contentType = { "suggestion" },
        ) { index ->
            val word = state.suggestions[index]
            LfChip(
                label = word,
                onClick = { onEvent(RecoveryEvent.WordCommitted(word)) },
            )
        }
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

    is RecoveryFailure.PhraseRejected -> when (val v = validation) {
        PhraseValidation.ChecksumMismatch ->
            "Every word is valid but the phrase isn't — two words are probably in " +
                "the wrong order."

        is PhraseValidation.UnknownWord ->
            "Word ${v.position} (\"${v.word}\") isn't in the recovery word list."

        is PhraseValidation.WrongWordCount ->
            "That's ${v.actual} words; a recovery phrase has ${v.expected}."

        PhraseValidation.Valid -> ""
    }
}

private const val WORDS_PER_ROW = 3

// ── Previews (CLAUDE.md §5) ───────────────────────────────────────────────

@PreviewScreenSizes
@PreviewFontScale
@PreviewLightDark
@Composable
private fun RecoveryEmptyPreview() {
    LfTheme {
        RecoveryScreen(
            state = RecoveryUiState(requiredWordCount = 24, draft = "aban", suggestions = listOf("abandon")),
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
                words = List(7) { "abandon" },
                requiredWordCount = 24,
                failure = RecoveryFailure.PhraseDidNotMatch,
            ),
            onEvent = {},
        )
    }
}
