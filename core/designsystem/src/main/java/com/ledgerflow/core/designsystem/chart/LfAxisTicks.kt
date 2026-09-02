package com.ledgerflow.core.designsystem.chart

/**
 * Axis tick selection (ADR-0005).
 *
 * **A pure function, deliberately.** It is the one piece of the chart layer with
 * a correct answer that does not depend on how anything looks, so it is
 * unit-tested on the JVM rather than inspected in a screenshot. Ticks at
 * 0 / 117 / 234 are wrong in a way no golden diff will ever call — the image
 * looks fine, it is the *numbers* that are unreadable — which is exactly the
 * kind of defect a screenshot gate cannot see.
 *
 * **Long arithmetic throughout.** These are money values in minor units (Law 3)
 * and they stay `Long` from input to output; the only real number in the file is
 * [fractionOf], which converts a value to a chart *coordinate* and is
 * legitimately real-valued.
 */
public object LfAxisTicks {

    /**
     * "Nice" gridline values from zero up to at least [maxValue].
     *
     * Steps are 1, 2, 5 or 10 times a power of ten, which is what makes the
     * labels round numbers a person can compare at a glance. 2.5 is deliberately
     * excluded: in minor units it is only representable without rounding when
     * the magnitude is at least 10, and a step that is sometimes exact and
     * sometimes not is worse than one that is always exact.
     *
     * Always starts at zero. A money axis that does not is a truncated axis, and
     * a truncated axis exaggerates differences — a bar twice as tall as its
     * neighbour for a difference of four percent. `SPEC.md` §9.1's honesty about
     * figures applies to their geometry too.
     *
     * @param maxValue the largest value the axis must contain. Zero or negative
     *   yields a single zero tick — an empty chart shows an axis, not nothing.
     * @param targetCount roughly how many gridlines are wanted. The result may
     *   hold one more or one fewer, because a round step matters more than an
     *   exact count.
     */
    public fun niceTicks(maxValue: Long, targetCount: Int = DEFAULT_TARGET): List<Long> {
        if (maxValue <= 0L) return listOf(0L)
        val target = targetCount.coerceAtLeast(1)

        val step = niceStep(maxValue, target)
        val ticks = mutableListOf<Long>()
        var tick = 0L
        while (tick < maxValue) {
            ticks += tick
            tick += step
        }
        ticks += tick
        return ticks
    }

    /**
     * The step between gridlines: 1, 2, 5 or 10 times a power of ten.
     *
     * The raw step rounds *up* rather than to nearest, because rounding down
     * produces more gridlines than asked for and a crowded axis is the failure
     * mode that shows up first at font scale 2.0.
     */
    internal fun niceStep(maxValue: Long, targetCount: Int): Long {
        val raw = (maxValue + targetCount - 1) / targetCount
        if (raw <= 1L) return 1L

        var magnitude = 1L
        while (magnitude <= raw / DECADE) magnitude *= DECADE

        // Compared against `raw` itself, not against `raw / magnitude`.
        // Integer division truncates, so 5_850 over a magnitude of 1_000 gives
        // a residual of 5 and would pick a step of 5_000 — *below* the raw
        // step, which yields more gridlines than were asked for. Measured: a
        // target of 4 produced six. Comparing the undivided value rounds up as
        // intended, to 10_000 and four gridlines.
        val nice = when {
            raw <= magnitude -> STEP_ONE
            raw <= STEP_TWO * magnitude -> STEP_TWO
            raw <= STEP_FIVE * magnitude -> STEP_FIVE
            else -> DECADE
        }
        return nice * magnitude
    }

    /**
     * Where a value sits between zero and the axis top, as a fraction.
     *
     * The one real-valued function here, and the reason the distinction is worth
     * stating: this is a chart coordinate, not money. `axisMax` of zero yields
     * zero rather than dividing.
     */
    public fun fractionOf(value: Long, axisMax: Long): Float =
        if (axisMax <= 0L) 0f else (value.toDouble() / axisMax.toDouble()).toFloat().coerceIn(0f, 1f)

    /**
     * How many labels fit without overlapping, given the space each needs.
     *
     * Called with a *measured* label width rather than an assumed one, so the
     * answer changes with the font scale instead of being right at 1.0 and
     * broken at 2.0. Returns at least two — an axis with one label is not an
     * axis — and never more than [count].
     */
    public fun labelsThatFit(count: Int, availablePx: Float, labelWidthPx: Float): Int {
        if (count <= 2) return count.coerceAtLeast(0)
        if (labelWidthPx <= 0f || availablePx <= 0f) return count
        val fits = (availablePx / (labelWidthPx * LABEL_BREATHING_ROOM)).toInt()
        return fits.coerceIn(2, count)
    }

    /**
     * Which indices to label when not all of them fit.
     *
     * Always keeps the first and last: those are the two a reader uses to
     * orient, and dropping either leaves a chart whose range is unstated.
     */
    public fun labelledIndices(count: Int, allowed: Int): Set<Int> {
        if (count <= 0) return emptySet()
        if (allowed >= count) return (0 until count).toSet()
        if (allowed <= 1) return setOf(0)

        val stride = (count - 1).toDouble() / (allowed - 1).toDouble()
        return buildSet {
            for (i in 0 until allowed) add(Math.round(i * stride).toInt().coerceIn(0, count - 1))
            add(0)
            add(count - 1)
        }
    }

    private const val DEFAULT_TARGET = 4

    /**
     * The nice-number ladder: a step is one of these times a power of ten.
     *
     * 2.5 is deliberately absent. In minor units it is only exact once the
     * magnitude reaches ten, and a step that is sometimes exact and sometimes
     * rounded is worse than one that is always exact.
     */
    private const val STEP_ONE = 1L
    private const val STEP_TWO = 2L
    private const val STEP_FIVE = 5L
    private const val DECADE = 10L

    /** Labels need a gap between them, not merely non-overlap. */
    private const val LABEL_BREATHING_ROOM = 1.35f
}
