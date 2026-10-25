package com.ledgerflow.core.designsystem.chart

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.CacheDrawScope
import androidx.compose.ui.draw.DrawResult
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.abs

/**
 * Spend over time (`SPEC.md` §5.6, A1).
 *
 * **The chart never holds the series.** §11 forbids handing it more points than
 * it has horizontal pixels, so [columns] arrives already binned to the selected
 * window — the caller re-queries `daily_rollup` at the new resolution when the
 * window changes, rather than this composable transforming data it is holding.
 * That is the whole reason ADR-0005 declined a charting library: the viewport
 * lives in the ViewModel that issues the query, and a library's internal
 * transform would have to be suppressed to get there.
 *
 * **One book only.** There is no signed axis and no negative segment, because
 * there is no netting to draw (Law 2). Callers pass debits or credits, never
 * both.
 *
 * **The y-axis starts at zero and its ticks are round numbers** ([LfAxisTicks]),
 * because a truncated money axis exaggerates differences.
 *
 * **X labels thin out against a measured width**, so the axis stays readable at
 * font scale 2.0 instead of turning into overlapping ink — and the first and
 * last are never dropped, since those are what state the range.
 *
 * **Nothing animates.** An animated first composition is a known source of
 * flaky Roborazzi diffs, and §12 wants goldens reviewed rather than re-recorded
 * until they settle.
 *
 * **Text is measured when the data changes, not on every draw.** Each draw used
 * to measure every tick and column label twice. On a month's worth of columns
 * that is more strings than [rememberTextMeasurer]'s cache holds, so every
 * label was laid out again on every draw. Read from the tab-switch traces
 * (2026-10-19, `docs/PERF-120HZ-PLAN.md` §5): Analytics redraws twice after
 * every switch, once when it re-reads on resume and once when the read lands,
 * and each redraw cost 6-13 ms of main thread for numbers that had not changed.
 * The layouts now live in [drawWithCache], which keeps them until the size or
 * the [StackedChart] changes, and that is remembered on *equal* inputs. A
 * caller that recomposes with a new-but-equal list and a new format lambda
 * therefore keeps the cache, which is the case the resume re-read produces.
 */
@Composable
public fun LfStackedBarChart(
    columns: List<LfBarColumn>,
    formatAxisValue: (Long) -> String,
    modifier: Modifier = Modifier,
    height: Dp = DefaultHeight,
    contentDescription: String? = null,
    onViewportChange: ((LfViewportGesture) -> Unit)? = null,
) {
    val measurer = rememberTextMeasurer()
    val tokens = LfChartTokens
    val labelStyle = tokens.labelStyle.copy(color = tokens.axisLabel)
    val grid = tokens.grid
    val track = tokens.track
    val ticks = LfAxisTicks.niceTicks(columns.maxOfOrNull { it.total } ?: 0L)
    val description = contentDescription ?: buildDescription(columns, formatAxisValue)
    // Formatted here rather than in the draw block, so the chart below is keyed
    // on the strings: a lambda only ever equals itself, and callers build a
    // new one whenever their own state changes.
    val tickLabels = ticks.map(formatAxisValue)
    val chart = remember(columns, ticks, tickLabels, labelStyle, grid, track) {
        StackedChart(
            columns = columns,
            ticks = ticks,
            tickLabels = tickLabels,
            labelStyle = labelStyle,
            gridColor = grid,
            trackColor = track,
        )
    }

    // `Canvas` is this same Spacer with `drawBehind`; `drawWithCache` is the
    // variant that can keep work between draws.
    Spacer(
        modifier = modifier
            .fillMaxWidth()
            .height(height)
            .semantics { this.contentDescription = description }
            .then(
                if (onViewportChange == null) {
                    Modifier
                } else {
                    Modifier.viewportGestures(onViewportChange)
                },
            )
            .drawWithCache { chart.layOut(this, measurer) },
    )
}

/** What the chart draws, held as one value so it can key the draw cache. */
private class StackedChart(
    val columns: List<LfBarColumn>,
    val ticks: List<Long>,
    val tickLabels: List<String>,
    val labelStyle: TextStyle,
    val gridColor: Color,
    val trackColor: Color,
) {

    /** Measures everything once for this size; the returned block only draws. */
    fun layOut(scope: CacheDrawScope, measurer: TextMeasurer): DrawResult {
        if (columns.isEmpty()) return scope.onDrawBehind { }

        val tickLayouts = tickLabels.map { measurer.measure(it, labelStyle) }
        val plot = measurePlot(scope.size, tickLayouts, measurer, labelStyle)
        val labels = placeColumnLabels(plot, columns.map { measurer.measure(it.label, labelStyle) })

        return scope.onDrawBehind {
            drawGrid(plot, ticks, tickLayouts, gridColor)
            drawColumns(plot, columns, ticks.last(), trackColor)
            labels.forEach { (layout, x) ->
                drawText(textLayoutResult = layout, topLeft = Offset(x = x, y = plot.height + AXIS_GAP_PX))
            }
        }
    }
}

