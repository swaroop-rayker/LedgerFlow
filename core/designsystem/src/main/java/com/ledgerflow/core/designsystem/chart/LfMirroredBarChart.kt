package com.ledgerflow.core.designsystem.chart

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.ledgerflow.core.designsystem.theme.LfTheme

/**
 * D1 — the two books, mirrored about a shared zero line (`SPEC.md` §5.6).
 *
 * **The point of this chart is the thing it refuses to draw.** Every competing
 * app puts a single netted figure on its home screen; Law 2 forbids one here,
 * and D1 exists to make that separation legible rather than to apologise for
 * it. There is no net line, no difference and no combined total — nothing in
 * this file takes a credit and a debit as operands. The two are measured from
 * the same baseline in opposite directions and never meet.
 *
 * **Credit up, debit down**, which is the only orientation anyone reads without
 * a legend: money arriving rises, money leaving falls.
 *
 * **One shared scale, not one per half.** Scaling each book to its own half
 * would draw ₹50,000 of income and ₹5,000 of spending as equal bars — exactly
 * the false impression a net figure gives, reintroduced as geometry. The shared
 * maximum is what makes an asymmetric month *look* asymmetric.
 *
 * **Both halves are labelled with the same positive figure.** A negative number
 * below the line would invite reading the two as one signed series, which is
 * the netting this chart exists to refuse. Below the line means "out", and the
 * label says how much went out.
 *
 * **This is not where debit detail is read**, and the shared scale is why. In a
 * salary month one credit dwarfs thirty debits, and the lower half compresses to
 * near-invisible slivers — which is the true shape of that month, and the honest
 * cost of a comparison. A1 sits directly above with the debits at full scale,
 * and the two totals beneath carry the absolute figures. D1's job is the
 * comparison, not the reading.
 *
 * Like every chart here it receives data already binned to the window (§11); it
 * holds no series and transforms nothing.
 */
@Composable
public fun LfMirroredBarChart(
    columns: List<LfMirroredColumn>,
    formatAxisValue: (Long) -> String,
    modifier: Modifier = Modifier,
    height: Dp = DefaultHeight,
    contentDescription: String? = null,
) {
    val measurer = rememberTextMeasurer()
    val colors = LfTheme.colors
    val labelStyle = LfTheme.typography.label.copy(color = colors.textSecondary)

    // One maximum across both books. Per-half scaling is the net figure
    // smuggled back in as geometry.
    val peak = columns.maxOfOrNull { maxOf(it.creditMinor, it.debitMinor) } ?: 0L

    val description = contentDescription ?: "Both books, ${columns.size} periods. " +
        "Money in above the line, money out below. They are never combined."

    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .height(height)
            .semantics { this.contentDescription = description },
    ) {
        val axisWidth = measurer.measure(formatAxisValue(peak), labelStyle).size.width.toFloat()
        val plotLeft = axisWidth + AXIS_GAP_PX
        val plotWidth = (size.width - plotLeft).coerceAtLeast(1f)
        val midY = size.height / 2f
        val halfHeight = ((size.height / 2f) - LABEL_ROOM_PX).coerceAtLeast(1f)

        drawAxis(
            measurer = measurer,
            style = labelStyle,
            format = formatAxisValue,
            peak = peak,
            plotLeft = plotLeft,
            midY = midY,
            halfHeight = halfHeight,
            grid = colors.surfaceOverlay,
        )

        if (columns.isEmpty() || peak <= 0L) return@Canvas

        val slot = plotWidth / columns.size
        val barWidth = (slot * BAR_FILL).coerceAtLeast(1f)
        val inset = (slot - barWidth) / 2f

        columns.forEachIndexed { index, column ->
            val left = plotLeft + slot * index + inset
            // Two independent draws from one baseline. Nothing here subtracts.
            drawBar(left, barWidth, column.creditMinor, peak, midY, halfHeight, true, colors.credit)
            drawBar(left, barWidth, column.debitMinor, peak, midY, halfHeight, false, colors.debit)
        }
    }
}

@Suppress("LongParameterList")
private fun DrawScope.drawBar(
    left: Float,
    width: Float,
    valueMinor: Long,
    peak: Long,
    midY: Float,
    halfHeight: Float,
    up: Boolean,
    color: Color,
) {
    if (valueMinor <= 0L || peak <= 0L) return
    val barHeight = (valueMinor.toFloat() / peak.toFloat()) * halfHeight
    drawRect(
        color = color,
        topLeft = Offset(left, if (up) midY - barHeight else midY),
        size = Size(width, barHeight),
    )
}

@Suppress("LongParameterList")
private fun DrawScope.drawAxis(
    measurer: TextMeasurer,
    style: TextStyle,
    format: (Long) -> String,
    peak: Long,
    plotLeft: Float,
    midY: Float,
    halfHeight: Float,
    grid: Color,
) {
    // The zero line is the chart's whole argument — the boundary the two books
    // never cross — so it is drawn even when there is nothing to plot.
    drawLine(
        color = grid,
        start = Offset(plotLeft, midY),
        end = Offset(size.width, midY),
        strokeWidth = ZERO_LINE_PX,
    )
    if (peak <= 0L) return

    listOf(true, false).forEach { up ->
        val y = if (up) midY - halfHeight else midY + halfHeight
        drawLine(
            color = grid,
            start = Offset(plotLeft, y),
            end = Offset(size.width, y),
            strokeWidth = GRID_PX,
        )
        val layout = measurer.measure(format(peak), style)
        drawText(
            textLayoutResult = layout,
            topLeft = Offset(0f, y - layout.size.height / 2f),
        )
    }
}

private const val BAR_FILL = 0.6f
private const val AXIS_GAP_PX = 8f
private const val LABEL_ROOM_PX = 6f
private const val ZERO_LINE_PX = 2f
private const val GRID_PX = 1f

private val DefaultHeight: Dp = 160.dp
