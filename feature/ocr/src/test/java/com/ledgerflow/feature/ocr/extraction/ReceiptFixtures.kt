package com.ledgerflow.feature.ocr.extraction

import com.ledgerflow.feature.ocr.recognition.RecognitionScript
import com.ledgerflow.feature.ocr.recognition.RecognizedElement
import com.ledgerflow.feature.ocr.recognition.RecognizedPage

/**
 * Hand-laid receipts, in the coordinates a recogniser would have returned.
 *
 * ## Why these are not the corpus, and must not become it
 *
 * `SPEC.md` §12's gate is measured against **real photographs with
 * hand-transcribed ground truth**, in a private store, written before the
 * extractor reads them (`scripts/guard-corpus-order.sh`). Nothing here counts
 * toward that number and nothing here should ever be added to it: a page this
 * file builds is a page whose geometry the extractor's author chose, so it can
 * only ever confirm that the arithmetic does what its author meant.
 *
 * That is still worth having, and it is a different job. These pin the *rules*
 * — a gutter splits, a band does not merge, `2 × 165.00` has to close — at a
 * grain where a failure names the step that broke. The corpus measures whether
 * those rules describe real paper. A failure here is a logic bug; a failure
 * there is a modelling one, and conflating them is how a pipeline ends up
 * tuned to its own fixtures.
 *
 * ## The layout model
 *
 * A thermal receipt at the sizes `ReceiptImageLoader` produces: glyph height
 * [GLYPH], line pitch [PITCH] (1.5× the glyph, comfortably inside the ≥1.2×
 * that `ROW_BAND_FRACTION` is derived from), a body column at x=[LEFT] and an
 * amount column right-aligned at x=[AMOUNT_X]. Word gaps are a third of a
 * glyph; column gutters are several glyphs. Those two facts are what every
 * rule under test depends on, so they are named here rather than buried in
 * numbers.
 */
internal object ReceiptFixtures {

    const val GLYPH = 20f
    const val PITCH = 30f
    const val LEFT = 40f
    const val QUANTITY_X = 300f
    const val RATE_X = 360f
    const val AMOUNT_X = 560f

    /** A word gap: too narrow to be a column boundary. */
    const val WORD_GAP = GLYPH / 3f

    /** Roughly how wide one character is at [GLYPH]. */
    const val ADVANCE = GLYPH * 0.6f

    /**
     * One printed line, as a list of runs.
     *
     * [columns] are laid out left to right at the x positions given; words
     * inside one column are separated by [WORD_GAP] so they stay one cell,
     * which is the property `ReceiptGeometry.cells` is being asked about.
     */
    fun row(
        lineIndex: Int,
        vararg columns: Pair<Float, String>,
        glyph: Float = GLYPH,
    ): List<RecognizedElement> {
        val top = lineIndex * PITCH
        return columns.flatMap { (x, text) ->
            var cursor = x
            text.split(" ").filter { it.isNotEmpty() }.map { word ->
                val width = word.length * glyph * 0.6f
                val element = RecognizedElement(
                    text = word,
                    left = cursor,
                    top = top,
                    right = cursor + width,
                    bottom = top + glyph,
                    script = RecognitionScript.LATIN,
                )
                cursor += width + WORD_GAP
                element
            }
        }
    }

    /** An amount, right-aligned so its column edge sits at [rightEdge]. */
    fun amount(text: String, rightEdge: Float = AMOUNT_X): Pair<Float, String> =
        (rightEdge - text.length * ADVANCE) to text

    fun page(vararg rows: List<RecognizedElement>): RecognizedPage =
        RecognizedPage(rows.toList().flatten())

