package com.ledgerflow.core.designsystem.chart

import kotlin.math.sqrt

/**
 * A smooth curve through a chart's points that **never overshoots them**
 * (Fritsch-Carlson monotone cubic interpolation; DESIGN_SYSTEM_PLAN §4.5).
 *
 * A plain cubic spline is smooth by bending past its points: between a quiet
 * day and a big one it would dip below the quiet day, or below zero, and peak
 * above the big one -- a line drawing money that was never spent. This curve
 * is a cubic Hermite spline whose tangents are limited so that, between any
 * two points, it stays inside the range those two points span. A rising
 * series (a running total) gives a curve that only rises.
 *
 * Pure arithmetic over pixel positions, kept apart from the drawing so the
 * guarantee has a test of its own (`LfMonotoneCurveTest`). A linear map from
 * values to pixels keeps the property, so the caller may work in either.
 */
internal object LfMonotoneCurve {

    /** One piece of the curve, from ([x0], [y0]) to ([x1], [y1]), as a cubic Bezier. */
    data class Segment(
        val x0: Float,
        val y0: Float,
        val c1x: Float,
        val c1y: Float,
        val c2x: Float,
        val c2y: Float,
        val x1: Float,
        val y1: Float,
    ) {
        /** The curve's height at [t] in 0..1 along the segment. */
        fun yAt(t: Float): Float {
            val u = 1f - t
            return u * u * u * y0 + 3f * u * u * t * c1y + 3f * u * t * t * c2y + t * t * t * y1
        }
    }

    /**
     * The Bezier segments through the points ([xs], [ys]); [xs] strictly
     * increasing. Fewer than two points have no segments to draw.
     */
    fun segments(xs: FloatArray, ys: FloatArray): List<Segment> {
        require(xs.size == ys.size) { "One y per x" }
        if (xs.size < 2) return emptyList()
        val m = tangents(xs, ys)
        return (0 until xs.size - 1).map { i ->
            val h = xs[i + 1] - xs[i]
            Segment(
                x0 = xs[i],
                y0 = ys[i],
                c1x = xs[i] + h / THIRD,
                c1y = ys[i] + m[i] * h / THIRD,
                c2x = xs[i + 1] - h / THIRD,
                c2y = ys[i + 1] - m[i + 1] * h / THIRD,
                x1 = xs[i + 1],
                y1 = ys[i + 1],
            )
        }
    }

    /** The slope at each point, limited so no segment overshoots its ends. */
    private fun tangents(xs: FloatArray, ys: FloatArray): FloatArray {
        val n = xs.size
        val secant = FloatArray(n - 1) { (ys[it + 1] - ys[it]) / (xs[it + 1] - xs[it]) }
        val m = FloatArray(n)
        m[0] = secant[0]
        m[n - 1] = secant[n - 2]
        for (k in 1 until n - 1) {
            // A peak or a valley, or flat on one side: the curve is level here,
            // or it would rise past the peak (fall past the valley) between points.
            m[k] = if (secant[k - 1] * secant[k] <= 0f) 0f else (secant[k - 1] + secant[k]) / 2f
        }
        for (k in 0 until n - 1) {
            if (secant[k] == 0f) {
                // A flat stretch stays flat: no wobble between two equal days.
                m[k] = 0f
                m[k + 1] = 0f
                continue
            }
            val a = m[k] / secant[k]
            val b = m[k + 1] / secant[k]
            val sum = a * a + b * b
            // Fritsch-Carlson: tangents inside this circle cannot overshoot.
            if (sum > LIMIT) {
                val scale = RADIUS / sqrt(sum)
                m[k] = scale * a * secant[k]
                m[k + 1] = scale * b * secant[k]
            }
        }
        return m
    }

    private const val THIRD = 3f
    private const val RADIUS = 3f
    private const val LIMIT = RADIUS * RADIUS
}
