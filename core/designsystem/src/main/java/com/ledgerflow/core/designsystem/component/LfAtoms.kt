package com.ledgerflow.core.designsystem.component

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.text.KeyboardOptions
import com.ledgerflow.core.designsystem.theme.LfTheme

/**
 * The insets every LedgerFlow screen consumes: system bars and display cutout.
 *
 * **Deliberately not `safeDrawing`, because `safeDrawing` includes the IME**,
 * and the IME is handled once at the scaffold instead — see [LfScaffold].
 *
 * Two measured failures produced this split, and both are worth recording
 * because each looks like the fix for the other:
 *
 * 1. `safeDrawing` here *and* on the bottom bar: with the keyboard open the
 *    pinned bar rendered at `y = 170` and **8 pixels tall**. The keyboard was
 *    being subtracted twice, leaving no room for content at all — tapping the
 *    Note field scrolled the whole form off screen.
 * 2. Removing the IME entirely: the collapse went away and the keyboard then
 *    covered the bar and the focused field, because `enableEdgeToEdge()` makes
 *    the manifest's `adjustResize` a no-op. The window no longer shrinks, so
 *    nothing accounted for the keyboard at all.
 *
 * The keyboard therefore has exactly one consumer, and it is [LfScaffold].
 */
private val LfDrawingInsets: WindowInsets
    @Composable get() = WindowInsets.systemBars.union(WindowInsets.displayCutout)

/**
 * The app's scaffold.
 *
 * **Consumes the drawing insets and the keyboard for you.** Android 15+
 * enforces edge-to-edge, and BUG5 is what happens when each screen is trusted to
 * remember its own inset handling: content slides under the status bar on
 * exactly the devices nobody tested. Screens use this rather than Material's
 * `Scaffold` directly, so the correct behaviour is the default rather than a
 * thing to remember.
 *
 * `imePadding()` sits on the **scaffold**, not on the content and not on the
 * bottom bar. That shortens the whole screen by the keyboard's height — which
 * is what `adjustResize` would have done had `enableEdgeToEdge()` not disabled
 * it — so the content viewport shrinks and Compose scrolls the focused field
 * into view, and the pinned bar rides above the keyboard. Putting it on either
 * child instead double-counts against the other, which is the bug this fixed.
 */
@Composable
public fun LfScaffold(
    modifier: Modifier = Modifier,
    topBar: @Composable () -> Unit = {},
    bottomBar: @Composable () -> Unit = {},
    snackbarHostState: SnackbarHostState? = null,
    content: @Composable (PaddingValues) -> Unit,
) {
    val insets = LfDrawingInsets
    Scaffold(
        modifier = modifier.imePadding(),
        topBar = topBar,
        bottomBar = {
            // `contentWindowInsets` covers the *content* slot only -- Material3
            // hands a custom bottomBar the raw bottom edge and expects the bar to
            // consume its own insets. Found on device: the Recovery screen's
            // pinned "Unlock" button rendered underneath the gesture navigation
            // bar. Doing it here means no screen has to remember (BUG5).
            Box(
                Modifier.windowInsetsPadding(
                    insets.only(WindowInsetsSides.Bottom + WindowInsetsSides.Horizontal),
                ),
            ) {
                bottomBar()
            }
        },
        containerColor = LfTheme.colors.surfaceBase,
        contentColor = LfTheme.colors.textPrimary,
        contentWindowInsets = insets,
        snackbarHost = {
            snackbarHostState?.let { SnackbarHost(it) }
        },
        content = content,
    )
}

/**
 * Button emphasis. Destructive is visually distinct, never just "red text".
 *
 * [Outlined] is the row-action style: a hairline border and a raised fill, so a
 * cluster of actions inside a card reads as controls rather than as a line of
 * coloured words. [Text] stays for the cases where an action genuinely is
 * secondary chrome -- "Done", "Dismiss", "OK" next to a message.
 */
public enum class LfButtonStyle { Filled, Tonal, Outlined, Text }

@Composable
public fun LfButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    style: LfButtonStyle = LfButtonStyle.Filled,
    enabled: Boolean = true,
    loading: Boolean = false,
) {
    val spacing = LfTheme.spacing
    val colors = LfTheme.colors

    if (style == LfButtonStyle.Text) {
        TextButton(
            onClick = onClick,
            modifier = modifier.defaultMinSize(minHeight = spacing.minTouchTarget),
            enabled = enabled && !loading,
        ) {
            ButtonLabel(text = text, color = colors.accent)
        }
        return
    }

    if (style == LfButtonStyle.Outlined) {
        OutlinedRowAction(text, onClick, modifier, enabled && !loading)
        return
    }

    Button(
        onClick = onClick,
        modifier = modifier.defaultMinSize(minHeight = spacing.minTouchTarget),
        enabled = enabled && !loading,
        shape = RoundedCornerShape(spacing.cornerMedium),
        colors = when (style) {
            LfButtonStyle.Filled -> ButtonDefaults.buttonColors(
                containerColor = colors.accent,
                contentColor = colors.onAccent,
            )
            else -> ButtonDefaults.buttonColors(
                containerColor = colors.surfaceOverlay,
                contentColor = colors.textPrimary,
            )
        },
    ) {
        if (loading) {
            CircularProgressIndicator(
                modifier = Modifier
                    .defaultMinSize(minHeight = spacing.md, minWidth = spacing.md)
                    // The label already announces the action; a spinner with its
                    // own semantics would make TalkBack read it twice.
                    .clearAndSetSemantics {},
                strokeWidth = 2.dp,
                color = colors.onAccent,
            )
        } else {
            ButtonLabel(text = text, color = LocalContentColor.current)
        }
    }
}

