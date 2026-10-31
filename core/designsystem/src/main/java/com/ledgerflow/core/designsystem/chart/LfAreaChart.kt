package com.ledgerflow.core.designsystem.chart

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.CacheDrawScope
import androidx.compose.ui.draw.DrawResult
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * One series over time as a soft line over a fading fill (step F6,
 * DESIGN_SYSTEM_PLAN §4.5): Analytics' "Spend over time", Total.
 *
 * - **A monotone curve** ([LfMonotoneCurve]): smooth, and never past the
 *   points it joins, so it draws no money that was never spent.
 * - **Under it, the series colour fading** from 24 % to nothing, top to
 *   bottom: the only gradient in the charts.
 * - **A baseline and nothing else.** No grid and no y-axis: the last point
 *   carries a dot and a small pill with its value in Plex Mono, and the x
 *   axis names the first, middle and last buckets.
 *
 * Everything [LfStackedBarChart] holds to holds here. [points] arrive binned
 * to the window (§11), one book only (Law 2), the y-axis starts at zero, and
 * a pan or a pinch is reported for the caller to re-query, never applied to
 * data the chart keeps. Text is measured in [drawWithCache], keyed on equal
 * inputs (`LfAreaChartRedrawTest`). Nothing animates.
 *
 * @param points the series, oldest first, each value in minor units.
 * @param color the book's colour: money out or money in.
 * @param formatValue a value as the pill and the description say it.
 * @param description what the series is, for a screen reader ("Spending so far").
 */
@Composable
public fun LfAreaChart(
    points: List<LfAreaPoint>,
    color: Color,
    formatValue: (Long) -> String,
    modifier: Modifier = Modifier,
    description: String = "Over time",
    height: Dp = DefaultHeight,
    onViewportChange: ((LfViewportGesture) -> Unit)? = null,
) {
    val measurer = rememberTextMeasurer()
    val tokens = LfChartTokens
    val axisStyle = tokens.labelStyle.copy(color = tokens.axisLabel)
    val pillStyle = tokens.figureStyle.copy(color = tokens.text)
    val pillText = points.lastOrNull()?.let { formatValue(it.value) }.orEmpty()
    // Per point, as a screen reader hears it (§9.6): the label and its value.
    val spoken = if (points.isEmpty()) {
        "$description, no data"
    } else {
        points.joinToString(prefix = "$description. ", separator = ", ") { "${it.label} ${formatValue(it.value)}" }
    }
    val colors = AreaColors(
        line = color,
        baseline = tokens.grid,
        pill = tokens.pill,
        ring = tokens.ringBehind,
    )
    val chart = remember(points, pillText, axisStyle, pillStyle, colors) {
        AreaChart(points, pillText, axisStyle, pillStyle, colors)
    }

    Spacer(
        modifier = modifier
            .fillMaxWidth()
            .height(height)
            .semantics { contentDescription = spoken }
            .then(if (onViewportChange == null) Modifier else Modifier.viewportGestures(onViewportChange))
            .drawWithCache { chart.layOut(this, measurer) },
    )
}

/** The chart's inks, as one value so they key the draw cache together. */
private data class AreaColors(val line: Color, val baseline: Color, val pill: Color, val ring: Color)

