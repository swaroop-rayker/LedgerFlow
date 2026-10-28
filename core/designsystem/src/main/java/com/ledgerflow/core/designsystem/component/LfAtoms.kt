package com.ledgerflow.core.designsystem.component

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.error
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType

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
 *
 * **Shows the window's [LfFeedback]** (step E1): the outermost scaffold makes
 * one when nothing above provides it, every scaffold inside shares it, and the
 * innermost draws it, so a message appears once, above that screen's own
 * pinned bar. A scaffold given its own [snackbarHostState] shows that instead
 * and leaves the shared one to the scaffold around it.
 */
@Composable
public fun LfScaffold(
    modifier: Modifier = Modifier,
    topBar: @Composable () -> Unit = {},
    bottomBar: @Composable () -> Unit = {},
    snackbarHostState: SnackbarHostState? = null,
    content: @Composable (PaddingValues) -> Unit,
) {
    val parentFeedback = LocalLfFeedback.current
    val ownFeedback = remember { LfFeedback() }
    // Only the scaffold that made the feedback gives it a scope: the outermost,
    // which outlives every screen inside it.
    if (parentFeedback == null) ownFeedback.ownScope()
    val feedback = parentFeedback ?: ownFeedback
    val claim = remember { Any() }
    if (snackbarHostState == null) {
        DisposableEffect(feedback) {
            feedback.claims += claim
            onDispose { feedback.claims -= claim }
        }
    }
    CompositionLocalProvider(LocalLfFeedback provides feedback) {
        ScaffoldBody(modifier, topBar, bottomBar, snackbarHostState, feedback, claim, content)
    }
}

@Composable
private fun ScaffoldBody(
    modifier: Modifier,
    topBar: @Composable () -> Unit,
    bottomBar: @Composable () -> Unit,
    snackbarHostState: SnackbarHostState?,
    feedback: LfFeedback,
    claim: Any,
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
        containerColor = LfScaffoldTokens.container,
        contentColor = LfScaffoldTokens.content,
        contentWindowInsets = insets,
        snackbarHost = {
            when {
                snackbarHostState != null -> LfSnackbarHost(snackbarHostState)
                feedback.claims.lastOrNull() === claim -> LfSnackbarHost(feedback.hostState)
            }
        },
        content = { padding ->
            // BUG49: mark what this scaffold paid for as consumed, so a scaffold
            // nested inside -- every full-screen destination sits inside the
            // shell's -- does not pay for the same system bars again. The caller
            // still applies `padding` itself, as before; this only tells the
            // descendants it has been applied.
            Box(Modifier.fillMaxSize().consumeWindowInsets(padding)) {
                content(padding)
            }
        },
    )
}

/**
 * Button emphasis. Destructive is visually distinct, never just "red text".
 *
 * [Outlined] is the row-action style: a hairline border and a raised fill, so a
 * cluster of actions inside a card reads as controls rather than as a line of
 * coloured words. [Text] stays for the cases where an action genuinely is
 * secondary chrome -- "Done", "Dismiss", "OK" next to a message.
 *
 * [Inline] is [Text] at label size, for two or three actions that have to share
 * one line inside a narrow card. Three `bodyL` labels need roughly 300dp and a
 * category card offers about 280, so they wrapped to a third line and made the
 * card taller than the content in it. The touch target stays 48dp -- what
 * shrinks is the type and the horizontal padding, never the tappable area
 * (§9.6).
 */
public enum class LfButtonStyle { Filled, Tonal, Outlined, Text, Inline }

