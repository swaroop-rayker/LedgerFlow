package com.ledgerflow.core.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Dp
import com.ledgerflow.core.designsystem.theme.LfLayout
import com.ledgerflow.core.designsystem.theme.LfTheme

/*
 * Component tokens for the shared rows and dialogs in `:core:ui`
 * (DESIGN_SYSTEM_PLAN §4.1, step B2).
 *
 * Same idea as `LfComponentTokens` in `:core:designsystem`, one module up: this
 * module cannot see the primitive scale, so every value here reads a public
 * semantic role. `internal`, so a feature screen uses the row, never its tokens.
 *
 * Step B2 moved these components onto the tokens with today's values; every
 * golden is byte-identical before and after.
 */

internal object LfEntryRowTokens {
    val shape: Shape @Composable get() = LfTheme.shapes.row
    val container: Color @Composable get() = LfTheme.colors.surface.raised
    val border: Color @Composable get() = LfTheme.colors.border.subtle
    val borderWidth: Dp @Composable get() = LfTheme.borders.hairline

    val insetStart: Dp @Composable get() = LfTheme.space.rowInset
    val insetEnd: Dp @Composable get() = LfTheme.space.rowInset
    /**
     * The trailing inset when the delete control is there: its 48 dp target
     * already carries about 16 dp around the glyph.
     */
    val insetEndBesideAction: Dp @Composable get() = LfTheme.space.inlineGap
    val insetVertical: Dp @Composable get() = LfTheme.space.rowInsetVertical
    /** Between the swatch, the texts and the amount. */
    val gap: Dp @Composable get() = LfTheme.space.itemGap

    /** The category's tinted tile (step F3; it was the 24 dp `LfCategoryDot`). */
    val swatchSize: Dp = LfLayout.categoryTileSize
    val swatchShape: Shape @Composable get() = LfTheme.shapes.tile
    val emptySwatchRing: Color @Composable get() = LfTheme.colors.border.subtle

    val titleStyle: TextStyle @Composable get() = LfTheme.type.body
    val title: Color @Composable get() = LfTheme.colors.text.primary
    val detailStyle: TextStyle @Composable get() = LfTheme.type.caption
    val detail: Color @Composable get() = LfTheme.colors.text.muted
    val amountStyle: TextStyle @Composable get() = LfTheme.type.amountRow
    val outgoing: Color @Composable get() = LfTheme.colors.money.outgoing
    val incoming: Color @Composable get() = LfTheme.colors.money.incoming
}

/** The pickers' option rows, the multi-picker and the line-item editor. */
internal object LfFormRowTokens {
    val minHeight: Dp = LfLayout.minTouchTarget
    val paddingVertical: Dp @Composable get() = LfTheme.space.rowInsetVertical
    val compactPaddingVertical: Dp @Composable get() = LfTheme.space.inlineGap
    val gap: Dp @Composable get() = LfTheme.space.itemGap
    val tightGap: Dp @Composable get() = LfTheme.space.inlineGap
    /** Between a row's label and its value. */
    val labelGap: Dp @Composable get() = LfTheme.space.rowInset

    val textStyle: TextStyle @Composable get() = LfTheme.type.body
    val supportingStyle: TextStyle @Composable get() = LfTheme.type.bodySmall
    val captionStyle: TextStyle @Composable get() = LfTheme.type.label
    val text: Color @Composable get() = LfTheme.colors.text.primary
    val secondary: Color @Composable get() = LfTheme.colors.text.secondary
    val muted: Color @Composable get() = LfTheme.colors.text.muted
    val selected: Color @Composable get() = LfTheme.colors.action.primary
    val warning: Color @Composable get() = LfTheme.colors.status.warning
}

/** Recovery-phrase entry and the phrase scanner. */
internal object LfPhraseTokens {
    /** Between the entry's blocks. */
    val sectionGap: Dp @Composable get() = LfTheme.space.blockGap
    val wordGap: Dp @Composable get() = LfTheme.space.inlineGap
    val rowGap: Dp @Composable get() = LfTheme.space.itemGap
    val scannerInset: Dp @Composable get() = LfTheme.space.itemGap
    val textStyle: TextStyle @Composable get() = LfTheme.type.bodySmall
    val text: Color @Composable get() = LfTheme.colors.text.primary
}