/**
 * The plot rectangle, sized from *measured* text rather than constants.
 *
 * The y-axis gutter is as wide as its widest label actually measures. A
 * hardcoded gutter is correct at font scale 1.0 and then either clips the
 * labels or wastes a third of the plot at 2.0, which is the whole failure mode
 * `CLAUDE.md` §5 is about.
 */
private data class PlotGeometry(
    val left: Float,
    val width: Float,
    val height: Float,
) {
    /** Horizontal space per bucket. Immutable: the count comes from the caller. */
    fun slotWidth(count: Int): Float = width / count.coerceAtLeast(1)
}

private fun measurePlot(
    size: Size,
    tickLayouts: List<TextLayoutResult>,
    measurer: TextMeasurer,
    labelStyle: TextStyle,
): PlotGeometry {
    val gutter = tickLayouts.maxOf { it.size.width }.toFloat() + AXIS_GAP_PX
    val xLabelHeight = measurer.measure("0", labelStyle).size.height.toFloat() + AXIS_GAP_PX
    return PlotGeometry(
        left = gutter,
        width = (size.width - gutter).coerceAtLeast(1f),
        height = (size.height - xLabelHeight).coerceAtLeast(1f),
    )
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawColumns(
    plot: PlotGeometry,
    columns: List<LfBarColumn>,
    axisMax: Long,
    trackColor: Color,
) {
    val slot = plot.slotWidth(columns.size)
    val barWidth = (slot * BAR_WIDTH_RATIO).coerceAtLeast(1f)
    val barInset = (slot - barWidth) / 2f

    columns.forEachIndexed { index, column ->
        val left = plot.left + index * slot + barInset
        // An empty bucket still draws its track, so a week with no spending
        // reads as "nothing here" rather than as a gap in the data.
        drawRect(
            color = trackColor,
            topLeft = Offset(left, plot.height - EMPTY_TRACK_PX),
            size = Size(barWidth, EMPTY_TRACK_PX),
        )

        var bottom = plot.height
        column.segments.forEach { segment ->
            if (segment.value <= 0L) return@forEach
            val segmentHeight = LfAxisTicks.fractionOf(segment.value, axisMax) * plot.height
            bottom -= segmentHeight
            drawRect(
                color = segment.color,
                topLeft = Offset(left, bottom),
                size = Size(barWidth, segmentHeight),
            )
        }
    }
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawGrid(
    plot: PlotGeometry,
    ticks: List<Long>,
    tickLayouts: List<TextLayoutResult>,
    gridColor: Color,
) {
    val axisMax = ticks.last()
    ticks.forEachIndexed { index, tick ->
        val y = plot.height - LfAxisTicks.fractionOf(tick, axisMax) * plot.height
        drawLine(
            color = gridColor,
            start = Offset(plot.left, y),
            end = Offset(plot.left + plot.width, y),
            strokeWidth = GRID_STROKE_PX,
        )
        val layout = tickLayouts[index]
        drawText(
            textLayoutResult = layout,
            topLeft = Offset(
                x = plot.left - AXIS_GAP_PX - layout.size.width,
                y = y - layout.size.height / 2f,
            ),
        )
    }
}

/**
 * Which column labels to draw, and where.
 *
 * Positioned first, drawn second: clamping moves an end label inward, so
 * whether two labels overlap is only known once both are placed (BUG41).
 */
private fun placeColumnLabels(
    plot: PlotGeometry,
    layouts: List<TextLayoutResult>,
): List<Pair<TextLayoutResult, Float>> {
    val widest = layouts.maxOf { it.size.width }.toFloat()
    val allowed = LfAxisTicks.labelsThatFit(layouts.size, plot.width, widest)
    val visible = LfAxisTicks.labelledIndices(layouts.size, allowed)
    val slot = plot.slotWidth(layouts.size)

    val placed = visible.sorted().map { index ->
        val layout = layouts[index]
        val centre = plot.left + index * slot + slot / 2f
        // Clamped to the plot so the first and last labels stay inside the
        // chart rather than being half-cut by its edge.
        val x = (centre - layout.size.width / 2f)
            .coerceIn(plot.left, plot.left + plot.width - layout.size.width)
        layout to x
    }
    val drawable = LfAxisTicks.withoutOverlaps(
        spans = placed.map { (layout, x) -> x..(x + layout.size.width) },
        gapPx = AXIS_GAP_PX,
    )
    return placed.filterIndexed { position, _ -> position in drawable }
}

/**
 * What a screen reader hears (§9.6).
 *
 * The totals rather than every segment: a stacked bar with twelve buckets and
 * eight categories is ninety-six numbers, which is not a description, it is a
 * recitation. The ranked list beside the chart carries the per-category detail.
 */
private fun buildDescription(
    columns: List<LfBarColumn>,
    formatAxisValue: (Long) -> String,
): String = if (columns.isEmpty()) {
    "Spending over time, no data"
} else {
    columns.joinToString(prefix = "Spending over time. ", separator = ", ") { column ->
        "${column.label} ${formatAxisValue(column.total)}"
    }
}

private const val GRID_STROKE_PX = 1f
private const val AXIS_GAP_PX = 8f
private const val EMPTY_TRACK_PX = 2f
private const val BAR_WIDTH_RATIO = 0.62f

private val DefaultHeight: Dp = 168.dp

/**
 * What a pan or a pinch means, in terms the caller can re-query with.
 *
 * **Not a transform.** §11 forbids the chart holding more points than it has
 * pixels, so it cannot pan or zoom by moving data it is keeping — it does not
 * keep any. ADR-0005 is explicit that this is the arrangement a charting
 * library could not accommodate: the gesture is *reported*, the ViewModel moves
 * the window, and the next frame is a fresh query at the new resolution.
 */
public sealed interface LfViewportGesture {

    /**
     * Shift the window by a fraction of its own span.
     *
     * A fraction rather than pixels or days, because the chart does not know
     * what a pixel is worth — that depends on the window the caller chose. -0.25
     * means "a quarter of a window into the past".
     */
    public data class Pan(val fractionOfSpan: Float) : LfViewportGesture

    /**
     * Scale the window about its centre. Greater than 1 zooms *out*.
     *
     * Pinching apart shows less time, which is the convention everywhere else,
     * so the composable inverts the raw gesture before reporting it: callers
     * should not have to know which way a pinch runs.
     */
    public data class Zoom(val scale: Float) : LfViewportGesture
}

/**
 * Reports pan and pinch, and reports them **once per gesture, not per event**.
 *
 * A drag emits dozens of pointer events; forwarding each one would issue dozens
 * of queries and the chart would settle on whichever finished last rather than
 * where the finger stopped. So the movement is accumulated and reported when
 * the gesture ends — the same reason the ViewModel cancels an in-flight load on
 * a range change.
 *
 * A gesture smaller than [MIN_PAN_FRACTION] is ignored: a stray touch while
 * scrolling the page should not silently move the window the user is reading.
 */
private fun Modifier.viewportGestures(
    onViewportChange: (LfViewportGesture) -> Unit,
): Modifier = this
    .pointerInput(onViewportChange) {
        var accumulated = 0f
        detectHorizontalDragGestures(
            onDragEnd = {
                val fraction = accumulated / size.width.toFloat()
                // Dragging right moves the window into the past, the way a
                // finger drags paper rather than moving a viewfinder.
                if (abs(fraction) >= MIN_PAN_FRACTION) {
                    onViewportChange(LfViewportGesture.Pan(-fraction))
                }
                accumulated = 0f
            },
            onDragCancel = { accumulated = 0f },
        ) { _, dragAmount -> accumulated += dragAmount }
    }
    .pointerInput(onViewportChange) {
        // **Hand-rolled rather than `detectTransformGestures`, and this is not
        // a preference.** That detector consumes *any* pan once it passes slop,
        // including a one-finger vertical drag -- which on device meant the
        // chart silently ate the page's scroll and the user could not get past
        // it by dragging on the one element filling a third of the screen.
        // Here nothing is consumed until a second pointer is down, so a
        // single-finger drag falls through to the horizontal detector above and
        // to the `LazyColumn` beneath.
        awaitEachGesture {
            awaitFirstDown(requireUnconsumed = false)
            var scale = 1f
            var pinched = false
            while (true) {
                val event = awaitPointerEvent()
                if (event.changes.size >= 2) {
                    pinched = true
                    scale *= event.calculateZoom()
                    event.changes.forEach { it.consume() }
                }
                if (event.changes.none { it.pressed }) break
            }
            // Reported once, at the end: a pinch emits dozens of events, and
            // one query per event would settle on whichever finished last
            // rather than where the fingers stopped.
            if (pinched && abs(scale - 1f) >= MIN_ZOOM_DELTA) {
                onViewportChange(LfViewportGesture.Zoom(1f / scale))
            }
        }
    }

/** A twentieth of the chart's width, below which a drag is a stray touch. */
private const val MIN_PAN_FRACTION = 0.05f

/** Ten percent, below which a pinch is hand tremor rather than intent. */
private const val MIN_ZOOM_DELTA = 0.1f
