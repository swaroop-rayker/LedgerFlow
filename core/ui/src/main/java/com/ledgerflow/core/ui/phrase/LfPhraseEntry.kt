package com.ledgerflow.core.ui.phrase

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
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
) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(LfTheme.spacing.lg),
    ) {
        EnteredWords(words, onWordRemove)

        LfTextField(
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

        Suggestions(suggestions, onSuggestionTap)
    }
}

@Composable
private fun EnteredWords(words: List<String>, onWordRemove: (Int) -> Unit) {
    if (words.isEmpty()) return
    // A flow layout would be prettier; a column of rows is what survives a 2.0x
    // font scale without a chip being clipped mid-word (§9.6).
    Column(verticalArrangement = Arrangement.spacedBy(LfTheme.spacing.xs)) {
        words.chunked(WORDS_PER_ROW).forEachIndexed { rowIndex, row ->
            Row(horizontalArrangement = Arrangement.spacedBy(LfTheme.spacing.xs)) {
                row.forEachIndexed { columnIndex, word ->
                    val position = rowIndex * WORDS_PER_ROW + columnIndex
                    LfChip(
                        label = word,
                        leading = "${position + 1}",
                        style = LfChipStyle.Selected,
                        contentDescription = "Word ${position + 1}, $word. Tap to remove.",
                        onClick = { onWordRemove(position) },
                    )
                }
            }
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
            LfChip(label = word, onClick = { onSuggestionTap(word) })
        }
    }
}

private const val WORDS_PER_ROW = 3
