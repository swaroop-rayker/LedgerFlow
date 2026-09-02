package com.ledgerflow.core.designsystem.chart

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * The axis, tested as arithmetic (ADR-0005, `CLAUDE.md` §5).
 *
 * A screenshot gate cannot catch a bad axis: the picture looks like a chart
 * either way, and it is the *numbers* along the edge that are unreadable. So
 * these assertions are about roundness and monotonicity, and they run on the
 * JVM in milliseconds rather than in Robolectric.
 */
class LfAxisTicksTest {

    @Test
    fun ticksAreRoundNumbers_notArbitraryDivisions() {
        // The failure this prevents, stated as a value: 234_00 over four
        // gridlines is 58_50 each, which is a number nobody can read off an
        // axis. The nice step is 100_00.
        val ticks = LfAxisTicks.niceTicks(maxValue = 234_00L, targetCount = 4)

        assertThat(ticks).containsExactly(0L, 100_00L, 200_00L, 300_00L).inOrder()
    }

    @Test
    fun everyTickIsAMultipleOfTheStep() {
        listOf(1L, 7L, 99L, 100L, 1_234L, 45_678L, 9_999_999L).forEach { max ->
            val ticks = LfAxisTicks.niceTicks(max)
            val step = ticks[1] - ticks[0]
            ticks.forEachIndexed { index, tick ->
                assertThat(tick).isEqualTo(step * index)
            }
        }
    }

    @Test
    fun theAxisAlwaysContainsTheLargestValue() {
        listOf(1L, 2L, 9L, 10L, 11L, 999L, 1_000L, 1_001L, 123_456_789L).forEach { max ->
            assertThat(LfAxisTicks.niceTicks(max).last()).isAtLeast(max)
        }
    }

    /**
     * A money axis starts at zero, always.
     *
     * A truncated axis exaggerates: a bar twice the height of its neighbour for
     * a four-percent difference. §9.1's honesty about figures applies to their
     * geometry.
     */
    @Test
    fun theAxisStartsAtZero() {
        listOf(1L, 500L, 123_456L).forEach { max ->
            assertThat(LfAxisTicks.niceTicks(max).first()).isEqualTo(0L)
        }
    }

    @Test
    fun stepsAreOneTwoFiveOrTenTimesAPowerOfTen() {
        val allowed = setOf(1L, 2L, 5L, 10L)
        (1L..2_000L).forEach { max ->
            val step = LfAxisTicks.niceStep(max, targetCount = 4)
            var magnitude = 1L
            while (magnitude <= step / 10L) magnitude *= 10L
            assertThat(step % magnitude).isEqualTo(0L)
            assertThat(step / magnitude).isIn(allowed)
        }
    }

    @Test
    fun anEmptyChartStillHasAnAxis() {
        assertThat(LfAxisTicks.niceTicks(0L)).containsExactly(0L)
        assertThat(LfAxisTicks.niceTicks(-5L)).containsExactly(0L)
    }

    @Test
    fun fractionOf_isBoundedAndHandlesAZeroAxis() {
        assertThat(LfAxisTicks.fractionOf(50L, 100L)).isEqualTo(0.5f)
        assertThat(LfAxisTicks.fractionOf(0L, 100L)).isEqualTo(0f)
        assertThat(LfAxisTicks.fractionOf(150L, 100L)).isEqualTo(1f)
        assertThat(LfAxisTicks.fractionOf(5L, 0L)).isEqualTo(0f)
    }

    /**
     * Label thinning is driven by a measured width, which is what makes it
     * survive font scale 2.0 rather than merely look right at 1.0.
     */
    @Test
    fun labelsThin_asTheMeasuredWidthGrows() {
        val atNormalScale = LfAxisTicks.labelsThatFit(count = 12, availablePx = 1000f, labelWidthPx = 40f)
        val atDoubleScale = LfAxisTicks.labelsThatFit(count = 12, availablePx = 1000f, labelWidthPx = 80f)

        assertThat(atNormalScale).isGreaterThan(atDoubleScale)
        assertThat(atDoubleScale).isAtLeast(2)
    }

    @Test
    fun theFirstAndLastLabelsAreNeverDropped() {
        val indices = LfAxisTicks.labelledIndices(count = 30, allowed = 4)

        assertThat(indices).contains(0)
        assertThat(indices).contains(29)
        // Never more than asked for, plus the two anchors it is allowed to keep.
        assertThat(indices.size).isAtMost(5)
    }

    @Test
    fun labellingIsTotalWhenEverythingFits() {
        assertThat(LfAxisTicks.labelledIndices(count = 5, allowed = 10)).hasSize(5)
    }
}
