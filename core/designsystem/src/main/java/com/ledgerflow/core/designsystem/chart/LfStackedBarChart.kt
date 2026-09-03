package com.ledgerflow.core.designsystem.chart

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.ledgerflow.core.designsystem.theme.LfTheme
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
    val colors = LfTheme.colors
    val labelStyle = LfTheme.typography.label.copy(color = colors.textTertiary)
    val ticks = LfAxisTicks.niceTicks(columns.maxOfOrNull { it.total } ?: 0L)
    val description = contentDescription ?: buildDescription(columns, formatAxisValue)

    Canvas(
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
            ),
    ) {
        if (columns.isEmpty()) return@Canvas

        val plot = measurePlot(size, ticks, measurer, labelStyle, formatAxisValue)
        drawGrid(plot, ticks, colors.outline, measurer, labelStyle, formatAxisValue)
        drawColumns(plot, columns, ticks.last(), colors.surfaceOverlay)
        drawColumnLabels(plot, columns, measurer, labelStyle)
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
    ticks: List<Long>,
    measurer: TextMeasurer,
    labelStyle: TextStyle,
    formatAxisValue: (Long) -> String,
): PlotGeometry {
    val gutter = ticks.maxOf { tick ->
        measurer.measure(formatAxisValue(tick), labelStyle).size.width
    }.toFloat() + AXIS_GAP_PX
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
    gridColor: Color,
    measurer: TextMeasurer,
    labelStyle: TextStyle,
    formatAxisValue: (Long) -> String,
) {
    val axisMax = ticks.last()
    ticks.forEach { tick ->
        val y = plot.height - LfAxisTicks.fractionOf(tick, axisMax) * plot.height
        drawLine(
            color = gridColor,
            start = Offset(plot.left, y),
            end = Offset(plot.left + plot.width, y),
            strokeWidth = GRID_STROKE_PX,
        )
        val layout = measurer.measure(formatAxisValue(tick), labelStyle)
        drawText(
            textLayoutResult = layout,
            topLeft = Offset(
                x = plot.left - AXIS_GAP_PX - layout.size.width,
                y = y - layout.size.height / 2f,
            ),
        )
    }
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawColumnLabels(
    plot: PlotGeometry,
    columns: List<LfBarColumn>,
    measurer: TextMeasurer,
    labelStyle: TextStyle,
) {
    val widest = columns.maxOf { measurer.measure(it.label, labelStyle).size.width }.toFloat()
    val allowed = LfAxisTicks.labelsThatFit(columns.size, plot.width, widest)
    val visible = LfAxisTicks.labelledIndices(columns.size, allowed)
    val slot = plot.slotWidth(columns.size)

    columns.forEachIndexed { index, column ->
        if (index !in visible) return@forEachIndexed
        val layout = measurer.measure(column.label, labelStyle)
        val centre = plot.left + index * slot + slot / 2f
        drawText(
            textLayoutResult = layout,
            topLeft = Offset(
                // Clamped to the plot so the first and last labels stay inside
                // the chart rather than being half-cut by its edge.
                x = (centre - layout.size.width / 2f)
                    .coerceIn(plot.left, plot.left + plot.width - layout.size.width),
                y = plot.height + AXIS_GAP_PX,
            ),
        )
    }
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
