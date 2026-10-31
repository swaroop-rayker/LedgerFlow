package com.ledgerflow.core.designsystem.chart

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.ledgerflow.core.designsystem.component.ProgressTrack

/**
 * Budget progress (`SPEC.md` §5.6 A7, §5.7).
 *
 * **A linear bar, not a ring.** §5.6 allows either. A ring is a bigger, rounder
 * object that says the same thing, and budgets are a *list* — one per category —
 * so the bar wins on `CLAUDE.md`'s compactness brief: six rings is a gallery,
 * six bars is something you can read down.
 *
 * **Two marks, and the second is the point.** [fraction] is the filled bar;
 * [projectedFraction] is the burn-rate forecast, drawn as a tick further along
 * the track. A budget at 60% on the 12th of a 30-day month is fine; the same
 * budget projecting 150% is not, and the tick is what makes that visible before
 * the overrun instead of after it.
 *
 * **Over-budget is `warn`, not an alarm colour.** §9.1's palette has one warning
 * token and the overspend is a fact rather than an emergency — the app does not
 * shout at someone about money they have already spent.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
public fun LfBudgetBar(
    label: String,
    formattedSpent: String,
    formattedBudget: String,
    fraction: Float,
    projectedFraction: Float,
    modifier: Modifier = Modifier,
    color: Color = LfChartTokens.series,
    height: Dp = DefaultBarHeight,
    contentDescription: String? = null,
    amounts: String? = null,
) {
    val tokens = LfChartTokens
    val over = fraction > 1f
    val fill = if (over) tokens.overBudget else color

    // [amounts] replaces "spent of budget" where a screen says it another way
    // (Home's "₹250 left of ₹5,000", step F5); the spoken line follows it.
    val pair = amounts ?: "$formattedSpent of $formattedBudget"
    val spoken = contentDescription ?: "$label, $pair"

    Column(
        modifier = modifier
            .fillMaxWidth()
            .semantics { this.contentDescription = spoken },
        verticalArrangement = Arrangement.spacedBy(tokens.gap),
    ) {
        // **`FlowRow`, not `Row`, and the goldens are why.** With a weighted
        // `Row` the unbreakable amount pair took its width first and the label
        // ellipsised to whatever was left — at font scale 2.0 that measured
        // "G...", "Eat...", "Tr...", a budget list where no row says which
        // budget it is. `CLAUDE.md` §5 is explicit that the degradation is to
        // wrap *whole controls*, never to clip a label (BUG9), so at narrow
        // widths the amounts drop to their own line and the name gets the row.
        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(tokens.wideGap),
            verticalArrangement = Arrangement.spacedBy(tokens.gap),
        ) {
            Text(
                text = label,
                style = tokens.textStyle,
                color = tokens.text,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.clearAndSetSemantics { },
            )
            BudgetAmounts(
                text = pair,
                color = if (over) tokens.overBudget else tokens.secondaryText,
            )
        }

        // LfProgressBar's track (step E1), with the burn-rate tick on top. The
        // bar says nothing itself: the row's description above already does.
        ProgressTrack(
            fraction = fraction,
            fill = fill,
            track = tokens.track,
            height = height,
        ) {
            // Only when the projection is both meaningful and not already true.
            if (projectedFraction > fraction && projectedFraction <= 1f) {
                ProjectionTick(fraction = projectedFraction, color = tokens.reference)
            }
        }
    }
}

/**
 * "₹4,200 of ₹6,000" -- both figures, because a percentage alone hides the
 * scale, and the headroom left is what someone actually wants off this row.
 *
 * **Wraps at "of", never clips (BUG55).** It was `softWrap = false`, so once the
 * pair was wider than the whole row the end of the budget was simply cut off:
 * "₹8,200.00 of ₹10,000.0" at font scale 2.0 in Home's card. The [FlowRow]
 * above already moves the pair below the name when they will not share a line;
 * this is the step after that, when the pair alone is wider than the row. The
 * only spaces are either side of "of", so the break lands between the amounts
 * and never inside one. Where the pair fits, nothing about it changes.
 */
@Composable
internal fun BudgetAmounts(
    text: String,
    color: Color,
    onTextLayout: (TextLayoutResult) -> Unit = {},
) {
    Text(
        text = text,
        style = LfChartTokens.labelStyle,
        color = color,
        maxLines = AMOUNT_MAX_LINES,
        onTextLayout = onTextLayout,
        modifier = Modifier.clearAndSetSemantics { },
    )
}

/**
 * The burn-rate mark.
 *
 * A hairline placed *at* the projection, not a second fill that grows to it: a
 * translucent bar reaching the same point would read as more spending, which is
 * exactly the wrong message — this is where today's pace lands, not money that
 * has gone.
 */
@Composable
private fun ProjectionTick(fraction: Float, color: Color) {
    Layout(
        content = { Box(modifier = Modifier.background(color)) },
        modifier = Modifier
            .fillMaxSize()
            .clearAndSetSemantics { },
    ) { measurables, constraints ->
        val placeable = measurables.first().measure(
            Constraints.fixed(TICK_WIDTH_PX, constraints.maxHeight),
        )
        layout(constraints.maxWidth, constraints.maxHeight) {
            val x = (constraints.maxWidth * fraction).toInt()
                .coerceIn(0, (constraints.maxWidth - TICK_WIDTH_PX).coerceAtLeast(0))
            placeable.place(x, 0)
        }
    }
}

private const val TICK_WIDTH_PX = 4

/** Spent on one line, the budget on the next: the most the pair can need. */
private const val AMOUNT_MAX_LINES = 2

private val DefaultBarHeight: Dp = 8.dp