/**
 * The row-action button: bordered, faintly filled, and never narrow.
 *
 * The minimum *width* is the point. Row actions sit in a wrapping cluster, and a
 * short label alone on the second line ("Delete", "Hide") reads as a stray tag
 * beside the two wider controls above it. `defaultMinSize` only ever grows the
 * button, so a longer label keeps its natural width and nothing is clipped
 * (BUG9).
 */
@Composable
private fun OutlinedRowAction(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier,
    enabled: Boolean,
) {
    val spacing = LfTheme.spacing
    val colors = LfTheme.colors
    OutlinedButton(
        onClick = onClick,
        modifier = modifier.defaultMinSize(
            minWidth = spacing.actionMinWidth,
            minHeight = spacing.minTouchTarget,
        ),
        enabled = enabled,
        shape = RoundedCornerShape(spacing.cornerMedium),
        border = BorderStroke(1.dp, colors.outline),
        // Material's default is 24dp a side, generous for a compact action and
        // enough on its own to push a second control off the line inside an
        // indented subcategory card. The minimum width covers the cramped case.
        contentPadding = PaddingValues(horizontal = spacing.md, vertical = spacing.sm),
        colors = ButtonDefaults.outlinedButtonColors(
            // A fill, faint but present: on the dark theme a border alone against
            // surfaceRaised is nearly invisible and the control still reads as text.
            containerColor = colors.surfaceOverlay,
            contentColor = colors.accent,
        ),
    ) {
        ButtonLabel(text = text, color = colors.accent)
    }
}

/**
 * A button's label. **Never wraps** (BUG9).
 *
 * Found on device: three text buttons in a row inside a card overflowed it, and
 * the last one broke mid-word -- "Delete" rendered as "Delet" above a lone "e".
 * A control's label is a single short phrase; breaking one across lines is never
 * the right answer, and it gets worse as the font scale grows (§9.6 requires
 * 2.0x without truncation or overlap).
 *
 * `softWrap = false` makes the button measure at its natural width, which pushes
 * the decision where it belongs: the *container* wraps whole controls onto the
 * next line (`FlowRow`), rather than the label wrapping inside a control. There
 * is deliberately no ellipsis either -- a "Delet…" button is no more usable than
 * a broken one.
 */
@Composable
private fun ButtonLabel(text: String, color: Color) {
    Text(
        text = text,
        style = LfTheme.typography.bodyL,
        color = color,
        maxLines = 1,
        softWrap = false,
    )
}

@Composable
public fun LfCard(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val spacing = LfTheme.spacing
    Box(
        modifier = modifier
            .fillMaxWidth()
            .background(
                color = LfTheme.colors.surfaceRaised,
                shape = RoundedCornerShape(spacing.cornerMedium),
            )
            .border(
                width = 1.dp,
                color = LfTheme.colors.outline,
                shape = RoundedCornerShape(spacing.cornerMedium),
            )
            .padding(spacing.md),
    ) {
        content()
    }
}

@Composable
public fun LfTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    supportingText: String? = null,
    isError: Boolean = false,
    singleLine: Boolean = true,
    keyboardOptions: KeyboardOptions = KeyboardOptions(
        // Recovery words and merchant keys are lowercase; auto-capitalising
        // them creates "errors" the user did not make.
        capitalization = KeyboardCapitalization.None,
    ),
) {
    val colors = LfTheme.colors
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier.fillMaxWidth(),
        label = { Text(text = label, style = LfTheme.typography.label) },
        supportingText = supportingText?.let {
            { Text(text = it, style = LfTheme.typography.label) }
        },
        isError = isError,
        singleLine = singleLine,
        shape = RoundedCornerShape(LfTheme.spacing.cornerSmall),
        keyboardOptions = keyboardOptions,
        textStyle = LfTheme.typography.bodyL,
        colors = OutlinedTextFieldDefaults.colors(
            focusedTextColor = colors.textPrimary,
            unfocusedTextColor = colors.textPrimary,
            focusedBorderColor = colors.accent,
            unfocusedBorderColor = colors.outline,
            errorBorderColor = colors.debit,
            focusedLabelColor = colors.accent,
            unfocusedLabelColor = colors.textSecondary,
            cursorColor = colors.accent,
        ),
    )
}
