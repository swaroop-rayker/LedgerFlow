package com.ledgerflow.core.designsystem.chart

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.ledgerflow.core.designsystem.theme.LfTheme

/**
 * Category breakdown and payment-method split (`SPEC.md` §5.6, A2 and A5).
 *
 * **Deliberately small.** `CLAUDE.md`'s compactness brief applies with force
 * here: in both surfaces that use this, the ranked list beside it is what the
 * user actually reads, and the donut exists to orient them. A donut sized to
 * fill the viewport pushes the content off screen to make room for a shape that
 * carries less information than the list it displaced.
 *
 * **No animation.** Not an omission — an arc that animates on first composition
 * is a classic source of flaky Roborazzi diffs, and §12 requires the goldens be
 * reviewed rather than re-recorded until they settle. If a future version
 * animates, it gates the animation off under test rather than accepting the
 * flake.
 *
 * **Values are assumed non-negative**, which the ledger guarantees: `SPEC.md`
 * §5.5 stores amounts positive and the two books are separate, so there is no
 * signed total to draw and no netting for a slice to represent (Law 2). A zero
 * total renders the empty ring rather than dividing by zero.
 */
@Composable
public fun LfDonutChart(
    slices: List<LfDonutSlice>,
    modifier: Modifier = Modifier,
    diameter: Dp = DefaultDiameter,
    thickness: Dp = DefaultThickness,
    centerContent: @Composable () -> Unit = {},
) {
    val trackColor = LfTheme.colors.outline
    val total = slices.sumOf { it.value }.coerceAtLeast(0L)

    // One description for the whole chart. Per-slice semantics nodes on a
    // Canvas would be invisible anyway -- there are no child composables to
    // hang them on -- so the ranked list beside this one carries the per-row
    // detail, and this says what the shape is and what it adds up to.
    val description = if (total == 0L) {
        "Donut chart, no data"
    } else {
        slices.joinToString(prefix = "Donut chart. ", separator = ", ") { slice ->
            "${slice.label} ${percentOf(slice.value, total)}%"
        }
    }

    Box(
        modifier = modifier
            .size(diameter)
            .semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) {
        Canvas(modifier = Modifier.size(diameter)) {
            val stroke = Stroke(width = thickness.toPx())
            val inset = thickness.toPx() / 2f
            val arcSize = androidx.compose.ui.geometry.Size(
                width = size.width - thickness.toPx(),
                height = size.height - thickness.toPx(),
            )
            val topLeft = androidx.compose.ui.geometry.Offset(inset, inset)

            drawArc(
                color = trackColor,
                startAngle = 0f,
                sweepAngle = FULL_CIRCLE,
                useCenter = false,
                topLeft = topLeft,
                size = arcSize,
                style = stroke,
            )

            if (total <= 0L) return@Canvas

            var start = START_ANGLE
            slices.forEach { slice ->
                if (slice.value <= 0L) return@forEach
                val sweep = FULL_CIRCLE * (slice.value.toFloat() / total.toFloat())
                drawArc(
                    color = slice.color,
                    startAngle = start,
                    // A hairline gap between arcs, which is what keeps two
                    // similar hues legible as two slices rather than one. It
                    // comes out of the sweep rather than being drawn over the
                    // top, so the ring stays a ring at any thickness.
                    sweepAngle = (sweep - SLICE_GAP_DEGREES).coerceAtLeast(0f),
                    useCenter = false,
                    topLeft = topLeft,
                    size = arcSize,
                    style = stroke,
                )
                start += sweep
            }
        }
        centerContent()
    }
}

/**
 * Integer percent, rounded half-up, for the accessibility string.
 *
 * Integer arithmetic rather than a `Double` round-trip. Not because Law 3
 * demands it — a percentage is not money — but because `47.99999` rendering as
 * "47%" when the honest answer is "48%" is the same class of defect
 * `CsvMoneyTest` exists to prevent, and the fix costs nothing.
 */
private fun percentOf(value: Long, total: Long): Long =
    if (total <= 0L) 0L else (value * DOUBLED_PERCENT + total) / (total * ROUNDING_HALVES)

/** 100 percent, doubled, so the half-up rounding is exact in integers. */
private const val DOUBLED_PERCENT = 200L
private const val ROUNDING_HALVES = 2L

private const val FULL_CIRCLE = 360f

/** Twelve o'clock, because a reader starts there. */
private const val START_ANGLE = -90f

private const val SLICE_GAP_DEGREES = 1.5f

private val DefaultDiameter: Dp = 132.dp
private val DefaultThickness: Dp = 18.dp
