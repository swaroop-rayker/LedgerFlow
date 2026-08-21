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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.ledgerflow.core.designsystem.theme.LfColors
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

    val palette = paletteFor(style, colors)

    Row(
        modifier = modifier
            .background(palette.container, shape)
            .border(1.dp, palette.outline, shape)
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
            color = palette.content,
        )
    }
}

/**
 * A style's three colours, resolved together.
 *
 * One `when` rather than three. The colours were read by three parallel `when`
 * blocks over the same enum, which is four styles x three reads = twelve
 * branches in one composable -- enough to trip detekt's complexity threshold the
 * moment a fourth style arrived, and, more to the point, enough that adding a
 * fifth style meant remembering three separate places. A style is one decision;
 * this makes it one branch.
 */
private data class ChipPalette(
    val container: Color,
    val outline: Color,
    val content: Color,
)

private fun paletteFor(style: LfChipStyle, colors: LfColors): ChipPalette = when (style) {
    LfChipStyle.Assist -> ChipPalette(colors.surfaceOverlay, colors.outline, colors.textPrimary)
    LfChipStyle.Selected -> ChipPalette(colors.surfaceRaised, colors.accent, colors.textPrimary)
    LfChipStyle.Error -> ChipPalette(colors.surfaceRaised, colors.debit, colors.debit)
    LfChipStyle.Warning -> ChipPalette(colors.surfaceRaised, colors.warn, colors.warn)
}

/**
 * Chip role. Selected and unselected differ in outline as well as fill, so the
 * distinction survives a colour-blind viewer and a greyscale screenshot.
 *
 * [Warning] is not [Error] in a different colour. Error says something went
 * wrong; Warning says something is true and worth knowing before you act on it
 * -- the export's "Not encrypted" being the case it was added for. Rendering
 * that in `debit` red would make a screen where nothing has failed look like a
 * screen where something has, which is how people learn to ignore red.
 *
 * Declared after the composable to match `LfAtoms.kt` and `LfDialog.kt`: detekt's
 * MatchingDeclarationName fires when a file's *first* top-level declaration is a
 * class whose name is not the filename.
 */
public enum class LfChipStyle { Assist, Selected, Error, Warning }
