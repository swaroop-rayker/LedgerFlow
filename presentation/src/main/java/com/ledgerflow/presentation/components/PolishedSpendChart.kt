package com.ledgerflow.presentation.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.ledgerflow.core.common.util.CurrencyUtils
import com.ledgerflow.domain.model.Transaction
import com.ledgerflow.core.ui.theme.Spacing
import com.ledgerflow.core.ui.theme.CornerRadius
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun PolishedSpendChart(
    transactions: List<Transaction>,
    modifier: Modifier = Modifier
) {
    if (transactions.isEmpty()) {
        Box(
            modifier = modifier
                .fillMaxWidth()
                .height(160.dp)
                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.2f), RoundedCornerShape(CornerRadius.m)),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = "No recent data for chart",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        return
    }

    val chartData = remember(transactions) { transactions.reversed() } // Chronological order
    val amounts = remember(chartData) { chartData.map { kotlin.math.abs(it.amount).toFloat() } }
    val maxVal = remember(amounts) { amounts.maxOrNull() ?: 1f }
    val minVal = remember(amounts) { amounts.minOrNull() ?: 0f }
    val range = remember(maxVal, minVal) { (maxVal - minVal).coerceAtLeast(1f) }
    val avgVal = remember(amounts) { amounts.average().toFloat() }

    val colorPrimary = MaterialTheme.colorScheme.primary
    val colorAccent = MaterialTheme.colorScheme.tertiary
    val highestColor = Color(0xFFEF4444) // Red for highest spent
    val lowestColor = Color(0xFF10B981)  // Green for lowest spend
    val colorOutline = MaterialTheme.colorScheme.outline

    val animationProgress = remember { Animatable(0f) }
    LaunchedEffect(transactions) {
        animationProgress.animateTo(
            targetValue = 1f,
            animationSpec = tween(durationMillis = 800)
        )
    }

    var activeIndex by remember { mutableStateOf<Int?>(null) }
    var touchPoint by remember { mutableStateOf<Offset?>(null) }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(CornerRadius.l))
            .background(MaterialTheme.colorScheme.surface)
            .padding(Spacing.m)
    ) {
        Column {
            // Interactive header showing active selection details
            if (activeIndex != null && activeIndex!! < chartData.size) {
                val activeTx = chartData[activeIndex!!]
                val sdf = SimpleDateFormat("MMM dd, yyyy", Locale.getDefault())
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = Spacing.s),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text(
                            text = activeTx.merchant,
                            style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Bold),
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = sdf.format(Date(activeTx.timestamp)),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Text(
                        text = if (activeTx.amount < 0) {
                            "+" + CurrencyUtils.formatCents(kotlin.math.abs(activeTx.amount))
                        } else {
                            "-" + CurrencyUtils.formatCents(activeTx.amount)
                        },
                        style = MaterialTheme.typography.titleLarge.copy(
                            fontWeight = FontWeight.ExtraBold,
                            color = if (activeTx.amount < 0) Color(0xFF10B981) else MaterialTheme.colorScheme.onSurface
                        )
                    )
                }
            } else {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = Spacing.s),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text(
                            text = "Daily Inflow/Outflow",
                            style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Bold)
                        )
                        Text(
                            text = "Drag/Tap chart to inspect",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                        )
                    }
                }
            }

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(140.dp)
            ) {
                Canvas(
                    modifier = Modifier
                        .fillMaxSize()
                        .pointerInput(chartData) {
                            detectDragGestures(
                                onDragStart = { offset ->
                                    touchPoint = offset
                                    activeIndex = findNearestIndex(offset.x, size.width.toFloat(), chartData.size)
                                },
                                onDrag = { change, _ ->
                                    touchPoint = change.position
                                    activeIndex = findNearestIndex(change.position.x, size.width.toFloat(), chartData.size)
                                },
                                onDragEnd = {
                                    activeIndex = null
                                    touchPoint = null
                                }
                            )
                        }
                        .pointerInput(chartData) {
                            detectTapGestures { offset ->
                                touchPoint = offset
                                activeIndex = findNearestIndex(offset.x, size.width.toFloat(), chartData.size)
                            }
                        }
                ) {
                    val width = size.width
                    val height = size.height

                    val points = chartData.mapIndexed { idx, txn ->
                        val x = if (chartData.size > 1) idx * (width / (chartData.size - 1)) else width / 2
                        val fraction = (kotlin.math.abs(txn.amount).toFloat() - minVal) / range
                        val y = height - ((fraction * height * 0.7f) + (height * 0.15f)) * animationProgress.value
                        Offset(x, y)
                    }

                    // 1. Draw Average Reference Line
                    val avgFraction = (avgVal - minVal) / range
                    val avgY = height - (avgFraction * height * 0.7f) - (height * 0.15f)
                    drawLine(
                        color = colorOutline.copy(alpha = 0.3f),
                        start = Offset(0f, avgY),
                        end = Offset(width, avgY),
                        strokeWidth = 1.dp.toPx(),
                        pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 10f), 0f)
                    )

                    if (points.isNotEmpty()) {
                        // 2. Draw Bezier Curve and Gradient Fill
                        val fillPath = Path().apply {
                            moveTo(points[0].x, height)
                            lineTo(points[0].x, points[0].y)
                            for (i in 0 until points.size - 1) {
                                val p0 = points[i]
                                val p1 = points[i + 1]
                                val conPoint1 = Offset(p0.x + (p1.x - p0.x) / 3f, p0.y)
                                val conPoint2 = Offset(p0.x + 2f * (p1.x - p0.x) / 3f, p1.y)
                                cubicTo(conPoint1.x, conPoint1.y, conPoint2.x, conPoint2.y, p1.x, p1.y)
                            }
                            lineTo(points.last().x, height)
                            close()
                        }

                        val strokePath = Path().apply {
                            moveTo(points[0].x, points[0].y)
                            for (i in 0 until points.size - 1) {
                                val p0 = points[i]
                                val p1 = points[i + 1]
                                val conPoint1 = Offset(p0.x + (p1.x - p0.x) / 3f, p0.y)
                                val conPoint2 = Offset(p0.x + 2f * (p1.x - p0.x) / 3f, p1.y)
                                cubicTo(conPoint1.x, conPoint1.y, conPoint2.x, conPoint2.y, p1.x, p1.y)
                            }
                        }

                        drawPath(
                            path = fillPath,
                            brush = Brush.verticalGradient(
                                colors = listOf(colorPrimary.copy(alpha = 0.12f), Color.Transparent)
                            )
                        )

                        drawPath(
                            path = strokePath,
                            color = colorPrimary,
                            style = Stroke(width = 3.dp.toPx(), cap = StrokeCap.Round)
                        )

                        // 3. Highlight Special Nodes (Highest & Lowest)
                        points.forEachIndexed { idx, pt ->
                            val txn = chartData[idx]
                            val amountAbs = kotlin.math.abs(txn.amount).toFloat()
                            
                            val isHighest = amountAbs == maxVal && range > 0
                            val isLowest = amountAbs == minVal && range > 0

                            val nodeColor = when {
                                isHighest -> highestColor
                                isLowest -> lowestColor
                                else -> colorAccent
                            }

                            val nodeRadius = when {
                                isHighest || isLowest -> 6.dp.toPx()
                                else -> 4.dp.toPx()
                            }

                            drawCircle(
                                color = nodeColor,
                                radius = nodeRadius,
                                center = pt
                            )
                            drawCircle(
                                color = Color.White,
                                radius = 2.dp.toPx(),
                                center = pt
                            )
                        }

                        // 4. Draw Active touch vertical indicator and pulse
                        activeIndex?.let { index ->
                            if (index < points.size) {
                                val pt = points[index]
                                drawLine(
                                    color = colorPrimary.copy(alpha = 0.5f),
                                    start = Offset(pt.x, 0f),
                                    end = Offset(pt.x, height),
                                    strokeWidth = 1.5.dp.toPx(),
                                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 8f), 0f)
                                )
                                drawCircle(
                                    color = colorPrimary.copy(alpha = 0.2f),
                                    radius = 12.dp.toPx(),
                                    center = pt
                                )
                                drawCircle(
                                    color = colorPrimary,
                                    radius = 6.dp.toPx(),
                                    center = pt
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun findNearestIndex(touchX: Float, width: Float, count: Int): Int {
    if (count <= 1) return 0
    val sectorWidth = width / (count - 1)
    val index = (touchX / sectorWidth).plus(0.5f).toInt()
    return index.coerceIn(0, count - 1)
}
