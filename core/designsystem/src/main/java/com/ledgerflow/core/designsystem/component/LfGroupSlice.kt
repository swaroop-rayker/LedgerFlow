package com.ledgerflow.core.designsystem.component

import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp

/**
 * Draws one row's slice of a grouped panel (step F5): the reference samples'
 * rows inside one rounded panel with hairlines between them, for a list whose
 * rows are separate lazy items and so cannot share a parent.
 *
 * [LfGroupPosition.First] has the panel's top corners and top edge, [Last] its
 * bottom ones, [Only] all four, [Middle] neither; every slice after the first
 * draws the hairline above itself, inset from the sides. Drawn, not composed:
 * no node is added to a row (`CLAUDE.md` §8), and the paths are built once per
 * size in `drawWithCache`.
 *
 * Slices only meet if the list puts no gap between them.
 */
public fun Modifier.lfGroupSlice(
    position: LfGroupPosition,
    container: Color,
    edge: Color,
    edgeWidth: Dp,
    radius: Dp,
    dividerInset: Dp,
): Modifier = drawWithCache {
    val stroke = edgeWidth.toPx()
    val half = stroke / 2f
    val r = radius.toPx()
    val inset = dividerInset.toPx()
    val top = position == LfGroupPosition.First || position == LfGroupPosition.Only
    val bottom = position == LfGroupPosition.Last || position == LfGroupPosition.Only
    val w = size.width
    val h = size.height

    val fill = Path().apply {
        addRoundRect(
            RoundRect(
                rect = Rect(0f, 0f, w, h),
                topLeft = if (top) CornerRadius(r) else CornerRadius.Zero,
                topRight = if (top) CornerRadius(r) else CornerRadius.Zero,
                bottomRight = if (bottom) CornerRadius(r) else CornerRadius.Zero,
                bottomLeft = if (bottom) CornerRadius(r) else CornerRadius.Zero,
            ),
        )
    }
    // The outline inside the bounds by half the stroke, so neighbours' edges
    // line up rather than doubling.
    val outline = edgePath(w, h, half, r - half, top, bottom)

    onDrawBehind {
        drawPath(fill, container)
        drawPath(outline, edge, style = Stroke(width = stroke))
        if (!top) {
            drawLine(edge, Offset(inset, half), Offset(w - inset, half), stroke)
        }
    }
}

/** The slice's share of the panel's outline: its sides, and the edges its position owns. */
@Suppress("LongParameterList")
private fun edgePath(w: Float, h: Float, half: Float, r: Float, top: Boolean, bottom: Boolean): Path = Path().apply {
    val left = half
    val right = w - half
    val upper = half
    val lower = h - half
    when {
        top && bottom -> addRoundRect(RoundRect(Rect(left, upper, right, lower), CornerRadius(r)))
        top -> {
            moveTo(left, h)
            lineTo(left, upper + r)
            arcTo(Rect(left, upper, left + 2 * r, upper + 2 * r), LEFT_DEG, QUARTER_DEG, false)
            lineTo(right - r, upper)
            arcTo(Rect(right - 2 * r, upper, right, upper + 2 * r), UP_DEG, QUARTER_DEG, false)
            lineTo(right, h)
        }
        bottom -> {
            moveTo(left, 0f)
            lineTo(left, lower - r)
            arcTo(Rect(left, lower - 2 * r, left + 2 * r, lower), LEFT_DEG, -QUARTER_DEG, false)
            lineTo(right - r, lower)
            arcTo(Rect(right - 2 * r, lower - 2 * r, right, lower), DOWN_DEG, -QUARTER_DEG, false)
            lineTo(right, 0f)
        }
        else -> {
            moveTo(left, 0f)
            lineTo(left, h)
            moveTo(right, 0f)
            lineTo(right, h)
        }
    }
}

// Arc angles, in Path's degrees: 0 points right and angles grow clockwise.
private const val DOWN_DEG = 90f
private const val LEFT_DEG = 180f
private const val UP_DEG = 270f
private const val QUARTER_DEG = 90f

/** Where a row sits in its group. */
public enum class LfGroupPosition { Only, First, Middle, Last }
