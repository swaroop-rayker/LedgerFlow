package com.ledgerflow.core.designsystem.chart

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import com.ledgerflow.core.designsystem.theme.LfTheme

/**
 * Calendar heatmap (`SPEC.md` §5.6, A6).
 *
 * **A grid, not a Canvas.** Seven columns of rounded cells is layout, and
 * drawing it by hand would mean owning cell hit-testing and text placement for
 * no benefit. ADR-0005 rules out a charting *dependency*; it does not require
 * everything to be `drawRect`.
 *
 * **Intensity comes from alpha over one accent, not from a colour ramp.**
 * `CLAUDE.md` is firm that the palette does not change to solve a presentation
 * problem, and a red-to-green ramp would also say something this app does not
 * mean — high spending on a Saturday is not an error. One hue at varying
 * opacity reads as "more" and "less" and nothing else.
 *
 * **Scaled against the busiest day, not the total.** A month's total spread
 * over 30 cells would make every cell nearly invisible; the busiest day is what
 * gives the month its contrast.
 *
 * **A day with no spending is a visible empty cell**, never a gap. The month has
 * a fixed shape, and a hole in it reads as missing data rather than as a day
 * nothing happened.
 */
@Composable
public fun LfCalendarHeatmap(
    days: List<LfHeatmapDay>,
    modifier: Modifier = Modifier,
    weekdayLabels: List<String> = DEFAULT_WEEKDAYS,
) {
    val colors = LfTheme.colors
    val busiest = days.maxOfOrNull { it.amount }?.coerceAtLeast(1L) ?: 1L

    val description = if (days.none { it.amount > 0L }) {
        "Calendar heatmap, no spending this month"
    } else {
        val busiestDay = days.maxByOrNull { it.amount }
        "Calendar heatmap. Busiest day: ${busiestDay?.dayOfMonth}, " +
            "${busiestDay?.formattedAmount}"
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .semantics { contentDescription = description },
        verticalArrangement = Arrangement.spacedBy(LfTheme.spacing.xs),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(LfTheme.spacing.xs)) {
            weekdayLabels.forEach { label ->
                Text(
                    text = label,
                    style = LfTheme.typography.label,
                    color = colors.textTertiary,
                    textAlign = TextAlign.Center,
                    maxLines = 1,
                    modifier = Modifier
                        .weight(1f)
                        .clearAndSetSemantics { },
                )
            }
        }

        days.chunked(DAYS_PER_WEEK).forEach { week ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(LfTheme.spacing.xs),
            ) {
                week.forEach { day ->
                    Cell(day = day, busiest = busiest, accent = colors.accent)
                }
                // A short final week is padded so its cells keep the same width
                // as every other row's -- otherwise the last week's squares
                // stretch and the grid stops reading as a calendar.
                repeat(DAYS_PER_WEEK - week.size) {
                    Box(modifier = Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
private fun androidx.compose.foundation.layout.RowScope.Cell(
    day: LfHeatmapDay,
    busiest: Long,
    accent: Color,
) {
    val empty = LfTheme.colors.surfaceOverlay
    val fill = when {
        day.blank -> Color.Transparent
        day.amount <= 0L -> empty
        else -> accent.copy(
            alpha = MIN_ALPHA +
                (day.amount.toFloat() / busiest.toFloat()) * (MAX_ALPHA - MIN_ALPHA),
        )
    }

    Box(
        modifier = Modifier
            .weight(1f)
            .aspectRatio(1f)
            .clip(RoundedCornerShape(LfTheme.spacing.cornerSmall))
            .background(fill)
            .semantics {
                contentDescription = if (day.blank) {
                    ""
                } else {
                    "${day.dayOfMonth}: ${day.formattedAmount}"
                }
            },
    )
}

/**
 * The floor stops a day with a rupee on it from being invisible.
 *
 * Without it, one large day in a month flattens every other day's alpha to
 * near zero and the grid reads as "one day of spending", which is the opposite
 * of what a heatmap is for.
 */
private const val MIN_ALPHA = 0.22f
private const val MAX_ALPHA = 1.0f
private const val DAYS_PER_WEEK = 7

private val DEFAULT_WEEKDAYS = listOf("M", "T", "W", "T", "F", "S", "S")
