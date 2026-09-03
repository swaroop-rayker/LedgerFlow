package com.ledgerflow.core.designsystem.chart

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * The treemap, tested as geometry (ADR-0005, `docs/DATAVIZ-PLAN.md` §7.2).
 *
 * A screenshot cannot tell a correct treemap from a plausible one: both are
 * boxes. The properties that make it *true* — areas exactly proportional, tiles
 * inside the frame, no overlaps — are arithmetic, so they are asserted here.
 */
class LfTreemapLayoutTest {

    /**
     * **The property the chart exists for.**
     *
     * A treemap whose areas drift is a picture that lies about money. Every
     * tile's area must equal its share of the total, to floating-point
     * tolerance.
     */
    @Test
    fun everyTileAreaIsProportionalToItsValue() {
        val values = listOf(
            "a" to 600_000L,
            "b" to 300_000L,
            "c" to 100_000L,
            "d" to 40_000L,
            "e" to 10_000L,
        )
        val total = values.sumOf { it.second }.toDouble()

        val tiles = LfTreemapLayout.layout(values).associateBy { it.id }

        values.forEach { (id, value) ->
            val tile = tiles.getValue(id)
            val area = (tile.width * tile.height).toDouble()
            assertThat(area).isWithin(TOLERANCE).of(value / total)
        }
    }

    @Test
    fun theTilesFillTheWholeFrame() {
        val tiles = LfTreemapLayout.layout(
            listOf("a" to 500L, "b" to 300L, "c" to 200L),
        )

        val covered = tiles.sumOf { (it.width * it.height).toDouble() }
        assertThat(covered).isWithin(TOLERANCE).of(1.0)
    }

    @Test
    fun everyTileStaysInsideTheUnitSquare() {
        val tiles = LfTreemapLayout.layout(
            (1..17).map { "c$it" to (it * it * 1_000L) },
        )

        tiles.forEach { tile ->
            assertThat(tile.x).isAtLeast(-TOLERANCE.toFloat())
            assertThat(tile.y).isAtLeast(-TOLERANCE.toFloat())
            assertThat(tile.x + tile.width).isAtMost(1f + TOLERANCE.toFloat())
            assertThat(tile.y + tile.height).isAtMost(1f + TOLERANCE.toFloat())
        }
    }

    /** No two tiles may overlap — a treemap that double-counts pixels lies twice. */
    @Test
    fun noTwoTilesOverlap() {
        val tiles = LfTreemapLayout.layout(
            listOf("a" to 400L, "b" to 300L, "c" to 200L, "d" to 60L, "e" to 40L),
        )

        for (i in tiles.indices) {
            for (j in i + 1 until tiles.size) {
                assertThat(overlaps(tiles[i], tiles[j])).isFalse()
            }
        }
    }

    /**
     * **Squarified, not slice-and-dice**, which is the whole reason for the
     * algorithm.
     *
     * A naive alternating layout gives correct areas and unreadable slivers: a
     * small category becomes a one-pixel ribbon nobody can label or tap. Every
     * tile here should be within a reasonable aspect ratio.
     */
    @Test
    fun tilesAreRoughlySquare_ratherThanSlivers() {
        val tiles = LfTreemapLayout.layout(
            listOf("a" to 300L, "b" to 250L, "c" to 200L, "d" to 150L, "e" to 100L),
        )

        tiles.forEach { tile ->
            val ratio = maxOf(tile.width / tile.height, tile.height / tile.width)
            assertThat(ratio).isLessThan(MAX_REASONABLE_ASPECT)
        }
    }

    @Test
    fun tilesComeBackLargestFirst() {
        val tiles = LfTreemapLayout.layout(
            listOf("small" to 100L, "big" to 900L, "middle" to 500L),
        )

        assertThat(tiles.map { it.id }).containsExactly("big", "middle", "small").inOrder()
    }

    /**
     * Zero and negative values are dropped rather than laid out.
     *
     * A zero-area tile cannot be seen or tapped, and drawing one puts an
     * invisible target under the user's finger.
     */
    @Test
    fun nonPositiveValuesAreDropped() {
        val tiles = LfTreemapLayout.layout(
            listOf("a" to 500L, "zero" to 0L, "negative" to -100L, "b" to 500L),
        )

        assertThat(tiles.map { it.id }).containsExactly("a", "b")
        assertThat(tiles.sumOf { (it.width * it.height).toDouble() }).isWithin(TOLERANCE).of(1.0)
    }

    @Test
    fun anEmptyInputLaysOutNothing() {
        assertThat(LfTreemapLayout.layout(emptyList())).isEmpty()
        assertThat(LfTreemapLayout.layout(listOf("a" to 0L))).isEmpty()
    }

    @Test
    fun aSingleValueFillsTheFrame() {
        val tile = LfTreemapLayout.layout(listOf("only" to 1_234L)).single()

        assertThat(tile.width).isWithin(TOLERANCE.toFloat()).of(1f)
        assertThat(tile.height).isWithin(TOLERANCE.toFloat()).of(1f)
    }

    private fun overlaps(a: LfTreemapTile, b: LfTreemapTile): Boolean {
        val gap = TOLERANCE.toFloat()
        return a.x + a.width - gap > b.x && b.x + b.width - gap > a.x &&
            a.y + a.height - gap > b.y && b.y + b.height - gap > a.y
    }

    private companion object {
        const val TOLERANCE = 1e-4
        const val MAX_REASONABLE_ASPECT = 6f
    }
}
