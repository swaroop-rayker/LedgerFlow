package com.ledgerflow.core.designsystem.component

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.ledgerflow.core.designsystem.theme.LfTheme

/**
 * A compact, tappable token.
 *
 * The first consumer is the Recovery screen's word list, which is why
 * [leading] exists: a recovery word means very little without its position, and
 * "word 17" is exactly what the user is checking against their written copy.
 */
@Composable
public fun LfChip(
    label: String,
    modifier: Modifier = Modifier,
    leading: String? = null,
    style: LfChipStyle = LfChipStyle.Assist,
    contentDescription: String? = null,
    onClick: (() -> Unit)? = null,
) {
    val colors = LfTheme.colors
    val spacing = LfTheme.spacing
    val shape = RoundedCornerShape(spacing.cornerSmall)

    val container = when (style) {
        LfChipStyle.Assist -> colors.surfaceOverlay
        LfChipStyle.Selected -> colors.surfaceRaised
        LfChipStyle.Error -> colors.surfaceRaised
    }
    val outline = when (style) {
        LfChipStyle.Assist -> colors.outline
        LfChipStyle.Selected -> colors.accent
        LfChipStyle.Error -> colors.debit
    }

    Row(
        modifier = modifier
            .background(container, shape)
            .border(1.dp, outline, shape)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            // Chips are small by nature; the touch target must not be.
            .defaultMinSize(minHeight = spacing.minTouchTarget)
            .padding(horizontal = spacing.md, vertical = spacing.sm)
            .then(
                contentDescription?.let { description ->
                    Modifier.semantics { this.contentDescription = description }
                } ?: Modifier,
            ),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(spacing.sm),
    ) {
        leading?.let {
            Text(text = it, style = LfTheme.typography.label, color = colors.textTertiary)
        }
        Text(
            text = label,
            style = LfTheme.typography.bodyM,
            color = if (style == LfChipStyle.Error) colors.debit else colors.textPrimary,
        )
    }
}

/**
 * Chip role. Selected and unselected differ in outline as well as fill, so the
 * distinction survives a colour-blind viewer and a greyscale screenshot.
 *
 * Declared after the composable to match `LfAtoms.kt` and `LfDialog.kt`: detekt's
 * MatchingDeclarationName fires when a file's *first* top-level declaration is a
 * class whose name is not the filename.
 */
public enum class LfChipStyle { Assist, Selected, Error }
