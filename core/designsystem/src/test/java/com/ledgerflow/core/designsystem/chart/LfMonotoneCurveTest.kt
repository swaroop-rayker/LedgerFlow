package com.ledgerflow.core.designsystem.chart

import com.google.common.truth.Truth.assertWithMessage
import org.junit.Test
import kotlin.random.Random

/**
 * The area chart's curve never leaves the range of the points it joins
 * (DESIGN_SYSTEM_PLAN §4.5, step F6). A plain spline would: it would dip below
 * a quiet day, or below zero, and peak above a big one, drawing money that was
 * never spent. Each segment is sampled finely and every sample must lie
 * between its two ends.
 */
class LfMonotoneCurveTest {

    @Test
    fun aQuietDayBesideABigOne_neverDipsOrPeaksPastThem() {
        // The shape that makes a plain spline overshoot: flat, one spike, flat.
        assertStaysInRange(floatArrayOf(0f, 0f, 0f, 240f, 0f, 0f, 0f))
    }

    @Test
    fun aRunningTotal_onlyRises() {
        val ys = floatArrayOf(0f, 30f, 30f, 30f, 210f, 215f, 400f, 400f, 401f)
        val segments = LfMonotoneCurve.segments(xsFor(ys.size), ys)
        segments.forEachIndexed { index, segment ->
            var previous = segment.y0
            samples().forEach { t ->
                val y = segment.yAt(t)
                assertWithMessage("segment $index falls at t=$t").that(y).isAtLeast(previous - EPSILON)
                previous = y
            }
        }
        assertStaysInRange(ys)
    }

    @Test
    fun arbitrarySeries_stayInRange() {
        val random = Random(SEED)
        repeat(CASES) {
            val ys = FloatArray(random.nextInt(2, 40)) { random.nextFloat() * 10_000f }
            assertStaysInRange(ys)
        }
    }

    @Test
    fun onePoint_hasNoSegments() {
        assertWithMessage("segments").that(LfMonotoneCurve.segments(floatArrayOf(0f), floatArrayOf(5f))).isEmpty()
    }

    private fun assertStaysInRange(ys: FloatArray) {
        val segments = LfMonotoneCurve.segments(xsFor(ys.size), ys)
        segments.forEachIndexed { index, segment ->
            val low = minOf(segment.y0, segment.y1)
            val high = maxOf(segment.y0, segment.y1)
            samples().forEach { t ->
                val y = segment.yAt(t)
                assertWithMessage("segment $index leaves [$low, $high] at t=$t").that(y).isAtLeast(low - EPSILON)
                assertWithMessage("segment $index leaves [$low, $high] at t=$t").that(y).isAtMost(high + EPSILON)
            }
        }
    }

    private fun xsFor(count: Int): FloatArray = FloatArray(count) { it * STEP }

    private fun samples(): List<Float> = (0..SAMPLES).map { it / SAMPLES.toFloat() }

    private companion object {
        const val STEP = 17.5f
        const val SAMPLES = 200
        const val EPSILON = 0.01f
        const val SEED = 20261030
        const val CASES = 200
    }
}
