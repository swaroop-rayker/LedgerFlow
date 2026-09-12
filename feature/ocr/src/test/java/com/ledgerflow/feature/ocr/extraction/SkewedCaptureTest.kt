package com.ledgerflow.feature.ocr.extraction

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import com.ledgerflow.core.model.LineItemKind
import com.ledgerflow.core.model.Money
import com.ledgerflow.feature.ocr.recognition.RecognizedElement
import com.ledgerflow.feature.ocr.recognition.RecognizedPage
import kotlin.math.tan
import org.junit.Test

/**
 * A receipt photographed crooked, which is the ordinary case.
 *
 * The synthetic pages elsewhere in this package are perfectly square, which no
 * hand-held capture ever is. Rows on a photographed bill **slope**, and the
 * drift across a wide receipt exceeds the band tolerance long before it
 * reaches the amount column — so the row splits and the item loses its price.
 *
 * The property asserted throughout is **invariance**: a crooked page must
 * produce the same rows, the same items and the same total as the straight
 * one. That is a stronger claim than "it still works", and it is checkable
 * without a single pixel of image processing.
 */
class SkewedCaptureTest {

    private fun slopeOf(degrees: Double): Float = tan(Math.toRadians(degrees)).toFloat()

    private fun itemsOf(page: RecognizedPage) =
        ReceiptExtractor.extract(page).lines
            .filter { it.kind == LineItemKind.ITEM }
            .map { it.name }

    private val straightItems = itemsOf(ReceiptFixtures.ordinaryBill())

    /**
     * The headline property, across the range a hand-held capture produces.
     *
     * Two degrees is a careful shot; five is ordinary; eight is a phone held
     * one-handed over a table. All three must read identically.
     */
    @Test
    fun aCrookedPage_readsTheSameAsAStraightOne() {
        listOf(2.0, 5.0, 8.0, -5.0).forEach { degrees ->
            val page = ReceiptFixtures.sheared(ReceiptFixtures.ordinaryBill(), slopeOf(degrees))
            val extracted = ReceiptExtractor.extract(page)

            assertWithMessage("%s degrees: items", degrees)
                .that(extracted.lines.filter { it.kind == LineItemKind.ITEM }.map { it.name })
                .isEqualTo(straightItems)
            assertWithMessage("%s degrees: total", degrees)
                .that(extracted.amount)
                .isEqualTo(Money(69_446L))
        }
    }

    /**
     * The row count is the thing that used to collapse.
     *
     * A split row is not a subtle degradation — it produces a row holding a
     * name and no amount, and a second holding an amount and no name. Both
     * then classify wrongly.
     */
    @Test
    fun aCrookedPage_producesTheSameNumberOfRows() {
        val straight = ReceiptGeometry.rows(ReceiptFixtures.ordinaryBill()).size

        listOf(2.0, 5.0, 8.0).forEach { degrees ->
            val page = ReceiptFixtures.sheared(ReceiptFixtures.ordinaryBill(), slopeOf(degrees))

            assertWithMessage("%s degrees", degrees)
                .that(ReceiptGeometry.rows(page).size)
                .isEqualTo(straight)
        }
    }

    /** And the quantities survive, which means the columns still line up. */
    @Test
    fun aCrookedPage_keepsItsPrintedQuantities() {
        val page = ReceiptFixtures.sheared(ReceiptFixtures.ordinaryBill(), slopeOf(6.0))

        val dal = ReceiptExtractor.extract(page).lines.single { it.name == "TOOR DAL 1KG" }

        assertThat(dal.quantityMilli).isEqualTo(2_000L)
        assertThat(dal.unitPrice).isEqualTo(Money(16_500L))
    }

    /** A GST invoice is wider and longer, so it has further to drift. */
    @Test
    fun aCrookedGstInvoice_readsTheSameAsAStraightOne() {
        val straight = ReceiptExtractor.extract(ReceiptFixtures.gstTaxInvoice())
        val crooked = ReceiptExtractor.extract(
            ReceiptFixtures.sheared(ReceiptFixtures.gstTaxInvoice(), slopeOf(5.0)),
        )

        assertThat(crooked.lines.filter { it.kind == LineItemKind.ITEM }.map { it.name })
            .isEqualTo(straight.lines.filter { it.kind == LineItemKind.ITEM }.map { it.name })
        assertThat(crooked.amount).isEqualTo(straight.amount)
    }

    // ── The estimator itself ────────────────────────────────────────────────

    /**
     * A square page must be read as square.
     *
     * The direction that matters more than accuracy: inventing a rotation
     * that is not there applies a correction that *creates* drift, which is
     * strictly worse than doing nothing.
     */
    @Test
    fun aSquarePage_estimatesNoSkew() {
        val page = ReceiptFixtures.ordinaryBill()
        val scale = ReceiptGeometry.medianHeight(page.elements)

        assertThat(ReceiptGeometry.estimateSkew(page.elements, scale)).isEqualTo(0f)
    }

    @Test
    fun theEstimatorRecoversTheAngleItWasGiven() {
        listOf(2.0, 5.0, 8.0, -6.0).forEach { degrees ->
            val expected = slopeOf(degrees)
            val page = ReceiptFixtures.sheared(ReceiptFixtures.ordinaryBill(), expected)
            val scale = ReceiptGeometry.medianHeight(page.elements)

            assertWithMessage("%s degrees", degrees)
                .that(ReceiptGeometry.estimateSkew(page.elements, scale))
                .isWithin(TOLERANCE)
                .of(expected)
        }
    }

