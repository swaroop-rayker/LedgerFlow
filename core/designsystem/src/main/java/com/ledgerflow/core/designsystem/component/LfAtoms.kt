package com.ledgerflow.core.designsystem.component

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.text.KeyboardOptions
import com.ledgerflow.core.designsystem.theme.LfTheme

/**
 * The app's scaffold.
 *
 * **Consumes `WindowInsets.safeDrawing` for you.** Android 15+ enforces
 * edge-to-edge, and BUG5 is what happens when each screen is trusted to
 * remember its own inset handling: content slides under the status bar on
 * exactly the devices nobody tested. Screens use this rather than Material's
 * `Scaffold` directly, so the correct behaviour is the default rather than a
 * thing to remember.
 */
@Composable
public fun LfScaffold(
    modifier: Modifier = Modifier,
    topBar: @Composable () -> Unit = {},
    bottomBar: @Composable () -> Unit = {},
    snackbarHostState: SnackbarHostState? = null,
    content: @Composable (PaddingValues) -> Unit,
) {
    Scaffold(
        modifier = modifier,
        topBar = topBar,
        bottomBar = {
            // `contentWindowInsets` covers the *content* slot only -- Material3
            // hands a custom bottomBar the raw bottom edge and expects the bar to
            // consume its own insets. Found on device: the Recovery screen's
            // pinned "Unlock" button rendered underneath the gesture navigation
            // bar. Doing it here means no screen has to remember (BUG5).
            Box(
                Modifier.windowInsetsPadding(
                    WindowInsets.safeDrawing.only(
                        WindowInsetsSides.Bottom + WindowInsetsSides.Horizontal,
                    ),
                ),
            ) {
                bottomBar()
            }
        },
        containerColor = LfTheme.colors.surfaceBase,
        contentColor = LfTheme.colors.textPrimary,
        contentWindowInsets = WindowInsets.safeDrawing,
        snackbarHost = {
            snackbarHostState?.let { SnackbarHost(it) }
        },
        content = content,
    )
}

/** Button emphasis. Destructive is visually distinct, never just "red text". */
public enum class LfButtonStyle { Filled, Tonal, Text }

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
            Text(text = text, style = LfTheme.typography.bodyL, color = colors.accent)
        }
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
            Text(text = text, style = LfTheme.typography.bodyL)
        }
    }
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
