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

    /**
     * A **quick-commerce A4 invoice with wrapped description cells** — the
     * structure of the owner's Zepto invoice, with invented products.
     *
     * Every description wraps over several lines in a narrow column and the
     * figures sit on the cell's middle line, so the priced line carries one
     * word of the name or none. The header is spread over three lines and
     * names ten columns. Item 3's quantity and HSN cells are deliberately empty,
 * because
     * on the real page ML Kit dropped that `1`.
     *
     * ```
     * 60.00 + 44.00 + 35.00 = 139.00 == column total == Invoice Value
     * ```
     *
     * Item 2's total prints as `44,00`, a misread the pipeline must refuse, so
     * the bill reads unbalanced by exactly ₹44.00.
     */
    // A page is data, one entry per printed line; splitting it would scatter one invoice.
    @Suppress("LongMethod")
    fun wrappedCellInvoice(): RecognizedPage = page(
        row(0, LEFT to "Seller Name: Quickmart Retail Private Limited", glyph = GLYPH * 1.5f),
        row(1, LEFT to "Plot 4 Industrial Area Hubli - 580020"),
        row(2, LEFT to "GSTIN: 29AAJCG0980D1ZK"),
        row(
            4,
            W_SR to "SR",
            W_DESC to "Item &",
            W_MRP to "Unit",
            W_TAXABLE to "Taxable",
            W_CGST to "CGST",
            W_SGST to "SGST",
            W_TOTAL to "Total",
        ),
        row(5, W_HSN to "HSN", W_QTY to "Qty", W_RATE to "Product"),
        row(
            6,
            W_SR to "No",
            W_DESC to "Description",
            W_MRP to "MRP",
            W_RATE to "Rate",
            W_TAXABLE to "Amt.",
            W_TOTAL to "Amt.",
        ),
        // Item 1: five lines, figures on the third.
        row(8, W_DESC to "Crunchy"),
        row(9, W_DESC to "Masala"),
        row(
            10,
            W_SR to "1",
            W_DESC to "Peanuts |",
            W_MRP to "30.00",
            W_HSN to "20081100",
            W_QTY to "2",
            W_RATE to "28.57",
            W_TAXABLE to "57.14",
            W_CGST to "2.50%",
            W_SGST to "2.50%",
            W_TOTAL to "60.00",
        ),
        row(11, W_DESC to "Snack 1"),
        row(12, W_DESC to "pack (150 g)"),
        // Item 2: three lines, figures on the second -- and its total misread
        // `44,00`, as the real page misread `52.00`. Refused as money (BUG23).
        row(14, W_DESC to "Oat"),
        row(
            15,
            W_SR to "2",
            W_DESC to "Cookies",
            W_MRP to "22.00",
            W_HSN to "19059020",
            W_QTY to "2",
            W_RATE to "20.95",
            W_TAXABLE to "41.90",
            W_CGST to "2.50%",
            W_SGST to "2.50%",
            W_TOTAL to "44,00",
        ),
        row(16, W_DESC to "(75 g)"),
        // Item 3: four lines, figures on the second -- and neither its quantity
        // nor its HSN code read, so the serial number alone says "item".
        row(18, W_DESC to "Instant"),
        row(
            19,
            W_SR to "3",
            W_DESC to "Noodle",
            W_MRP to "35.00",
            W_RATE to "33.33",
            W_TAXABLE to "33.33",
            W_CGST to "2.50%",
            W_SGST to "2.50%",
            W_TOTAL to "35.00",
        ),
        row(20, W_DESC to "Cup 1"),
        row(21, W_DESC to "pack (70 g)"),
        // The column-total line: figures, and no serial, quantity or HSN code.
        row(23, W_TAXABLE to "132.37", W_TOTAL to "139.00"),
        row(25, LEFT to "Item Total", W_TOTAL to "139.00"),
        row(26, LEFT to "Invoice Value", W_TOTAL to "139.00"),
    )

    /**
     * An A4 invoice whose **name straddles the figures** — the structure of the
     * owner's bigbasket invoice, with invented products.
     *
     * Each item is three printed lines: the first half of the name beside the
     * tax rates, the figures (serial, HSN, quantity, prices, total), and the
     * second half of the name beside the tax amounts. One name runs into its
     * HSN code as a single run, one item line prints no serial number, and
     * below the table two summary tables sit side by side — so `Total Invoice
     * value (In words)` shares a line with the left table's `Rs.9.99`.
     *
     * ```
     * 420.00 + 96.50 + 23.45 = 539.95 == column total == Total Invoice value
     * ```
     */
    // A page is data, one entry per printed line; splitting it would scatter one invoice.
    @Suppress("LongMethod")
    fun straddledNameInvoice(): RecognizedPage = page(
        row(0, LEFT to "Original Tax Invoice", glyph = GLYPH * 1.6f),
        row(1, LEFT to "Details of Supplier"),
        row(2, LEFT to "Fresh Aisle Retail Pvt Ltd"),
        row(4, B_CGST to "CGST", B_SGST to "SGST/"),
        row(
            5,
            B_SI to "SI",
            B_DESC to "Item Description",
            B_HSN to "HSN",
            B_QTY to "Quantity",
            B_UNIT to "Unit",
            B_TAXABLE to "Taxable",
            B_CGST to "Rate(%)",
            B_SGST to "Rate(%)",
            B_TOTAL to "TOTAL",
        ),
        row(
            6,
            B_SI to "No.",
            B_HSN to "Code",
            B_UNIT to "Price*",
            B_TAXABLE to "Value",
            B_CGST to "Amount",
            B_SGST to "Amount",
            B_TOTAL to "Value",
        ),
        row(8, B_DESC to "Cold Pressed Sesame Oil 1", B_CGST to "2.50%", B_SGST to "2.50%"),
        row(
            9,
            B_SI to "1",
            B_HSN to "15155091",
            B_QTY to "2",
            B_UNIT to "210.00",
            B_TAXABLE to "400.00",
            B_TOTAL to "420.00",
        ),
        row(10, B_DESC to "Litre Bottle", B_CGST to "10.00", B_SGST to "10.00"),
        row(12, B_DESC to "Whole Wheat Bread04059020", B_CGST to "0.00%", B_SGST to "0.00%"),
        // No serial number and no quantity read: the HSN code alone says item.
        row(13, B_HSN to "19051000", B_UNIT to "96.50", B_TAXABLE to "96.50", B_TOTAL to "96.50"),
        row(14, B_DESC to "400 g", B_CGST to "0.00", B_SGST to "0.00"),
        row(16, B_DESC to "Green Chilli", B_CGST to "0.00%", B_SGST to "0.00%"),
        // Only the quantity survives on this line.
        row(17, B_QTY to "1.07", B_UNIT to "21.92", B_TAXABLE to "23.45", B_TOTAL to "23.45"),
        row(18, B_DESC to "100 g", B_CGST to "0.00", B_SGST to "0.00"),
        row(20, B_TAXABLE to "519.95", B_TOTAL to "539.95"),
        row(22, LEFT to "GST Information", B_CGST to "Transaction ID", B_TOTAL to "Sub Total Rs.539.95"),
        row(23, LEFT to "2.50% Rs.420.00 Rs.9.99", B_CGST to "Total Invoice value (In Figure): Rs.539.95"),
        row(24, LEFT to "0.00% Rs.119.95 Rs.9.99", B_CGST to "Total Invoice value (In words): Rupees Five Hundred"),
    )

    // Column positions for the two invoice layouts. Spaced so a gutter between
    // any two header words is wider than half a glyph, which is the whole of
    // the column rule under test.
    const val W_SR = 40f
    const val W_DESC = 120f
    const val W_MRP = 320f
    const val W_HSN = 420f
    const val W_QTY = 540f
    const val W_RATE = 620f
    const val W_TAXABLE = 740f
    const val W_CGST = 860f
    const val W_SGST = 960f
    const val W_TOTAL = 1060f

    const val B_SI = 40f
    const val B_DESC = 100f
    const val B_HSN = 420f
    const val B_QTY = 540f
    const val B_UNIT = 660f
    const val B_TAXABLE = 780f
    const val B_CGST = 900f
    const val B_SGST = 1020f
    const val B_TOTAL = 1140f

    /**
     * The same page as the recogniser actually read it: one glyph wrong.
     *
     * On the owner's real Food Bazaar invoice ML Kit returned `S 6ST 9%` for
     * `S GST 9%`. Expressed as a **transformation** of an existing page rather
     * than a second hand-laid one, so the two cannot drift apart and the only
     * difference between them is the misread character — which is the whole
     * claim a fuzzy-matching test needs to make. The replacement is
     * length-preserving, so every bounding box is untouched too, and nothing
     * about the geometry can be what makes the test pass.
     */
    fun misread(page: RecognizedPage, from: String, to: String): RecognizedPage {
        require(from.length == to.length) {
            "A substituted glyph preserves length; '$from' -> '$to' would move every box after it."
        }
        return RecognizedPage(
            page.elements.map { if (it.text == from) it.copy(text = to) else it },
        )
    }

    /**
     * The same page, photographed crooked.
     *
     * Shears every run's vertical position by `slope * centerX` — the model of
     * a page rotated by a small angle, which is what a hand-held capture
     * produces. A shear rather than a true rotation because ML Kit returns
     * **axis-aligned** bounding boxes: a rotated glyph comes back as an
     * upright box whose centre has moved, which is precisely a shear of the
     * centres.
     *
     * The boxes keep their width and height, so a test can assert that the
     * *same* rows come out of the crooked page as out of the straight one —
     * which is the property the whole skew correction exists to give.
     */
    fun sheared(page: RecognizedPage, slope: Float): RecognizedPage = RecognizedPage(
        page.elements.map { element ->
            val shift = slope * element.centerX
            element.copy(top = element.top + shift, bottom = element.bottom + shift)
        },
    )

    /**
     * The same page on a curled roll.
     *
     * A quadratic sag about the page's horizontal midpoint: rows bow downward
     * toward both edges, which is what a thermal roll lying on a table does.
     * [depth] is the sag at the edges in pixels.
     *
     * Deliberately *not* something the linear skew correction can undo — it
     * exists to measure the residual that correction leaves behind.
     */
    fun curled(page: RecognizedPage, depth: Float): RecognizedPage {
        val centres = page.elements.map { it.centerX }
        val mid = (centres.minOrNull() ?: 0f).let { lo ->
            val hi = centres.maxOrNull() ?: 0f
            (lo + hi) / 2f
        }
        val halfWidth = ((centres.maxOrNull() ?: 1f) - mid).coerceAtLeast(1f)
        return RecognizedPage(
            page.elements.map { element ->
                val offset = (element.centerX - mid) / halfWidth
                val shift = depth * offset * offset
                element.copy(top = element.top + shift, bottom = element.bottom + shift)
            },
        )
    }
}
