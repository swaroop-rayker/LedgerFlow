package com.ledgerflow.core.designsystem.chart

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.material3.Text
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.ledgerflow.core.designsystem.theme.LfTheme

/**
 * Merchant leaderboard (`SPEC.md` §5.6, A4).
 *
 * **Barely a chart, and that is the design.** It is a list of rows, each with a
 * fill behind the label proportional to its share of the largest row. There is
 * no axis, no gridline and no legend, because none of them would tell the
 * reader anything the number at the end of the row does not — and every one of
 * them would cost vertical space in a list whose whole purpose is to be
 * scanned.
 *
 * **The fill sits behind the text rather than beside it.** A bar in its own
 * column has to be given a width, which at font scale 2.0 is width taken from
 * the label — and a truncated merchant name is exactly the defect BUG9 is about.
 * Behind the text, the bar has no width of its own to negotiate and the label
 * gets the whole row.
 *
 * **Proportional to the maximum, not to the total.** Top-N + "Other" (§5.6)
 * means the visible rows do not sum to the whole, so a share-of-total fill would
 * render every bar misleadingly short. Share-of-largest is the honest reading of
 * a ranked list, and the figures beside them carry the absolute truth.
 */
@Composable
public fun LfHorizontalBarChart(
    data: List<LfBarDatum>,
    modifier: Modifier = Modifier,
    rowHeight: Dp = DefaultRowHeight,
) {
    val max = data.maxOfOrNull { it.value }?.coerceAtLeast(1L) ?: 1L
    val track = LfTheme.colors.surfaceOverlay

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(LfTheme.spacing.xs),
    ) {
        data.forEach { datum ->
            val fraction = (datum.value.toFloat() / max.toFloat()).coerceIn(0f, 1f)
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(rowHeight)
                    .clip(RoundedCornerShape(LfTheme.spacing.cornerSmall))
                    .background(track)
                    .semantics {
                        contentDescription = "${datum.label}, ${datum.formattedValue}"
                    },
            ) {
                FractionalFill(fraction = fraction, color = datum.color)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = LfTheme.spacing.sm),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    // The label may ellipsise; the figure never may. A truncated
                    // merchant name is recoverable by tapping through, a
                    // truncated amount is a wrong number on screen.
                    Text(
                        text = datum.label,
                        style = LfTheme.typography.bodyM,
                        color = LfTheme.colors.textPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier
                            .weight(1f, fill = true)
                            .clearAndSetSemantics { },
                    )
                    Text(
                        text = datum.formattedValue,
                        style = LfTheme.typography.amountM,
                        color = LfTheme.colors.textSecondary,
                        maxLines = 1,
                        softWrap = false,
                        modifier = Modifier
                            .padding(start = LfTheme.spacing.sm)
                            .clearAndSetSemantics { },
                    )
                }
            }
        }
    }
}

/**
 * A fill of exactly [fraction] of the parent's width.
 *
 * `Layout` rather than `fillMaxWidth(fraction)`, because that modifier resolves
 * against the incoming constraint and a zero-value row would collapse to a
 * width of zero and disappear — leaving a merchant with no spend indistinguishable
 * from a merchant that is missing. Measuring against `maxWidth` here keeps a
 * zero row as a visible empty track.
 */
@Composable
private fun FractionalFill(fraction: Float, color: Color) {
    Layout(
        content = {},
        modifier = Modifier
            .background(color)
            .clearAndSetSemantics { },
    ) { _, constraints ->
        val width = (constraints.maxWidth * fraction).toInt().coerceIn(0, constraints.maxWidth)
        layout(width, constraints.maxHeight) {}
    }
}

private val DefaultRowHeight: Dp = 36.dp
