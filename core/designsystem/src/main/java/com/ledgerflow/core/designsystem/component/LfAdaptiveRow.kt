package com.ledgerflow.core.designsystem.component

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Constraints
import com.ledgerflow.core.designsystem.theme.LfTheme

/**
 * [leading] beside [trailing], or above it when both will not fit.
 *
 * The Ledger's row layout (`EntryRowBody`), lifted here at P5 step 4 so every
 * "name and an amount" row in the app degrades the same way. Without it, at
 * font scale 2.0 an unweighted amount takes the row and the name beside it is
 * squeezed to a few letters — Analytics' "Groc…" and "Landlo…", the Inbox's
 * "19 Aug, 9:…" — which is clipping the information the row exists to show.
 *
 * **Measured per row, not switched on a font-scale threshold.** [trailing] is
 * measured first against the real width; [leading] gets what is left if that
 * clears the width [leading] actually needs on one line (its
 * `maxIntrinsicWidth`), and otherwise the two stack and each gets the full
 * width. So `-₹69.00` stays beside its label at 2.0 while `-₹18,752.00` drops
 * below its own — the decision is per row and per amount, degrading by
 * re-laying-out whole blocks, never by clipping a label (BUG9).
 *
 * [trailing] goes first because its width is the one worth respecting: an
 * amount broken mid-number is unreadable in a way a wrapped name is not. It
 * stays right-aligned when stacked, in the optical column it holds on every
 * other row. Each child is measured once — a plain [Layout], not a
 * `SubcomposeLayout`.
 */
@Composable
public fun LfAdaptiveRow(
    leading: @Composable () -> Unit,
    trailing: @Composable () -> Unit,
    modifier: Modifier = Modifier,
) {
    val gapPx = with(LocalDensity.current) { LfTheme.spacing.sm.roundToPx() }

    Layout(
        modifier = modifier,
        contents = listOf(leading, trailing),
    ) { (leadingMeasurables, trailingMeasurables), constraints ->
        val width = constraints.maxWidth
        val leadingMeasurable = leadingMeasurables.first()

        val trailingPlaceable = trailingMeasurables.first().measure(Constraints(maxWidth = width))
        val remaining = width - trailingPlaceable.width - gapPx
        // What the leading block needs on one line, not a constant: a fixed
        // floor is a guess about content it cannot see, and the Ledger's 96 dp
        // guess left a timestamp clipped beside a narrow amount at 2.0.
        val stacked = remaining < leadingMeasurable.maxIntrinsicWidth(constraints.maxHeight)

        val leadingPlaceable =
            leadingMeasurable.measure(Constraints(maxWidth = if (stacked) width else remaining))

        if (stacked) {
            val height = leadingPlaceable.height + gapPx + trailingPlaceable.height
            layout(width, height) {
                leadingPlaceable.place(0, 0)
                trailingPlaceable.place(x = width - trailingPlaceable.width, y = leadingPlaceable.height + gapPx)
            }
        } else {
            val height = maxOf(leadingPlaceable.height, trailingPlaceable.height)
            layout(width, height) {
                leadingPlaceable.place(0, (height - leadingPlaceable.height) / 2)
                trailingPlaceable.place(
                    x = width - trailingPlaceable.width,
                    y = (height - trailingPlaceable.height) / 2,
                )
            }
        }
    }
}
