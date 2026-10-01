package com.ledgerflow.core.ui.phrase

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.ledgerflow.core.designsystem.component.LfActionAlignment
import com.ledgerflow.core.designsystem.component.LfActionRow
import com.ledgerflow.core.designsystem.component.LfButton
import com.ledgerflow.core.designsystem.component.LfButtonStyle
import com.ledgerflow.core.designsystem.component.LfChip
import com.ledgerflow.core.designsystem.component.LfChipStyle
import com.ledgerflow.core.designsystem.component.LfKeyboards
import com.ledgerflow.core.designsystem.component.LfTextField
import com.ledgerflow.core.designsystem.theme.LfTheme

/**
 * Typing the 24 recovery words: the committed words, the field, and the
 * suggestion strip.
 *
 * Shared by the Recovery screen and "Back up now" (§16 Q23), so the phrase is
 * entered the same way everywhere it is entered — including the one property
 * nobody can see: the field uses [LfKeyboards.RecoveryWord], so the keyboard
 * never learns the words (BUG25). A screen that built its own field would
 * render identically and lose that.
 *
 * Takes resolved values rather than a domain type, per this module's rule
 * (see its build file): the host owns the entry state and its rules, and this
 * only draws it and reports taps.
 *
 * @param onDraftChange every keystroke, raw. The host decides what a space means.
 * @param onSuggestionTap a word from the strip, to commit as the next word.
 * @param onWordRemove a committed word's 0-based position, tapped to remove it.
 */
@Composable
public fun LfPhraseEntry(
    words: List<String>,
    draft: String,
    suggestions: List<String>,
    draftIsUnknown: Boolean,
    requiredWordCount: Int,
    onDraftChange: (String) -> Unit,
    onSuggestionTap: (String) -> Unit,
    onWordRemove: (Int) -> Unit,
    modifier: Modifier = Modifier,
    /**
     * Offers "Scan the Recovery Kit" above the field (ADR-0028). Null on a
     * screen with no scanner; typing is always available either way, and the
     * scan is deliberately the *secondary* affordance — a screen reader cannot
     * aim a camera.
     */
    onScanRequested: (() -> Unit)? = null,
) {
    Column(
        modifier = modifier.phraseSecret(),
        verticalArrangement = Arrangement.spacedBy(LfTheme.spacing.lg),
    ) {
        EnteredWords(words, onWordRemove)

        LfTextField(
            modifier = Modifier.phraseSecret(),
            value = draft,
            onValueChange = onDraftChange,
            label = "Word ${(words.size + 1).coerceAtMost(requiredWordCount)}",
            isError = draftIsUnknown,
            supportingText = when {
                draftIsUnknown -> "Not a word in the recovery list."
                else -> "Type a word, then press space."
            },
            keyboardOptions = LfKeyboards.RecoveryWord,
        )

        if (onScanRequested != null) {
            LfActionRow(alignment = LfActionAlignment.Start) {
                LfButton(
                    text = "Scan the Recovery Kit",
                    onClick = onScanRequested,
                    style = LfButtonStyle.Inline,
                )
            }
        }

        Suggestions(suggestions, onSuggestionTap)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun EnteredWords(words: List<String>, onWordRemove: (Int) -> Unit) {
    if (words.isEmpty()) return
    // A flow of whole chips (BUG42). This was fixed rows of three, on the
    // belief that rows survive font scale 2.0 better; at phone width the third
    // chip of a row was given zero width and the word vanished. A chip's label
    // never wraps (BUG9), so the flow moves whole chips to the next line and
    // none is clipped -- in reading order, so "Word 4" still follows "Word 3".
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(LfTheme.spacing.xs),
        verticalArrangement = Arrangement.spacedBy(LfTheme.spacing.xs),
    ) {
        words.forEachIndexed { position, word ->
            LfChip(
                label = word,
                leading = "${position + 1}",
                style = LfChipStyle.Selected,
                contentDescription = "Word ${position + 1}, $word. Tap to remove.",
                onClick = { onWordRemove(position) },
                modifier = Modifier.phraseSecret(),
            )
        }
    }
}

@Composable
private fun Suggestions(suggestions: List<String>, onSuggestionTap: (String) -> Unit) {
    if (suggestions.isEmpty()) return
    LazyRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(LfTheme.spacing.sm),
    ) {
        items(
            count = suggestions.size,
            key = { index -> suggestions[index] },
            contentType = { "suggestion" },
        ) { index ->
            val word = suggestions[index]
            LfChip(label = word, onClick = { onSuggestionTap(word) }, modifier = Modifier.phraseSecret())
        }
    }
}

