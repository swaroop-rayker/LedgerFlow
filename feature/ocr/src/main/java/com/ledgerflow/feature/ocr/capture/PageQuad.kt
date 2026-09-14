package com.ledgerflow.feature.ocr.capture

import kotlin.math.hypot

/**
 * The geometry of a photographed page's outline, with no OpenCV in it.
 *
 * [OpenCvPageCorrector] finds four candidate corners; everything about whether
 * to trust them and what rectangle they should become is decided here, as
 * arithmetic, so it is a JVM test rather than a device one. The same split the
 * extraction package makes with `RecognizedElement`.
 */
internal object PageQuad {

    data class Point(val x: Float, val y: Float)

    /** Corners in the order a perspective transform expects them. */
    data class Corners(val topLeft: Point, val topRight: Point, val bottomRight: Point, val bottomLeft: Point) {
        val all: List<Point> get() = listOf(topLeft, topRight, bottomRight, bottomLeft)
    }

    /**
     * Top-left, top-right, bottom-right, bottom-left, **by coordinate sums and
     * differences**, not by the order a contour happened to be traced in.
     *
     * The top-left corner has the smallest `x + y` and the bottom-right the
     * largest; the top-right has the largest `x - y` and the bottom-left the
     * smallest. That holds for any page rotated less than 45°, which is all a
     * receipt held in front of a camera ever is — and it is independent of
     * whether the contour ran clockwise, which OpenCV does not promise.
     */
    fun order(points: List<Point>): Corners {
        require(points.size == CORNERS) { "a page outline has four corners, got ${points.size}" }
        return Corners(
            topLeft = points.minBy { it.x + it.y },
            topRight = points.maxBy { it.x - it.y },
            bottomRight = points.maxBy { it.x + it.y },
            bottomLeft = points.minBy { it.x - it.y },
        )
    }

    /**
     * The rectangle the page becomes: its **longest** measured width and height.
     *
     * The far edge of a page photographed at an angle is foreshortened, so the
     * longer of each pair of opposite edges is the closer one to the page's real
     * proportions. Taking the shorter would shrink the text the recogniser has
     * to read.
     */
    fun targetSize(corners: Corners): Pair<Int, Int> {
        val width = maxOf(
            distance(corners.topLeft, corners.topRight),
            distance(corners.bottomLeft, corners.bottomRight),
        )
        val height = maxOf(
            distance(corners.topLeft, corners.bottomLeft),
            distance(corners.topRight, corners.bottomRight),
        )
        return width.toInt().coerceAtLeast(1) to height.toInt().coerceAtLeast(1)
    }

    /**
     * Whether four points are a page worth warping to, in an image of the given
     * size.
     *
     * **Refusing is always safe; warping a wrong quad is not.** A false outline
     * — a table edge, a shadow, a phone case in shot — would stretch the photo
     * into something the recogniser reads worse than the original. So the
     * outline must be convex, fill at least [MIN_AREA_SHARE] of the frame (a
     * receipt the user framed with the capture guide does), and not the frame
     * itself ([MAX_AREA_SHARE]), which means no background was found and there
     * is nothing to correct.
     */
    fun isPlausible(corners: Corners, imageWidth: Int, imageHeight: Int): Boolean {
        val share = area(corners) / (imageWidth.toFloat() * imageHeight)
        return isConvex(corners) && share >= MIN_AREA_SHARE && share <= MAX_AREA_SHARE
    }

    /** Shoelace formula over the ordered corners. */
    fun area(corners: Corners): Float {
        val p = corners.all
        var twice = 0f
        for (i in p.indices) {
            val a = p[i]
            val b = p[(i + 1) % p.size]
            twice += a.x * b.y - b.x * a.y
        }
        return kotlin.math.abs(twice) / 2f
    }

    /** Every turn has the same sign: no bow-tie, no dent. */
    private fun isConvex(corners: Corners): Boolean {
        val p = corners.all
        val turns = p.indices.map { i ->
            val a = p[i]
            val b = p[(i + 1) % p.size]
            val c = p[(i + 2) % p.size]
            (b.x - a.x) * (c.y - b.y) - (b.y - a.y) * (c.x - b.x)
        }
        return turns.all { it > 0f } || turns.all { it < 0f }
    }

    private fun distance(a: Point, b: Point): Float = hypot(a.x - b.x, a.y - b.y)

    private const val CORNERS = 4
    const val MIN_AREA_SHARE = 0.20f
    const val MAX_AREA_SHARE = 0.97f
}
