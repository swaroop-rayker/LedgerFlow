package com.ledgerflow.core.designsystem.chart

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Dp
import com.ledgerflow.core.designsystem.theme.LfTheme
import com.ledgerflow.core.designsystem.theme.primitive.LfScale

/*
 * Chart tokens (DESIGN_SYSTEM_PLAN §4.1, step B2): what the `Lf*` Canvas
 * primitives draw with, named by the part of a chart they paint.
 *
 * Same rules as the component tokens beside `LfComponentTokens`: colours and
 * text from the semantic roles, a chart's own spacing from the primitive scale,
 * radii from `LfSpacing` until step B3. `internal`, so a screen picks a chart,
 * never its colours.
 *
 * Step B2 moved every chart onto these with today's values; every golden is
 * byte-identical before and after.
 */
internal object LfChartTokens {
    /** Between a chart's stacked parts: header and bar, one bar and the next. */
    val gap: Dp = LfScale.Space4
    /** Between a row's label and its figures, and a bar's inner side padding. */
    val wideGap: Dp = LfScale.Space8
    /** Keeps an ellipsised label from running into the amount after it. */
    val figureInset: Dp = LfScale.Space16
    val cellCorner: Dp @Composable get() = LfTheme.spacing.cornerSmall

    val labelStyle: TextStyle @Composable get() = LfTheme.type.label
    val textStyle: TextStyle @Composable get() = LfTheme.type.bodySmall
    val amountStyle: TextStyle @Composable get() = LfTheme.type.amountRow
    val text: Color @Composable get() = LfTheme.colors.text.primary
    val secondaryText: Color @Composable get() = LfTheme.colors.text.secondary
    val axisLabel: Color @Composable get() = LfTheme.colors.chart.axisLabel

    val grid: Color @Composable get() = LfTheme.colors.chart.grid
    val track: Color @Composable get() = LfTheme.colors.chart.track
    /** The donut's empty ring. A border tone, not the bar track: it is thinner. */
    val donutTrack: Color @Composable get() = LfTheme.colors.border.subtle
    /** The mirrored chart's zero line and guides sit on the raised surface. */
    val mirroredGrid: Color @Composable get() = LfTheme.colors.surface.overlay
    /** Treemap gutters: the page showing through between tiles. */
    val gutter: Color @Composable get() = LfTheme.colors.surface.canvas
    /** A projection or reference mark, quieter than the data. */
    val reference: Color @Composable get() = LfTheme.colors.text.muted
    /** A category with no colour of its own. */
    val uncategorised: Color @Composable get() = LfTheme.colors.text.muted

    /** A single-series chart's ink when the caller gives none. */
    val series: Color @Composable get() = LfTheme.colors.action.primary
    val outgoing: Color @Composable get() = LfTheme.colors.money.outgoing
    val incoming: Color @Composable get() = LfTheme.colors.money.incoming
    val overBudget: Color @Composable get() = LfTheme.colors.status.warning
}
