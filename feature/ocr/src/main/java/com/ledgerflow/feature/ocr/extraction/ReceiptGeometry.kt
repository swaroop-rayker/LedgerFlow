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
     * How far apart two runs may sit and still vote on the page's skew.
     *
     * Three glyph heights is comfortably wider than a word gap (0.3–0.5) and
     * comfortably narrower than a column gutter, which is what it has to
     * separate: a pair straddling the gutter between an item name and its
     * price would measure the *row's* slope over a long lever arm, which is
     * exactly the measurement that is unreliable when the row might be curved.
     */
    private const val SKEW_NEIGHBOUR_GAP_FRACTION = 3.0f

    /** Two runs must share this much height to count as one printed line. */
    private const val MIN_SKEW_PAIR_OVERLAP = 0.5f

    /**
     * Below this many pairs the estimate is noise, and zero is the safer
     * answer — an unrotated page read as rotated is worse than a rotated page
     * read as flat, because the correction then invents drift that is not
     * there.
     */
    private const val MIN_SKEW_PAIRS = 4

    /**
     * A last bound on a degenerate estimate.
     *
     * **It rarely fires, and knowing why is the useful part.**
     * [MIN_SKEW_PAIR_OVERLAP] bites first: two runs on one printed line are
     * offset vertically by `slope x dx`, so requiring them to still share half
     * their height caps what is *measurable* at roughly 10° for body text —
     * measured, not reasoned about. Past that no pair qualifies and the
     * estimator honestly returns nothing rather than a wrong number.
     *
     * This bound exists for the case that survives anyway: large print, where
     * tall boxes keep overlapping at a slope no receipt should have. A page
     * that steep wants retaking, and correcting it would move rows further
     * than leaving it alone.
     */
    private const val MAX_SKEW_SLOPE = 0.36f

    /**
     * The page's rows, top to bottom, each ordered left to right.
     *
     * ## Skew is corrected first, and that is the whole change
     *
     * The original version banded on raw `centerY`, which assumes rows are
     * **horizontal**. A receipt photographed by hand is rotated by a few
     * degrees, so a row's right-hand end sits lower than its left-hand end —
     * and on a wide bill the drift across the page exceeds the band tolerance
     * long before it reaches the amount column. The row splits, the amount
     * lands in a row of its own, and the item loses its price.
     *
     * §5.3 lists deskew as an image preprocessing step and nothing implements
     * one. It does not need one: the rotation is recoverable from the
     * recognised boxes themselves, which is cheaper than touching pixels,
     * needs no dependency, and stays JVM-testable — the property the
     * recognizer wrapper exists to protect.
     *
     * So [estimateSkew] reads the dominant text slope off the page and the
     * banding runs on `centerY - slope * centerX`: the coordinate a run
     * *would* have had if the page were square. Nothing is rewritten; the
     * elements keep their real positions for [cells] and for step 9.
     *
     * ## What this does not fix
     *
     * **Rotation past about 10°.** Measured, not assumed: the estimator pairs
     * runs that still overlap vertically, and on body text that stops being
     * true somewhere around 10°. Beyond it no pair qualifies, [estimateSkew]
     * returns zero, and the page is banded as though it were square — which
     * is the honest failure, not a silent wrong answer. A capture guide in the
     * viewfinder is the fix for that range, not more arithmetic here.
     *
     * **Curl.** A thermal roll bends, so its rows are curves rather than
     * straight lines, and one slope cannot describe a curve. Correcting the
     * dominant linear term leaves a residual that the band tolerance absorbs
     * while it stays small — which for a roll flattened on a table it does,
     * and for one photographed mid-curl it may not. That is the corpus's
     * "curled or crumpled sheet" fixture to measure, not this comment to
     * guess.
     *
     * **Perspective.** A page shot at an angle has rows that also *converge*.
     * Undoing that needs a four-point warp, which needs the page's corners —
     * image processing, done before recognition by `OpenCvPageCorrector`
     * (ADR-0024) rather than here in arithmetic.
     */
    fun rows(page: RecognizedPage): List<ReceiptRow> {
        val elements = contentElements(page)
        if (elements.isEmpty()) return emptyList()

        val scale = medianHeight(elements)
        val tolerance = scale * ROW_BAND_FRACTION
        val slope = estimateSkew(elements, scale)

        val bands = mutableListOf<MutableList<RecognizedElement>>()
        val centres = mutableListOf<Float>()

        elements.sortedBy { it.squaredY(slope) }.forEach { element ->
            val last = bands.lastOrNull()
            val bandCentre = centres.lastOrNull()
            if (last != null && bandCentre != null &&
                element.squaredY(slope) - bandCentre <= tolerance
            ) {
                last += element
                // The running mean, recomputed rather than nudged: a band that
                // gained a tall header glyph should re-centre on what it now
                // holds, not on the order it arrived in.
                centres[centres.lastIndex] =
                    last.sumOf { it.squaredY(slope).toDouble() }.toFloat() / last.size
            } else {
                bands += mutableListOf(element)
                centres += element.squaredY(slope)
            }
        }

        return bands.map { band -> ReceiptRow(band.sortedBy { it.left }) }
    }

    /**
     * Whether a run could contribute a name or an amount.
     *
     * **A receipt is rarely photographed on a clean surface**, and a recogniser
     * pointed at woven cloth finds "text" in the weave: the real bill in the
     * corpus store came back with 195 runs for about 60 printed lines. Measured
     * rather than assumed, that surplus costs two things — item names acquire
     * leading specks (`· ~ TOMATO 1KG`), and once the specks outnumber the
     * print they take the page scale with them.
     *
     * The rule needs no threshold: a run with neither a letter nor a digit
     * cannot be part of a name and cannot be an amount. That removes weave
     * speckle and the `-----` rules receipts are full of, and it is
     * Unicode-aware, so Devanagari counts as letters.
     *
     * What it costs: a lone currency symbol in its own cell is dropped, so a
     * bill that prints `₹` detached from its figure loses the currency marker.
     * `amount_minor` is always base currency (D-02) and the symbol is a hint,
     * so that is the cheap side of the trade.
     */
    fun contentElements(page: RecognizedPage): List<RecognizedElement> =
        page.elements.filter { it.height > 0f && it.text.any(Char::isLetterOrDigit) }

    /** Where a run would sit vertically if the page were square. */
    private fun RecognizedElement.squaredY(slope: Float): Float = centerY - slope * centerX

    /**
     * The page's dominant text slope, in y-per-x.
     *
     * **Measured from the runs, never assumed.** Each pair of horizontally
     * adjacent runs that plainly share a printed line — they overlap
     * vertically and sit within a word-gap of each other — contributes one
     * slope, and the answer is their **median**.
     *
     * The median rather than a mean, and adjacent pairs rather than row
     * endpoints, because both choices are about robustness on exactly the
     * input this exists for: a messy photograph produces spurious runs from
     * background texture, and a receipt has wide column gutters that no pair
     * should be allowed to straddle. A mean would be dragged by a handful of
     * junk pairs; endpoints would measure the gutter instead of the text.
     *
     * Returns zero when there is not enough evidence — fewer than
     * [MIN_SKEW_PAIRS] usable pairs — because a page with almost no adjacent
     * text is one where a slope estimate is a guess, and guessing a rotation
     * is worse than assuming none.
     */
    fun estimateSkew(elements: List<RecognizedElement>, scale: Float): Float {
        val byLeft = elements.sortedBy { it.left }
        val neighbourGap = scale * SKEW_NEIGHBOUR_GAP_FRACTION
        val slopes = mutableListOf<Float>()

        byLeft.forEachIndexed { index, left ->
            // The nearest qualifying neighbour only — a run votes once.
            byLeft.asSequence()
                .drop(index + 1)
                // Sorted by `left`, so once the gap is too wide it stays too
                // wide; `takeWhile` ends the scan rather than walking the page.
                .takeWhile { right -> right.left - left.right <= neighbourGap }
                .firstOrNull { right ->
                    right.centerX > left.centerX &&
                        left.verticalOverlapWith(right) >= MIN_SKEW_PAIR_OVERLAP
                }
                ?.let { right ->
                    slopes += (right.centerY - left.centerY) / (right.centerX - left.centerX)
                }
        }

        if (slopes.size < MIN_SKEW_PAIRS) return 0f
        val median = slopes.sorted()[slopes.size / 2]
        // A receipt photographed past this is not a skew problem, it is a
        // retake. Clamping stops a pathological estimate making things worse
        // than leaving the page alone would.
        return median.coerceIn(-MAX_SKEW_SLOPE, MAX_SKEW_SLOPE)
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
     * The page's scale, as the median height of the runs that carry content.
     *
     * Median and not mean: a receipt's header is often printed at two or three
     * times the body size, and a handful of tall runs would drag a mean far
     * enough to make the body's rows merge.
     *
     * **Callers must pass [contentElements], not the raw page.** A median
     * counts runs, so it holds only while real print outnumbers noise — and on
     * a textured background it does not. Measured: sixty rows of weave speckle
     * against fifty words of receipt moved the scale from 20 px to 9, which
     * then widens every row band and every column gutter by the same factor.
     *
     * Area weighting was tried instead and traded the fault rather than fixing
     * it: weighting by ink makes a 3x header dominate a short bill, which is
     * the failure `aLargeHeader_doesNotSetThePageScale` exists to prevent.
     * Dropping the speckle before counting keeps both properties.
     *
     * **The residual risk is stated rather than engineered against:** noise
     * that the recogniser reads as letters or digits survives the filter and
     * would still move a count median if it dominated. Whether that happens on
     * real paper is a corpus question, and tuning for it without one is how a
     * threshold gets chosen to fit an imagined page.
     */
    fun medianHeight(elements: List<RecognizedElement>): Float {
        val heights = elements.map { it.height }.filter { it > 0f }.sorted()
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
