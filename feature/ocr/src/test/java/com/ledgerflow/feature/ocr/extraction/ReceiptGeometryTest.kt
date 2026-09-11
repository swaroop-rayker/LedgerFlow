package com.ledgerflow.feature.ocr.extraction

import com.google.common.truth.Truth.assertThat
import com.ledgerflow.feature.ocr.extraction.ReceiptFixtures.AMOUNT_X
import com.ledgerflow.feature.ocr.extraction.ReceiptFixtures.GLYPH
import com.ledgerflow.feature.ocr.extraction.ReceiptFixtures.LEFT
import com.ledgerflow.feature.ocr.extraction.ReceiptFixtures.amount
import com.ledgerflow.feature.ocr.extraction.ReceiptFixtures.page
import com.ledgerflow.feature.ocr.extraction.ReceiptFixtures.row
import com.ledgerflow.feature.ocr.recognition.RecognizedElement
import com.ledgerflow.feature.ocr.recognition.RecognizedPage
import org.junit.Test

/**
 * Step 6 (line reconstruction) and step 7a (column gutters).
 *
 * These two rules carry the whole pipeline: everything downstream reads rows
 * and cells, so a band that merges two printed lines or a gutter that swallows
 * the amount column is not a degraded reading, it is a wrong one.
 */
class ReceiptGeometryTest {

    @Test
    fun rows_areOnePerPrintedLine_topToBottom() {
        val rows = ReceiptGeometry.rows(ReceiptFixtures.ordinaryBill())

        assertThat(rows).hasSize(16)
        assertThat(rows.first().text).isEqualTo("SRI LAKSHMI STORES")
        assertThat(rows.last().text).isEqualTo("THANK YOU VISIT AGAIN")
    }

    /**
     * The gap the whole design turns on.
     *
     * A name on the left and a price on the right are one printed line and two
     * columns. ML Kit's own line grouping merges them; the banding here has to
     * keep them on one row *and* the gutter has to split them into two cells.
     */
    @Test
    fun aNameAndItsPrice_areOneRow_andTwoCells() {
        val page = page(row(0, LEFT to "TOMATO 1KG", amount("40.00")))

        val rows = ReceiptGeometry.rows(page)
        assertThat(rows).hasSize(1)

        val cells = ReceiptGeometry.cells(rows.single(), GLYPH)
        assertThat(cells.map { it.text }).containsExactly("TOMATO 1KG", "40.00").inOrder()
    }

    /** A space between words is not a column boundary. */
    @Test
    fun wordsWithinAName_stayInOneCell() {
        val page = page(row(0, LEFT to "TOOR DAL 1KG BRAND"))

        val cells = ReceiptGeometry.cells(ReceiptGeometry.rows(page).single(), GLYPH)

        assertThat(cells).hasSize(1)
        assertThat(cells.single().text).isEqualTo("TOOR DAL 1KG BRAND")
    }

    /**
     * Adjacent printed lines must not merge at the minimum leading the band
     * fraction is derived from.
     *
     * `ROW_BAND_FRACTION` is 0.6 because rows are ≥ 1.2 glyph heights apart.
     * This is that derivation as an assertion: at exactly 1.2× the two rows
     * still separate, so the constant has a margin rather than sitting on the
     * boundary by luck.
     */
    @Test
    fun rowsAtMinimumLeading_doNotMerge() {
        val pitch = GLYPH * 1.2f
        val page = RecognizedPage(
            listOf(
                RecognizedElement("TOMATO", LEFT, 0f, 100f, GLYPH),
                RecognizedElement("40.00", AMOUNT_X, 0f, AMOUNT_X + 60f, GLYPH),
                RecognizedElement("ATTA", LEFT, pitch, 100f, pitch + GLYPH),
                RecognizedElement("285.00", AMOUNT_X, pitch, AMOUNT_X + 72f, pitch + GLYPH),
            ),
        )

        val rows = ReceiptGeometry.rows(page)

        assertThat(rows).hasSize(2)
        assertThat(rows[0].text).isEqualTo("TOMATO 40.00")
        assertThat(rows[1].text).isEqualTo("ATTA 285.00")
    }

    /**
     * A run whose baseline wobbles within its own row still joins it.
     *
     * The other half of the same derivation: a superscript or a tall capital
     * shifts a run's centre by a fraction of a glyph, and a tolerance tight
     * enough to reject that would split rows on ordinary typography.
     */
    @Test
    fun aRunWithABaselineWobble_joinsItsRow() {
        val wobble = GLYPH * 0.4f
        val page = RecognizedPage(
            listOf(
                RecognizedElement("TOMATO", LEFT, 0f, 100f, GLYPH),
                RecognizedElement("40.00", AMOUNT_X, wobble, AMOUNT_X + 60f, wobble + GLYPH),
            ),
        )

        assertThat(ReceiptGeometry.rows(page)).hasSize(1)
    }

    /**
     * The scale is the median, so an outsized header cannot set it.
     *
     * A mean would be dragged up by a shop name printed at 3× and the body's
     * rows would then band together — sixteen rows collapsing into six, which
     * looks like a recogniser failure and is not one.
     */
    @Test
    fun aLargeHeader_doesNotSetThePageScale() {
        val page = page(
            row(0, LEFT to "BIG SHOP NAME", glyph = GLYPH * 3f),
            row(2, LEFT to "TOMATO 1KG", amount("40.00")),
            row(3, LEFT to "ATTA 5KG", amount("285.00")),
        )

        val rows = ReceiptGeometry.rows(page)

        assertThat(ReceiptGeometry.medianHeight(page.elements)).isEqualTo(GLYPH)
        assertThat(rows).hasSize(3)
    }

    @Test
    fun anEmptyPage_hasNoRows() {
        assertThat(ReceiptGeometry.rows(RecognizedPage(emptyList()))).isEmpty()
    }

    /** Blank runs are not rows. A recogniser occasionally returns one. */
    @Test
    fun blankRuns_areDropped() {
        val page = RecognizedPage(
            listOf(
                RecognizedElement("  ", LEFT, 0f, 50f, GLYPH),
                RecognizedElement("TOMATO", LEFT, 0f, 100f, GLYPH),
            ),
        )

        assertThat(ReceiptGeometry.rows(page).single().text).isEqualTo("TOMATO")
    }
}