    /**
     * The receipt most of these tests read.
     *
     * Deliberately ordinary, and every row is one the classifier has to place:
     * a shop name printed larger than its address, an address that *does*
     * carry digits, a GSTIN, a bill number in its own column (the case where a
     * short identifier would otherwise read as ₹45.21), four items across two
     * column layouts, a subtotal, two tax rows, a discount, a grand total,
     * two tender rows and a pleasantry.
     *
     * The arithmetic closes, which is what lets a reconciliation test assert
     * `Balanced` rather than "some delta":
     *
     * ```
     * items 40.00 + 330.00 + 54.00 + 285.00 = 709.00
     * tax   17.73 + 17.73                   =  35.46
     * disc                                  = -50.00
     * parts                                   694.46  == GRAND TOTAL 694.46
     * ```
     */
    fun ordinaryBill(): RecognizedPage = page(
        row(0, LEFT to "SRI LAKSHMI STORES", glyph = GLYPH * 1.5f),
        row(1, LEFT to "12 MAIN ROAD BENGALURU 560001"),
        row(2, LEFT to "GSTIN 29AAACT2727Q1ZW"),
        row(3, LEFT to "BILL NO", amount("4521")),
        row(4, LEFT to "TOMATO 1KG", amount("40.00")),
        row(5, LEFT to "TOOR DAL 1KG", QUANTITY_X to "2", RATE_X to "165.00", amount("330.00")),
        row(6, LEFT to "MILK 500ML", RATE_X to "27.00", amount("54.00")),
        row(7, LEFT to "ATTA 5KG", amount("285.00")),
        row(8, LEFT to "SUB TOTAL", amount("709.00")),
        row(9, LEFT to "CGST 2.5%", amount("17.73")),
        row(10, LEFT to "SGST 2.5%", amount("17.73")),
        row(11, LEFT to "DISCOUNT", amount("50.00")),
        row(12, LEFT to "GRAND TOTAL", amount("694.46")),
        row(13, LEFT to "CASH", amount("700.00")),
        row(14, LEFT to "CHANGE", amount("5.54")),
        row(15, LEFT to "THANK YOU VISIT AGAIN"),
    )

    /**
     * An Indian **GST tax invoice**, in the shape a real one has.
     *
     * The names and amounts are invented; the *structure* is copied from a
     * real Food Bazaar receipt, and the structure is the whole point — it
     * broke three separate assumptions the synthetic bill above never
     * tested:
     *
     * - **`S GST` / `C GST` rows under every item**, not once at the bottom.
     *   The old rule closed the item block at the first tax row, which on
     *   that receipt lost five of the six products.
     * - **`HSN : 2005   UOM : Pcs`** under each item, which parses as ₹20.05
     *   and was becoming a line item.
     * - **`TOTAL SAVING: 75.00`** at the foot, which matched the TOTAL
     *   keyword and — last match winning — replaced a ₹1,075.46 bill total
     *   with ₹75.00.
     *
     * And the arithmetic is **tax-inclusive**: the four item NET AMTs sum to
     * the printed total on their own, with the GST rows restating tax that is
     * already inside them.
     *
     * ```
     * items 70.00 + 55.00 + 174.00 + 315.00 = 614.00 == TOTAL 614.00
     * gst rows          5.34 + 4.19 + 13.27 = 22.80   (inside the prices)
     * ```
     */
    fun gstTaxInvoice(): RecognizedPage = page(
        row(0, LEFT to "VALUE MART RETAIL LTD", glyph = GLYPH * 1.4f),
        row(1, LEFT to "42 STATION ROAD BENGALURU 560001"),
        row(2, LEFT to "GST TIN 29AADCB1093N1ZE"),
        row(3, LEFT to "ITEM DESC", QUANTITY_X to "QTY", amount("NET AMT")),
        row(4, LEFT to "CRISPS 95G", QUANTITY_X to "2", amount("70.00")),
        row(5, LEFT to "HSN :", QUANTITY_X to "2005", RATE_X to "UOM : Pcs"),
        row(6, LEFT to "S GST 9%", RATE_X to "59.32", amount("5.34")),
        row(7, LEFT to "C GST 9%", RATE_X to "59.32", amount("5.34")),
        row(8, LEFT to "CRISPS 177G", QUANTITY_X to "1", amount("55.00")),
        row(9, LEFT to "HSN :", QUANTITY_X to "2005", RATE_X to "UOM : Pcs"),
        row(10, LEFT to "S GST 9%", RATE_X to "46.62", amount("4.19")),
        row(11, LEFT to "SHOWERGEL 250ML", QUANTITY_X to "1", amount("174.00")),
        row(12, LEFT to "HSN :", QUANTITY_X to "3401", RATE_X to "UOM : Pcs"),
        row(13, LEFT to "S GST 9%", RATE_X to "147.46", amount("13.27")),
        row(14, LEFT to "CLEANER JASMINE 2L", QUANTITY_X to "1", amount("315.00")),
        row(15, LEFT to "SUBTOTAL", amount("614.00")),
        row(16, LEFT to "TOTAL", amount("614.00")),
        row(17, LEFT to "SBI", amount("614.00")),
        row(18, LEFT to "PIECES PURCHASED: 5 DISC ITEMS:", amount("0")),
        row(19, LEFT to "TOTAL SAVING:", amount("75.00")),
    )
}