@Composable
public fun LfButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    style: LfButtonStyle = LfButtonStyle.Filled,
    enabled: Boolean = true,
    loading: Boolean = false,
) {
    val tokens = LfButtonTokens

    if (style == LfButtonStyle.Text || style == LfButtonStyle.Inline) {
        val inline = style == LfButtonStyle.Inline
        TextButton(
            onClick = onClick,
            modifier = modifier.defaultMinSize(minHeight = tokens.minHeight),
            enabled = enabled && !loading,
            contentPadding = if (inline) {
                PaddingValues(horizontal = tokens.inlinePaddingHorizontal, vertical = tokens.inlinePaddingVertical)
            } else {
                ButtonDefaults.TextButtonContentPadding
            },
        ) {
            ButtonLabel(
                text = text,
                // Dimmed when it cannot be tapped. The label was `accent`
                // unconditionally, so a disabled text action rendered in full
                // clickable blue -- inert, but indistinguishable from one that
                // works. Found on the bin's "Erase", which sits beside a
                // correctly-greyed filled button and looked available with
                // nothing selected.
                color = if (enabled && !loading) tokens.textLabel else tokens.disabledLabel,
                style = if (inline) tokens.inlineLabelStyle else tokens.labelStyle,
            )
        }
        return
    }

    if (style == LfButtonStyle.Outlined) {
        OutlinedRowAction(text, onClick, modifier, enabled && !loading)
        return
    }

    Button(
        onClick = onClick,
        modifier = modifier.defaultMinSize(minHeight = tokens.mainMinHeight),
        enabled = enabled && !loading,
        shape = tokens.shape,
        colors = when (style) {
            LfButtonStyle.Filled -> ButtonDefaults.buttonColors(
                containerColor = tokens.filledContainer,
                contentColor = tokens.filledContent,
            )
            else -> ButtonDefaults.buttonColors(
                containerColor = tokens.tonalContainer,
                contentColor = tokens.tonalContent,
            )
        },
    ) {
        if (loading) {
            CircularProgressIndicator(
                modifier = Modifier
                    .defaultMinSize(minHeight = tokens.spinnerSize, minWidth = tokens.spinnerSize)
                    // The label already announces the action; a spinner with its
                    // own semantics would make TalkBack read it twice.
                    .clearAndSetSemantics {},
                strokeWidth = tokens.spinnerStroke,
                color = tokens.spinnerColor,
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
    val tokens = LfButtonTokens
    OutlinedButton(
        onClick = onClick,
        modifier = modifier.defaultMinSize(
            minWidth = tokens.outlinedMinWidth,
            minHeight = tokens.outlinedMinHeight,
        ),
        enabled = enabled,
        shape = tokens.shape,
        border = BorderStroke(tokens.outlinedBorderWidth, tokens.outlinedBorder),
        // Material's default is 24dp a side, generous for a compact action and
        // enough on its own to push a second control off the line inside an
        // indented subcategory card. The minimum width covers the cramped case.
        contentPadding = PaddingValues(
            horizontal = tokens.outlinedPaddingHorizontal,
            vertical = tokens.outlinedPaddingVertical,
        ),
        colors = ButtonDefaults.outlinedButtonColors(
            // A fill, faint but present: on the dark theme a border alone against
            // surfaceRaised is nearly invisible and the control still reads as text.
            containerColor = tokens.outlinedContainer,
            contentColor = tokens.outlinedContent,
        ),
    ) {
        ButtonLabel(text = text, color = tokens.outlinedContent)
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
 *
 * **It says so to intrinsic measurement too.** Compose reports a text's minimum
 * intrinsic width as its longest word, whatever `softWrap` says, so a layout
 * asking how narrow a control can go heard "Move" for "Move back". At the 24 dp
 * screen edge (design-system step D1) the Inbox gave that label 124 dp of the
 * 127 it needs at font scale 2.0. `IntrinsicSize.Max` makes the minimum the
 * whole label; it changes no layout, since the label already measured at its
 * natural width.
 */
@Composable
private fun ButtonLabel(
    text: String,
    color: Color,
    style: androidx.compose.ui.text.TextStyle = LfButtonTokens.labelStyle,
) {
    Text(
        text = text,
        style = style,
        color = color,
        maxLines = 1,
        softWrap = false,
        modifier = Modifier.width(IntrinsicSize.Max),
    )
}

@Composable
public fun LfCard(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val tokens = LfCardTokens
    val shape = tokens.shape
    Box(
        modifier = modifier
            .fillMaxWidth()
            .background(color = tokens.container, shape = shape)
            .border(width = tokens.borderWidth, color = tokens.border, shape = shape)
            .padding(tokens.inset),
    ) {
        content()
    }
}

/**
 * Keyboard configurations with a job beyond layout.
 *
 * Named here rather than built inline at each call site, because the one that
 * matters is a security property: a screen that forgets it looks and behaves
 * identically, and only the keyboard's learned dictionary knows the difference.
 */
public object LfKeyboards {

    /**
     * **For any field that receives a recovery-phrase word** — onboarding's
     * word challenge, the Recovery screen, and "Back up now".
     *
     * The 24 words are the only thing that protects a `.lfbk`, and a
     * keyboard's personal dictionary is a store outside this app: Gboard and
     * Samsung Keyboard learn typed words and, with sync on, carry them off the
     * device. So the field is declared a **password** input, which those
     * keyboards neither learn from nor offer suggestions into, with
     * autocorrect off so a real word cannot be "corrected" into another one.
     *
     * The characters stay visible — there is no masking transformation — so a
     * user can see what they typed; the app's own suggestion strip, from the
     * BIP-39 list, replaces the keyboard's.
     *
     * **A limit, stated.** Android's explicit incognito signal,
     * `EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING`, is not reachable through
     * Compose's `KeyboardOptions`, so this relies on keyboards honouring the
     * password variation — which the mainstream ones do, and which is the
     * convention every password manager depends on. A keyboard that ignores
     * it is outside what the app can control.
     */
    public val RecoveryWord: KeyboardOptions = KeyboardOptions(
        capitalization = KeyboardCapitalization.None,
        autoCorrectEnabled = false,
        keyboardType = KeyboardType.Password,
    )
}

/**
 * A text field. [error], when set, is the message **directly under the field**
 * (DESIGN_SYSTEM_PLAN §5.3, step E1), in place of [supportingText] and in the
 * danger colour, and screen readers hear it as the field's error. Check when
 * the user taps the action or leaves the field, not on every keystroke.
 * [isError] alone marks the field without a message, as before.
 */
@Composable
public fun LfTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    supportingText: String? = null,
    isError: Boolean = false,
    error: String? = null,
    singleLine: Boolean = true,
    keyboardOptions: KeyboardOptions = KeyboardOptions(
        // Recovery words and merchant keys are lowercase; auto-capitalising
        // them creates "errors" the user did not make.
        capitalization = KeyboardCapitalization.None,
    ),
) {
    val tokens = LfTextFieldTokens
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier
            .fillMaxWidth()
            .semantics { error?.let { error(it) } },
        label = { Text(text = label, style = tokens.labelStyle) },
        supportingText = (error ?: supportingText)?.let {
            { Text(text = it, style = tokens.labelStyle) }
        },
        isError = isError || error != null,
        singleLine = singleLine,
        shape = tokens.shape,
        keyboardOptions = keyboardOptions,
        textStyle = tokens.textStyle,
        colors = OutlinedTextFieldDefaults.colors(
            focusedTextColor = tokens.text,
            unfocusedTextColor = tokens.text,
            focusedBorderColor = tokens.focusedBorder,
            unfocusedBorderColor = tokens.unfocusedBorder,
            errorBorderColor = tokens.errorBorder,
            errorSupportingTextColor = tokens.errorText,
            errorLabelColor = tokens.errorText,
            errorCursorColor = tokens.cursor,
            errorTextColor = tokens.text,
            focusedLabelColor = tokens.focusedLabel,
            unfocusedLabelColor = tokens.unfocusedLabel,
            cursorColor = tokens.cursor,
        ),
    )
}