/** What the chart draws, held as one value so it can key the draw cache. */
private class AreaChart(
    val points: List<LfAreaPoint>,
    val pillText: String,
    val axisStyle: TextStyle,
    val pillStyle: TextStyle,
    val colors: AreaColors,
) {

    /** Measures and shapes everything once for this size; the returned block only draws. */
    @Suppress("LongMethod") // One layout pass: plot, curve, fill, pill, labels.
    fun layOut(scope: CacheDrawScope, measurer: TextMeasurer): DrawResult = with(scope) {
        if (points.isEmpty()) return onDrawBehind { }

        val stroke = LINE_WIDTH.toPx()
        val dot = DOT_RADIUS.toPx()
        val ring = DOT_RING.toPx()
        val gap = AXIS_GAP.toPx()
        val pillLayout = measurer.measure(pillText, pillStyle)
        val pillPadX = PILL_PAD_X.toPx()
        val pillPadY = PILL_PAD_Y.toPx()
        val pillSize = Size(pillLayout.size.width + pillPadX * 2, pillLayout.size.height + pillPadY * 2)
        val labels = axisLabels(measurer)
        val labelHeight = labels.maxOfOrNull { it.second.size.height }?.toFloat() ?: 0f

        // The plot: room above for the pill over the highest point, below for
        // the x labels, and the dot's half-width at each end so it is not cut.
        val top = pillSize.height + gap + dot
        val bottom = size.height - labelHeight - gap
        val left = dot + ring
        val right = size.width - dot - ring
        val plotHeight = (bottom - top).coerceAtLeast(1f)
        val max = points.maxOf { it.value }.coerceAtLeast(1L)
        val xs = FloatArray(points.size) { i ->
            if (points.size == 1) right else left + (right - left) * i / (points.size - 1)
        }
        val ys = FloatArray(points.size) { i -> bottom - LfAxisTicks.fractionOf(points[i].value, max) * plotHeight }

        val line = curvePath(xs, ys)
        val fill = Path().apply {
            addPath(line)
            lineTo(xs.last(), bottom)
            lineTo(xs.first(), bottom)
            close()
        }
        val fade = Brush.verticalGradient(
            colors = listOf(colors.line.copy(alpha = FILL_TOP_ALPHA), colors.line.copy(alpha = 0f)),
            startY = top,
            endY = bottom,
        )
        val last = Offset(xs.last(), ys.last())
        // The pill centred over the last point, kept inside the chart.
        val pillLeft = (last.x - pillSize.width / 2f).coerceIn(0f, (size.width - pillSize.width).coerceAtLeast(0f))
        val pillTop = (last.y - dot - gap - pillSize.height).coerceAtLeast(0f)
        val labelTop = bottom + gap
        val placed = labels.map { (x, layout) ->
            val leftX = when (x) {
                LabelAt.Start -> 0f
                LabelAt.Middle -> size.width / 2f - layout.size.width / 2f
                LabelAt.End -> size.width - layout.size.width
            }
            leftX to layout
        }.let(::withoutOverlaps)

        onDrawBehind {
            if (points.size > 1) {
                drawPath(fill, fade)
                drawPath(line, colors.line, style = Stroke(width = stroke, cap = StrokeCap.Round))
            }
            drawLine(colors.baseline, Offset(0f, bottom), Offset(size.width, bottom), BASELINE_PX)
            drawCircle(colors.ring, radius = dot + ring, center = last)
            drawCircle(colors.line, radius = dot, center = last)
            drawRoundRect(
                color = colors.pill,
                topLeft = Offset(pillLeft, pillTop),
                size = pillSize,
                cornerRadius = CornerRadius(pillSize.height / 2f),
            )
            drawText(pillLayout, topLeft = Offset(pillLeft + pillPadX, pillTop + pillPadY))
            placed.forEach { (x, layout) -> drawText(layout, topLeft = Offset(x, labelTop)) }
        }
    }

    /** First, middle and last labels; one label when the series has one point. */
    private fun axisLabels(measurer: TextMeasurer): List<Pair<LabelAt, TextLayoutResult>> {
        val measure = { text: String -> measurer.measure(text, axisStyle) }
        return when (points.size) {
            1 -> listOf(LabelAt.End to measure(points.single().label))
            2 -> listOf(LabelAt.Start to measure(points.first().label), LabelAt.End to measure(points.last().label))
            else -> listOf(
                LabelAt.Start to measure(points.first().label),
                LabelAt.Middle to measure(points[points.size / 2].label),
                LabelAt.End to measure(points.last().label),
            )
        }
    }

    private fun curvePath(xs: FloatArray, ys: FloatArray): Path = Path().apply {
        moveTo(xs.first(), ys.first())
        LfMonotoneCurve.segments(xs, ys).forEach { s -> cubicTo(s.c1x, s.c1y, s.c2x, s.c2y, s.x1, s.y1) }
    }
}

private enum class LabelAt { Start, Middle, End }

/**
 * Drops the middle label where it would touch an end one: at font scale 2.0
 * the three no longer fit, and the ends are what state the range.
 */
private fun withoutOverlaps(labels: List<Pair<Float, TextLayoutResult>>): List<Pair<Float, TextLayoutResult>> {
    if (labels.size < MIDDLE_AND_ENDS) return labels
    val (start, middle, end) = labels
    val touches = middle.first < start.first + start.second.size.width + LABEL_GAP_PX ||
        middle.first + middle.second.size.width + LABEL_GAP_PX > end.first
    return if (touches) listOf(start, end) else labels
}

private const val FILL_TOP_ALPHA = 0.24f
private const val BASELINE_PX = 1f
private const val LABEL_GAP_PX = 8f
private const val MIDDLE_AND_ENDS = 3

private val DefaultHeight: Dp = 168.dp
private val LINE_WIDTH: Dp = 2.dp
private val DOT_RADIUS: Dp = 4.dp
private val DOT_RING: Dp = 2.dp
private val AXIS_GAP: Dp = 8.dp
private val PILL_PAD_X: Dp = 8.dp
private val PILL_PAD_Y: Dp = 4.dp