    /**
     * Too little evidence means no correction, not a guess.
     *
     * A page with a *handful* of adjacent runs, not zero — the first version
     * of this test used a single word, which yields no pairs at all and so
     * passed whether the guard existed or not. Three steeply-sloped pairs are
     * exactly the case the guard is for: enough to compute a slope, nowhere
     * near enough to believe one.
     */
    @Test
    fun aPageWithTooFewAdjacentRuns_estimatesNoSkew() {
        // Exactly three *qualifying* pairs — one line of four words, using the
        // tall geometry that keeps a steep pair inside the overlap rule. The
        // first two versions of this test used runs that did not qualify at
        // all, so `slopes` was empty and the guard and its absence returned
        // the same zero. Three is one short of the floor, which is the only
        // arrangement that tells them apart.
        val height = 120f
        val wordWidth = 80f
        val gap = 10f
        val steep = 0.5f

        val sparse = RecognizedPage(
            (0 until 4).map { word ->
                val left = 40f + word * (wordWidth + gap)
                val shift = steep * (left + wordWidth / 2f)
                RecognizedElement("WORD", left, shift, left + wordWidth, shift + height)
            },
        )

        val scale = ReceiptGeometry.medianHeight(sparse.elements)
        assertThat(ReceiptGeometry.estimateSkew(sparse.elements, scale)).isEqualTo(0f)
    }

    /**
     * The estimate is a **median**, so junk pairs cannot move it.
     *
     * This is the background-texture case: a receipt photographed on woven
     * cloth produces spurious runs, and the real one in the corpus store came
     * back with 195 runs for a bill of about 60 lines. A *mean* would let that
     * noise drag the whole page's correction.
     *
     * The first version of this test added two stray runs and proved nothing —
     * two junk pairs cannot move a mean either, so swapping the median for a
     * mean left it green. Twenty is what a textured background actually looks
     * like, and it is what makes the choice of statistic testable.
     */
    @Test
    fun textureRuns_doNotMoveTheEstimate() {
        val degrees = 5.0
        val clean = ReceiptFixtures.sheared(ReceiptFixtures.ordinaryBill(), slopeOf(degrees))

        // Pairs of adjacent specks at wild angles, as cloth weave misread as
        // text would be. Placed left of the receipt so they pair with each
        // other rather than with real words.
        val texture = (0 until 20).flatMap { index ->
            val y = 40f + index * 37f
            listOf(
                RecognizedElement("·", 2f, y, 14f, y + 12f),
                RecognizedElement("·", 16f, y + 11f, 28f, y + 23f),
            )
        }

        val noisy = RecognizedPage(clean.elements + texture)
        val scale = ReceiptGeometry.medianHeight(noisy.elements)

        assertThat(ReceiptGeometry.estimateSkew(noisy.elements, scale))
            .isWithin(TOLERANCE)
            .of(slopeOf(degrees))
    }

    /**
     * A degenerate estimate is bounded.
     *
     * Built from **tall** runs deliberately. On body text the overlap
     * requirement caps what is measurable at around 10° — past that no pair
     * qualifies and the estimator returns zero, so a sheared ordinary bill
     * could never exercise this and the first version of this test passed
     * vacuously at 60°. Large print keeps overlapping at a slope no receipt
     * should have, which is the one case where the clamp is the thing that
     * stops a wild correction.
     */
    @Test
    fun aDegenerateEstimate_isClamped() {
        // Dimensions chosen so the pair actually qualifies: a pair survives
        // the overlap test only while `slope x dx <= (1 - minOverlap) x
        // height`, which at slope 0.5 means the runs must be spaced no wider
        // than they are tall. Body text never is, which is why an ordinary
        // sheared bill cannot reach this code at all.
        val height = 120f
        val wordWidth = 80f
        val gap = 10f
        val steep = 0.5f

        val page = RecognizedPage(
            (0 until 12).flatMap { line ->
                val baseline = line * 400f
                (0 until 4).map { word ->
                    val left = 40f + word * (wordWidth + gap)
                    val shift = steep * (left + wordWidth / 2f)
                    RecognizedElement(
                        text = "WORD",
                        left = left,
                        top = baseline + shift,
                        right = left + wordWidth,
                        bottom = baseline + shift + height,
                    )
                }
            },
        )

        val scale = ReceiptGeometry.medianHeight(page.elements)
        val estimate = ReceiptGeometry.estimateSkew(page.elements, scale)

        // It measured something — otherwise the clamp is untested, which is
        // how the first version of this passed at 60 degrees while the
        // estimator was quietly returning zero.
        assertThat(estimate).isGreaterThan(0f)
        assertThat(estimate).isEqualTo(MAX_SLOPE)
    }

    // ── Curl, which the linear correction cannot fully undo ─────────────────

    /**
     * A gently curled roll still reads, and the limit is stated rather than
     * assumed.
     *
     * One slope cannot describe a curve, so the correction removes the
     * dominant linear term and the band tolerance absorbs what is left. This
     * pins how much is left at a sag the band can still take — and it is the
     * measurement the corpus's "curled or crumpled sheet" fixture will
     * eventually replace with a real one.
     */
    @Test
    fun aGentlyCurledPage_stillReads() {
        val page = ReceiptFixtures.curled(ReceiptFixtures.ordinaryBill(), depth = 4f)

        val extracted = ReceiptExtractor.extract(page)

        assertThat(extracted.lines.filter { it.kind == LineItemKind.ITEM }.map { it.name })
            .isEqualTo(straightItems)
        assertThat(extracted.amount).isEqualTo(Money(69_446L))
    }

    private companion object {
        const val TOLERANCE = 0.01f
        const val MAX_SLOPE = 0.36f
    }
}
