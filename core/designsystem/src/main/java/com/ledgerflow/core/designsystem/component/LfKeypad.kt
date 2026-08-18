package com.ledgerflow.core.designsystem.component

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import com.ledgerflow.core.designsystem.theme.LfTheme

/**
 * The amount keypad (SPEC.md §9.4) — large, thumb-reachable, on-screen.
 *
 * Not the IME. §5.4 targets four taps for a repeat expense, and the system
 * keyboard costs an animation, a focus dance, and roughly half the screen
 * before the first digit lands. It also cannot be trusted to offer digits: a
 * numeric IME on some OEM keyboards still shows a full QWERTY.
 *
 * Digits arrive as strings and are appended right-to-left in **minor units** by
 * the caller: "1", "12", "125" mean 0.01, 0.12, 1.25. That is what keeps the
 * amount a `Long` from the first keystroke — there is never a half-typed
 * decimal string for something to parse into a `Double` (Law 3). It is also why
 * there is no decimal-point key: the point is a rendering detail, and a keypad
 * that offered one would invite "1.2.5".
 *
 * `00` earns its place on an India-first product: round hundreds and thousands
 * are the common case, and ₹500 costs three taps instead of five.
 */
@Composable
public fun LfKeypad(
    onDigits: (String) -> Unit,
    onBackspace: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val spacing = LfTheme.spacing

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(spacing.sm),
    ) {
        KEY_ROWS.forEach { row ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(spacing.sm),
            ) {
                row.forEach { key ->
                    Key(
                        label = key,
                        modifier = Modifier.weight(1f),
                        onClick = { if (key == BACKSPACE) onBackspace() else onDigits(key) },
                    )
                }
            }
        }
    }
}

@Composable
private fun Key(label: String, modifier: Modifier, onClick: () -> Unit) {
    val colors = LfTheme.colors
    val spacing = LfTheme.spacing
    val isBackspace = label == BACKSPACE

    Box(
        modifier = modifier
            .background(colors.surfaceRaised, RoundedCornerShape(spacing.cornerMedium))
            .clickable(onClick = onClick)
            // A key that is 48dp tall at font scale 1.0 is still 48dp at 2.0,
            // which is the minimum §9.6 requires and the floor a thumb needs.
            // The vertical padding is what actually grows the key with the text.
            .defaultMinSize(minHeight = spacing.keypadKeyHeight)
            .padding(vertical = spacing.sm)
            .semantics {
                contentDescription = if (isBackspace) BACKSPACE_DESCRIPTION else label
            },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            style = LfTheme.typography.titleL,
            color = if (isBackspace) colors.textSecondary else colors.textPrimary,
            textAlign = TextAlign.Center,
            // BUG9's contract. A key is the narrowest control in the app and
            // "00" breaking into "0 / 0" would be both wrong and comical.
            maxLines = 1,
            softWrap = false,
        )
    }
}

/** `⌫` renders as a glyph; TalkBack gets a sentence instead. */
private const val BACKSPACE = "⌫"
private const val BACKSPACE_DESCRIPTION = "Delete last digit"

private val KEY_ROWS: List<List<String>> = listOf(
    listOf("1", "2", "3"),
    listOf("4", "5", "6"),
    listOf("7", "8", "9"),
    listOf("00", "0", BACKSPACE),
)
