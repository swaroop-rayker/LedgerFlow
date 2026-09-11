package com.ledgerflow.feature.ocr.extraction

import com.ledgerflow.feature.ocr.recognition.RecognizedElement
import com.ledgerflow.feature.ocr.recognition.RecognizedPage

/**
 * Steps 6 and 7a of §5.3: turning a bag of glyph runs back into rows and
 * columns.
 *
 * **All of it is arithmetic over [RecognizedElement], so all of it is a JVM
 * test.** That is the property `ReceiptTextRecognizer` returning its own type
 * buys, and it is why nothing here imports `com.google.mlkit` or touches a
 * `Bitmap`.
 *
 * ## Why not ML Kit's own lines
 *
 * ML Kit groups runs into `Text.Line` itself, and it is tempting. It merges
 * across the wide gap between an item name and its price — the gap that *is*
 * the column boundary — and on a two-column thermal receipt it also
 * occasionally joins a name on the left with a price belonging to the row
 * below. Both failures destroy the one signal step 7 needs, so the runs are
 * taken at element grain and re-grouped here, where the rule is visible and
 * testable.
 */
internal object ReceiptGeometry {

    /**
     * How far a run's vertical centre may sit from its row's, as a fraction of
     * the page's median glyph height.
     *
     * **Derived rather than chosen.** Adjacent printed lines are separated by
     * at least their own leading, which on every receipt stock is ≥ 1.2× glyph
     * height, so the centres of two runs on *different* rows differ by ≥ 1.2×.
     * Half of that is the midpoint between "same row" and "next row": 0.6 is
     * the largest tolerance that still cannot merge two adjacent rows at
     * minimum leading, and the smallest that absorbs the half-height baseline
     * wobble a superscript or a tall glyph introduces within one row.
     *
     * It is a threshold, so it is a thing the corpus is entitled to overrule.
     * `ReceiptGeometryTest` pins the reasoning; a real receipt is what would
     * move the number.
     */
    private const val ROW_BAND_FRACTION = 0.6f

    /**
     * How wide a horizontal gap has to be, in median glyph heights, before it
     * separates two *columns* rather than two words.
     *
     * A space between words on a receipt runs about 0.3–0.5× glyph height; the
     * gutter between the item block and the amount column is several times
     * that, because it is what makes the bill readable to a human in the first
     * place. One full glyph height sits in the empty band between them.
     *
     * Erring low would split `TOOR DAL` into two cells, which costs a name.
     * Erring high would swallow the gutter and put the price inside the name,
     * which costs the amount. The first is recoverable by rejoining textual
     * cells — which [cells] does — and the second is not, so when in doubt this
     * splits.
     */
    private const val COLUMN_GAP_FRACTION = 1.0f

    /**
     * The page's rows, top to bottom, each ordered left to right.
     *
     * Greedy banding over runs sorted by vertical centre, with the band's own
     * running mean as the reference. The mean rather than the first member's
     * centre because a row is a dozen runs and its first one may be a tall
     * capital; the mean is what the rest of the row agrees on.
     *
     * **Known limit: skew.** A page photographed at an angle has rows that
     * slope, and a slope carrying a row's right-hand end more than the
     * tolerance below its left-hand end will split that row in two. §5.3 lists
     * deskew as a preprocessing step and `ReceiptImageLoader` does not
     * implement one; a handheld capture of a flat bill is well inside the
     * tolerance, a photograph of a curled roll may not be. This is stated
     * rather than guessed at, and it is one of the things the corpus's "curled
     * or crumpled sheet" fixture exists to measure.
     */
    fun rows(page: RecognizedPage): List<ReceiptRow> {
        val elements = page.elements.filter { it.text.isNotBlank() }
        if (elements.isEmpty()) return emptyList()

        val scale = medianHeight(elements)
        val tolerance = scale * ROW_BAND_FRACTION

        val bands = mutableListOf<MutableList<RecognizedElement>>()
        val centres = mutableListOf<Float>()

        elements.sortedBy { it.centerY }.forEach { element ->
            val last = bands.lastOrNull()
            val bandCentre = centres.lastOrNull()
            if (last != null && bandCentre != null && element.centerY - bandCentre <= tolerance) {
                last += element
                // The running mean, recomputed rather than nudged: a band that
                // gained a tall header glyph should re-centre on what it now
                // holds, not on the order it arrived in.
                centres[centres.lastIndex] = last.sumOf { it.centerY.toDouble() }.toFloat() / last.size
            } else {
                bands += mutableListOf(element)
                centres += element.centerY
            }
        }

        return bands.map { band -> ReceiptRow(band.sortedBy { it.left }) }
    }

    /**
     * A row's runs regrouped into cells at the column gutters.
     *
     * Two runs join the same cell when the empty space between them is narrower
     * than [COLUMN_GAP_FRACTION] of the page's median glyph height. [scale] is
     * the *page's* median rather than the row's, deliberately: a row holding
     * one short word has no reliable scale of its own, and a header printed at
     * double size would otherwise get a gutter twice as wide as the body's.
     */
    fun cells(row: ReceiptRow, scale: Float): List<ReceiptCell> {
        if (row.elements.isEmpty()) return emptyList()
        val gutter = scale * COLUMN_GAP_FRACTION

        val groups = mutableListOf<MutableList<RecognizedElement>>()
        row.elements.forEach { element ->
            val previous = groups.lastOrNull()?.lastOrNull()
            if (previous != null && element.left - previous.right <= gutter) {
                groups.last() += element
            } else {
                groups += mutableListOf(element)
            }
        }

        return groups.map { group ->
            ReceiptCell(
                text = group.joinToString(" ") { it.text }.trim(),
                left = group.minOf { it.left },
                right = group.maxOf { it.right },
            )
        }
    }

    /**
     * The page's scale, as the median run height.
     *
     * Median and not mean: a receipt's header is often printed at two or three
     * times the body size, and a handful of tall runs would drag a mean far
     * enough to make the body's rows merge.
     */
    fun medianHeight(elements: List<RecognizedElement>): Float {
        val heights = elements.map { it.bottom - it.top }.filter { it > 0f }.sorted()
        if (heights.isEmpty()) return 1f
        return heights[heights.size / 2]
    }
}

/**
 * One reconstructed printed line, its runs ordered left to right.
 *
 * The runs are kept rather than only their joined text, because steps 7 and 9
 * both need geometry the string has thrown away — where the amount column
 * starts, and how tall the shop's name is printed relative to its address.
 */
internal data class ReceiptRow(
    val elements: List<RecognizedElement>,
) {
    val text: String get() = elements.joinToString(" ") { it.text }.trim()

    val top: Float get() = elements.minOf { it.top }

    val bottom: Float get() = elements.maxOf { it.bottom }

    val left: Float get() = elements.minOf { it.left }

    val right: Float get() = elements.maxOf { it.right }

    /** The row's own median glyph height — step 9's signal for a shop name. */
    val height: Float get() = ReceiptGeometry.medianHeight(elements)
}

/** One column's worth of a row: the runs between two gutters, joined. */
internal data class ReceiptCell(
    val text: String,
    val left: Float,
    val right: Float,
)
