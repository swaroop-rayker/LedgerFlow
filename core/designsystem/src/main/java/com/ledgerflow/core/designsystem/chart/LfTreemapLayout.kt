package com.ledgerflow.core.designsystem.chart

/**
 * A laid-out treemap tile, in fractions of the container (0..1).
 *
 * Fractions rather than pixels so the layout is a pure function testable off
 * a device — the same argument [LfAxisTicks] makes. The composable multiplies
 * by its own size.
 */
public data class LfTreemapTile(
    val id: String,
    val x: Float,
    val y: Float,
    val width: Float,
    val height: Float,
)

/**
 * Squarified treemap layout (`SPEC.md` §5.6, A3's optional treemap).
 *
 * **The one non-obvious algorithm in the catalogue**, which is why
 * `docs/DATAVIZ-PLAN.md` §7.2 named it as the first credible reason to reopen
 * ADR-0005. It is here rather than in a dependency because it is ninety lines
 * and a chart library would have brought a second rendering system with it.
 *
 * **Squarified, not slice-and-dice.** The naive layout alternates horizontal
 * and vertical splits, which produces correct areas and unreadable slivers: a
 * category worth 2% becomes a one-pixel ribbon down the side that cannot be
 * labelled or tapped. Squarifying (Bruls, Huizing & van Wijk) packs each row
 * until adding another tile would make the row's aspect ratios worse, which
 * keeps tiles near-square and therefore legible.
 *
 * **Areas are exactly proportional.** That is the property the whole chart
 * exists for, so it is asserted rather than assumed: a treemap whose areas
 * drift is a picture that lies about money.
 *
 * Values are money in minor units (Law 3); the fractions this returns are chart
 * coordinates and legitimately real-valued.
 */
public object LfTreemapLayout {

    /**
     * @param values id-to-amount, any order. Non-positive values are dropped —
     *   a zero-area tile cannot be seen or tapped, and drawing one would put an
     *   invisible target under the user's finger.
     */
    public fun layout(values: List<Pair<String, Long>>): List<LfTreemapTile> {
        val items = values.filter { it.second > 0L }.sortedByDescending { it.second }
        if (items.isEmpty()) return emptyList()

        val tiles = mutableListOf<LfTreemapTile>()
        var frame = Frame(0.0, 0.0, 1.0, 1.0)
        // **Scaled against what is *left*, not the original total.** Each row
        // consumes part of the frame, so the items after it map onto the
        // smaller frame that remains. Scaling by the global total instead makes
        // every row after the first too small, the areas stop being
        // proportional, and the tiles no longer fill the square -- which is
        // what `everyTileAreaIsProportionalToItsValue` caught.
        var remaining = items.sumOf { it.second }.toDouble()
        var index = 0

        while (index < items.size) {
            val row = mutableListOf<Pair<String, Long>>()
            var best = Double.MAX_VALUE

            // Grow the row while it improves the worst aspect ratio in it.
            while (index < items.size) {
                val candidate = row + items[index]
                val ratio = worstAspect(candidate, frame, remaining)
                if (row.isNotEmpty() && ratio > best) break
                row += items[index]
                best = ratio
                index++
            }

            frame = placeRow(row, frame, remaining, tiles)
            remaining -= row.sumOf { it.second }.toDouble()
        }

        return tiles
    }

    private data class Frame(val x: Double, val y: Double, val width: Double, val height: Double) {
        val area: Double get() = width * height
        val shortSide: Double get() = minOf(width, height)
        val isWide: Boolean get() = width >= height
    }

    /**
     * The worst aspect ratio in a row, laid along the frame's short side.
     *
     * This is the quantity squarifying minimises. `MAX_VALUE` for a degenerate
     * frame, so an impossible row is never preferred.
     */
    private fun worstAspect(
        row: List<Pair<String, Long>>,
        frame: Frame,
        remaining: Double,
    ): Double {
        if (row.isEmpty() || remaining <= 0.0) return Double.MAX_VALUE
        val side = frame.shortSide
        if (side <= 0.0) return Double.MAX_VALUE

        val scale = frame.area / remaining
        val rowArea = row.sumOf { it.second }.toDouble() * scale
        if (rowArea <= 0.0) return Double.MAX_VALUE

        val maxArea = row.maxOf { it.second }.toDouble() * scale
        val minArea = row.minOf { it.second }.toDouble() * scale
        val sideSquared = side * side
        val rowAreaSquared = rowArea * rowArea

        return maxOf(
            sideSquared * maxArea / rowAreaSquared,
            rowAreaSquared / (sideSquared * minArea),
        )
    }

    /** Lays [row] along the frame's short side and returns what is left. */
    private fun placeRow(
        row: List<Pair<String, Long>>,
        frame: Frame,
        remaining: Double,
        into: MutableList<LfTreemapTile>,
    ): Frame {
        if (row.isEmpty() || remaining <= 0.0) return frame
        val scale = frame.area / remaining
        val rowArea = row.sumOf { it.second }.toDouble() * scale
        if (rowArea <= 0.0) return frame

        return if (frame.isWide) {
            val rowWidth = rowArea / frame.height
            var y = frame.y
            row.forEach { (id, value) ->
                val height = (value.toDouble() * scale) / rowWidth
                into += tile(id, frame.x, y, rowWidth, height)
                y += height
            }
            Frame(frame.x + rowWidth, frame.y, frame.width - rowWidth, frame.height)
        } else {
            val rowHeight = rowArea / frame.width
            var x = frame.x
            row.forEach { (id, value) ->
                val width = (value.toDouble() * scale) / rowHeight
                into += tile(id, x, frame.y, width, rowHeight)
                x += width
            }
            Frame(frame.x, frame.y + rowHeight, frame.width, frame.height - rowHeight)
        }
    }

    private fun tile(id: String, x: Double, y: Double, w: Double, h: Double) = LfTreemapTile(
        id = id,
        x = x.toFloat(),
        y = y.toFloat(),
        width = w.toFloat(),
        height = h.toFloat(),
    )
}
