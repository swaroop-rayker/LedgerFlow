package com.ledgerflow.core.designsystem.component

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.window.DialogProperties
import com.ledgerflow.core.designsystem.theme.LfTheme

/**
 * A confirmation dialog.
 *
 * [emphasis] exists for one specific job: the Recovery Kit confirmation (D-07)
 * has to read as a genuine warning, because the user is about to write the
 * master key for every backup they will ever make to shared storage. A dialog
 * that looks like every other dialog gets dismissed by muscle memory, and that
 * decision deserves a beat of attention. It is not decoration.
 */
@Composable
public fun LfDialog(
    title: String,
    body: String,
    confirmText: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    dismissText: String? = "Cancel",
    emphasis: LfDialogEmphasis = LfDialogEmphasis.Normal,
    detail: (@Composable () -> Unit)? = null,
) {
    val colors = LfTheme.colors
    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = modifier,
        // Not dismissable by an outside tap when it is a warning: the whole
        // point is that the choice is made deliberately.
        properties = DialogProperties(
            dismissOnClickOutside = emphasis == LfDialogEmphasis.Normal,
        ),
        shape = RoundedCornerShape(LfTheme.spacing.cornerLarge),
        containerColor = colors.surfaceOverlay,
        title = {
            Text(
                text = title,
                style = LfTheme.typography.titleM,
                color = when (emphasis) {
                    LfDialogEmphasis.Normal -> colors.textPrimary
                    LfDialogEmphasis.Warning -> colors.warn
                },
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(LfTheme.spacing.md)) {
                Text(
                    text = body,
                    style = LfTheme.typography.bodyM,
                    color = colors.textSecondary,
                )
                detail?.invoke()
            }
        },
        confirmButton = {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(LfTheme.spacing.sm),
            ) {
                dismissText?.let {
                    LfButton(text = it, onClick = onDismiss, style = LfButtonStyle.Text)
                }
                LfButton(
                    text = confirmText,
                    onClick = onConfirm,
                    style = when (emphasis) {
                        LfDialogEmphasis.Normal -> LfButtonStyle.Filled
                        LfDialogEmphasis.Warning -> LfButtonStyle.Tonal
                    },
                )
            }
        },
    )
}

public enum class LfDialogEmphasis { Normal, Warning }
