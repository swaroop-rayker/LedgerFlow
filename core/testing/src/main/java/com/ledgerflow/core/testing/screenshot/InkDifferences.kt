package com.ledgerflow.core.testing.screenshot

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PixelMap
import kotlin.math.abs

/**
 * Where two captures of the same size disagree by more than anti-aliasing, as
 * readable "(x,y) expected vs actual" lines, at most [limit] of them.
 *
 * For tests that hold a rewritten composable to the one it replaced, pixel for
 * pixel (PERF-120HZ-PLAN §2). Not a golden comparison: both sides are rendered
 * in the same run by the same renderer, so the only legitimate difference is
 * how an edge that falls mid-pixel is anti-aliased -- measured at up to 6/255
 * on a circle of 31.5 px radius, and 1/255 on a vector glyph's edges. A layout
 * a pixel out moves whole edges, by far more than [tolerance].
 */
public fun inkDifferences(
    expected: PixelMap,
    actual: PixelMap,
    tolerance: Float = ANTI_ALIASING_TOLERANCE,
    limit: Int = DEFAULT_LIMIT,
): List<String> {
    require(expected.width == actual.width && expected.height == actual.height) {
        "sizes differ: ${expected.width}x${expected.height} expected, ${actual.width}x${actual.height} actual"
    }
    val differences = mutableListOf<String>()
    for (y in 0 until expected.height) {
        for (x in 0 until expected.width) {
            val e = expected[x, y]
            val a = actual[x, y]
            if (!e.sameInk(a, tolerance) && differences.size < limit) differences += "($x,$y) $e vs $a"
        }
    }
    return differences
}

private fun Color.sameInk(other: Color, tolerance: Float): Boolean =
    abs(red - other.red) <= tolerance &&
        abs(green - other.green) <= tolerance &&
        abs(blue - other.blue) <= tolerance &&
        abs(alpha - other.alpha) <= tolerance

/** 8/255 per channel. See [inkDifferences]. */
public const val ANTI_ALIASING_TOLERANCE: Float = 8f / 255f

private const val DEFAULT_LIMIT = 20
