package com.ledgerflow.feature.ocr.extraction

import com.ledgerflow.core.model.Money
import com.ledgerflow.core.model.Quantity
import com.ledgerflow.feature.ocr.recognition.RecognizedElement

/**
 * The item block of an **A4 GST tax invoice**, read as a table.
 *
 * ## Why the line-by-line reading cannot do this
 *
 * Every other step in this package assumes a printed line is a bill line: a
 * name on the left, an amount on the right, one row. A thermal slip obeys that.
 * The quick-commerce invoices the owner receives as PDFs do not, and on both of
 * the first two the line-by-line reading found no real items at all:
 *
 * - **Zepto** prints each description *wrapped* over four to nine lines in a
 *   narrow column, with the figures vertically centred beside it. The line that
 *   carries the price carries one word of the name — or none — so it read as
 *   NOISE, and every product was lost.
 * - **bigbasket** prints the first half of the name on the line above the
 *   figures and the second half on the line below, beside the per-column tax
 *   amounts. The priced line started with a serial number or an HSN code, which
 *   came out as the item's name.
 *
 * Both are tables: a header names the columns, and each item is exactly one
 * value in the **total** column. So this reads them as one — and deliberately
 * nothing else. A page without a header of this shape returns null and the
 * line-by-line path runs exactly as before, so no thermal receipt is affected.
 *
 * ## The four steps
 *
 * 1. **The header band.** Up to [MAX_HEADER_LINES] consecutive lines made
 *    almost entirely of column words (`SR`, `Item`, `HSN`, `Qty`, `Taxable`,
 *    `CGST`, `Total`, `Amt.` …) and carrying no money. It must name at least
 *    [MIN_COLUMNS] columns, a description column and a total column. The
 *    column count is what keeps a thermal slip's `ITEM QTY RATE AMOUNT` out:
 *    that is a line-by-line layout with column headings, not a table with
 *    wrapped cells, and the existing path already reads it.
 * 2. **Columns from the header.** Header words that overlap horizontally are
 *    one column (`Item &` above `Description`). A column's extent is
 *    everything strictly between its neighbours — a description wider than its
 *    heading still belongs to it.
 * 3. **Anchors.** Below the band, each line with money in the total column is
 *    one item, until a totals keyword or the column-total line — money in the
 *    total column and none of a serial number, a quantity or an HSN code.
 * 4. **Names.** The description column's text lines are split into contiguous
 *    groups, one per anchor, choosing the split that puts each group's middle
 *    closest to its anchor — the geometry of a vertically centred cell, which
 *    both real invoices use. It needs no threshold: no gap size, no line pitch,
 *    nothing to tune against two receipts. A top-aligned layout (figures on a
 *    cell's first line) is **not** handled; neither invoice has one, and a
 *    branch for it would be untested by anything real.
 *
 * Every table item is `ITEM`. On these formats tax and discount are *columns*,
 * already inside each row's total, never rows — and running the keyword sets
 * over a product description would read `PROCESSED CHEESE` as a CESS line.
 */
internal object ReceiptTable {

    data class Item(
        val name: String,
        /** Null when the total cell held a figure that did not read as money. */
        val amount: Money?,
        val quantity: Quantity?,
    )

    data class Table(
        /** Index of the first header line in the page's rows. */
        val headerFirst: Int,
        /** Index of the first row after the item block, into the page's rows. */
        val afterBody: Int,
        val items: List<Item>,
        /** The column-total line's figure, when the table printed one. */
        val columnTotal: Money?,
    )

    fun read(rows: List<ReceiptRow>, scale: Float, currency: String): Table? {
        val band = headerBand(rows, currency) ?: return null
        val layout = layoutOf(band.flatMap { rows[it].elements }, scale) ?: return null
        return bodyOf(rows, band.last + 1, layout, currency)
            .takeIf { it.anchors.isNotEmpty() }
            ?.let { body ->
                val lines = descriptionLines(rows, band.last + 1, body.end, layout)
                val groups = assign(lines, body.anchors.map { it.y })
                val items = body.anchors.mapIndexed { index, anchor ->
                    Item(cleanName(groups[index].joinToString(" ") { it.text }), anchor.amount, anchor.quantity)
                }
                Table(band.first, body.end, items, body.columnTotal)
            }
    }

    private fun layoutOf(header: List<RecognizedElement>, scale: Float): Layout? {
        val columns = columnsOf(header, scale).takeIf { it.size >= MIN_COLUMNS } ?: return null
        val description = columns.bestFor(DESCRIPTION_WORDS)
        val total = (
            columns.lastOrNull { it.words.any { word -> word in TOTAL_WORDS } }
                ?: columns.lastOrNull { it.words.any { word -> word in AMOUNT_WORDS } }
            )?.takeIf { it != description }
        if (description == null || total == null) return null
        return Layout(
            columns = columns,
            description = description,
            total = total,
            quantity = columns.firstOrNull { it.words.any { word -> word in QUANTITY_WORDS } },
            code = columns.firstOrNull { it.words.any { word -> word in CODE_WORDS } },
        )
    }

    // ── 1. The header band ──────────────────────────────────────────────────

    private fun headerBand(rows: List<ReceiptRow>, currency: String): IntRange? {
        val start = rows.indices.firstOrNull { index ->
            val row = rows[index]
            row.isHeaderLike(currency) &&
                row.elements.count { it.isHeaderWord() } >= MIN_HEADER_WORDS_ON_A_LINE
        } ?: return null

        // Down first: the column headings a table cannot be read without (`No.`,
        // `Code`, `Amount`) sit below the line that qualified; the stacked
        // fragments above it (`SGST/`) are the ones that can be spared.
        var last = start
        val room = MAX_HEADER_LINES - 1
        while (last + 1 < rows.size && last - start < room && rows[last + 1].isHeaderLike(currency)) last++
        var first = start
        while (first > 0 && last - first < room && rows[first - 1].isHeaderLike(currency)) first--
        return first..last
    }

    private fun ReceiptRow.isHeaderLike(currency: String): Boolean {
        if (elements.any { ReceiptNumbers.money(it.text, currency) != null }) return false
        val words = elements.count { it.isHeaderWord() }
        return words > 0 && words.toFloat() / elements.size >= HEADER_WORD_SHARE
    }

    private fun RecognizedElement.isHeaderWord(): Boolean {
        val tokens = headerTokens(text)
        return tokens.isNotEmpty() && tokens.all { it in HEADER_WORDS }
    }

    private fun headerTokens(text: String): List<String> =
        text.uppercase().split(NON_LETTERS).filter { it.isNotEmpty() }

    // ── 2. Columns ──────────────────────────────────────────────────────────

    private data class Column(val left: Float, val right: Float, val words: Set<String>)

    private fun columnsOf(
        header: List<RecognizedElement>,
        scale: Float,
    ): List<Column> {
        val gap = scale * WORD_GAP_FRACTION
        val merged = mutableListOf<Column>()
        header.filter { it.isHeaderWord() }.sortedBy { it.left }.forEach { element ->
            val words = headerTokens(element.text).toSet()
            val last = merged.lastOrNull()
            if (last != null && element.left <= last.right + gap) {
                merged[merged.lastIndex] =
                    Column(last.left, maxOf(last.right, element.right), last.words + words)
            } else {
                merged += Column(element.left, element.right, words)
            }
        }
        return merged
    }

    private fun List<Column>.bestFor(priority: List<String>): Column? =
        priority.firstNotNullOfOrNull { word -> firstOrNull { word in it.words } }

    /** Strictly between this column's neighbours. The outer columns are open-ended. */
    private fun List<Column>.contains(column: Column, x: Float): Boolean {
        val index = indexOf(column)
        val from = if (index == 0) Float.NEGATIVE_INFINITY else this[index - 1].right
        val to = if (index == lastIndex) Float.POSITIVE_INFINITY else this[index + 1].left
        return x > from && x < to
    }

    // ── 3. Anchors ──────────────────────────────────────────────────────────

    private data class Anchor(val y: Float, val amount: Money?, val quantity: Quantity?)

    private data class Body(val anchors: List<Anchor>, val end: Int, val columnTotal: Money?)

    private data class Layout(
        val columns: List<Column>,
        val description: Column,
        val total: Column,
        val quantity: Column?,
        val code: Column?,
    ) {
        fun inColumn(column: Column?, x: Float): Boolean = column != null && columns.contains(column, x)

        /** Left of the description column's right-hand neighbour: serial number or description. */
        fun leftOfFigures(x: Float): Boolean = x < columns.rightEdgeOf(description)
    }

    /** What one line below the header is, to the item block. */
    private sealed interface Role {
        /** A totals keyword: the item block is over, and this line is not part of it. */
        data object End : Role

        /** Money in the total column and no sign of an item: the table's own sum. */
        data class ColumnTotal(val amount: Money) : Role

        data class ItemLine(val anchor: Anchor) : Role

        /** A wrapped name, a tax-amount line, a blank: nothing to anchor on. */
        data object Other : Role
    }

    private fun bodyOf(rows: List<ReceiptRow>, from: Int, layout: Layout, currency: String): Body {
        val anchors = mutableListOf<Anchor>()
        for (index in from until rows.size) {
            when (val role = roleOf(rows[index], layout, currency)) {
                Role.End -> return Body(anchors, index, null)
                is Role.ColumnTotal -> if (anchors.isNotEmpty()) return Body(anchors, index + 1, role.amount)
                is Role.ItemLine -> anchors += role.anchor
                Role.Other -> Unit
            }
        }
        return Body(anchors, rows.size, null)
    }

    private fun roleOf(row: ReceiptRow, layout: Layout, currency: String): Role {
        val upper = row.text.uppercase()
        val isTotalsKeyword = ReceiptKeywords.matches(upper, ReceiptKeywords.TOTAL) ||
            ReceiptKeywords.matches(upper, ReceiptKeywords.SUBTOTAL)

        val totalCell = row.elements.filter { layout.inColumn(layout.total, it.centerX) }
        val amount = totalCell.firstNotNullOfOrNull { ReceiptNumbers.money(it.text, currency) }
        val qty = row.elements
            .filter { layout.inColumn(layout.quantity, it.centerX) }
            .firstNotNullOfOrNull { ReceiptNumbers.quantity(it.text) }
        val hasFigure = amount != null || totalCell.any { it.text.any(Char::isDigit) }

        // **An item line whose total did not read is still an item**, with no
        // amount. On the real Zepto invoice `52.00` came back `52,00`, which
        // BUG23 rightly refuses as money -- and dropping the line with it lost
        // that product *and* shifted every wrapped name after it onto the wrong
        // one. Kept, the names stay on their own products and the review screen
        // shows a line waiting for its figure; the bill reports unbalanced by
        // exactly that figure, which is true.
        return when {
            isTotalsKeyword -> Role.End
            !hasFigure -> Role.Other
            isItemLine(row, qty, layout) ->
                Role.ItemLine(Anchor(row.elements.map { it.centerY }.average().toFloat(), amount, qty))
            amount != null -> Role.ColumnTotal(amount)
            else -> Role.Other
        }
    }

    /**
     * **The column-total line has none of the three things an item line has**,
     * and any one of them is enough. A single signal is not: on the device ML
     * Kit dropped the `1` from Zepto's third quantity cell, and bigbasket's
     * eighth line printed no serial number — each would have ended the table
     * early on a one-signal rule.
     */
    private fun isItemLine(row: ReceiptRow, qty: Quantity?, layout: Layout): Boolean =
        qty != null ||
            row.elements.any { layout.leftOfFigures(it.centerX) } ||
            row.elements.any {
                layout.inColumn(layout.code, it.centerX) && it.text.count(Char::isDigit) >= MIN_CODE_DIGITS
            }

    private fun List<Column>.rightEdgeOf(column: Column): Float {
        val index = indexOf(column)
        return if (index == lastIndex) Float.POSITIVE_INFINITY else this[index + 1].left
    }

    // ── 4. Names ────────────────────────────────────────────────────────────

    private data class Line(val y: Float, val text: String)

    private fun descriptionLines(
        rows: List<ReceiptRow>,
        from: Int,
        until: Int,
        layout: Layout,
    ): List<Line> = (from until until).mapNotNull { index ->
        // Column position is the whole filter. Excluding money-shaped words
        // here would strip `1` from `Chips 1 pack` and `52.9` from `52.9 g`:
        // pack sizes are numbers, and the tax figures that are not part of a
        // name already sit in their own columns.
        val words = rows[index].elements.filter { element ->
            layout.inColumn(layout.description, element.centerX)
        }
        if (words.isEmpty()) {
            null
        } else {
            Line(words.map { it.centerY }.average().toFloat(), words.joinToString(" ") { it.text })
        }
    }

    /**
     * Contiguous groups of [lines], one per anchor, minimising the squared
     * distance between each anchor and its group's middle.
     *
     * A small dynamic programme — O(anchors × lines²) over a few dozen lines —
     * because the obvious greedy rule, "each line to its nearest anchor", is
     * wrong on exactly the layout that needs this: when adjacent cells wrap to
     * different heights, the midpoint between two anchors falls inside the
     * taller cell, and its last line goes to the next product. Measured on
     * Zepto's layout, that moves `or 52.9 g)` from the crisps onto the cookies.
     *
     * An empty group costs [EMPTY_GROUP_COST] scaled by the page, so an anchor
     * goes nameless only when there are fewer lines than anchors.
     */
    private fun assign(lines: List<Line>, anchors: List<Float>): List<List<Line>> {
        val k = anchors.size
        val n = lines.size
        val spread = (lines.maxOfOrNull { it.y } ?: 0f) - (lines.minOfOrNull { it.y } ?: 0f) + 1f
        val empty = (spread * spread).toDouble() * EMPTY_GROUP_COST

        fun cost(anchor: Int, from: Int, until: Int): Double {
            if (from == until) return empty
            val middle = (lines[from].y + lines[until - 1].y) / 2f
            val offset = middle - anchors[anchor]
            return (offset * offset).toDouble()
        }

        val best = Array(k + 1) { DoubleArray(n + 1) { Double.POSITIVE_INFINITY } }
        val split = Array(k + 1) { IntArray(n + 1) }
        best[0][0] = 0.0
        for (anchor in 1..k) {
            for (end in 0..n) {
                val start = (0..end).minBy { best[anchor - 1][it] + cost(anchor - 1, it, end) }
                best[anchor][end] = best[anchor - 1][start] + cost(anchor - 1, start, end)
                split[anchor][end] = start
            }
        }

        val groups = MutableList(k) { emptyList<Line>() }
        var end = n
        for (anchor in k downTo 1) {
            val start = split[anchor][end]
            groups[anchor - 1] = lines.subList(start, end)
            end = start
        }
        return groups
    }

    /**
     * Drops an HSN code that spilled into the description.
     *
     * bigbasket's names run up against the HSN column, and ML Kit returns the
     * join as one run — `Ghee/Tuppa04059020`. A run of six or more digits is a
     * classification code in any product name this app will meet; sizes and
     * pack counts are one to four.
     */
    private fun cleanName(raw: String): String =
        raw.split(' ')
            .map { it.replace(TRAILING_CODE, "") }
            .filter { it.isNotEmpty() }
            .joinToString(" ")
            .trim()

    private val TRAILING_CODE = Regex("""\d{6,}$""")
    private val NON_LETTERS = Regex("""[^\p{L}]+""")

    private const val MAX_HEADER_LINES = 5

    /** An HSN code is 4, 6 or 8 digits; a serial number or quantity never reaches 4. */
    private const val MIN_CODE_DIGITS = 4
    private const val MIN_COLUMNS = 5
    private const val MIN_HEADER_WORDS_ON_A_LINE = 4
    private const val HEADER_WORD_SHARE = 0.6f

    /** Header words closer than this, as a share of glyph height, are one column. */
    private const val WORD_GAP_FRACTION = 0.5f

    /** Relative to the squared vertical spread of all description lines. */
    private const val EMPTY_GROUP_COST = 4.0

    private val DESCRIPTION_WORDS = listOf("DESCRIPTION", "ITEM", "PARTICULARS", "NAME", "PRODUCT")
    private val TOTAL_WORDS = setOf("TOTAL")
    private val AMOUNT_WORDS = setOf("AMOUNT", "AMT", "VALUE")
    private val QUANTITY_WORDS = setOf("QTY", "QUANTITY")
    private val CODE_WORDS = setOf("HSN", "SAC", "CODE")

    private val HEADER_WORDS = setOf(
        "SI", "SL", "SR", "S", "NO", "SNO", "ITEM", "ITEMS", "DESCRIPTION", "PARTICULARS",
        "PRODUCT", "NAME", "HSN", "SAC", "CODE", "QTY", "QUANTITY", "UNIT", "UOM", "PRICE",
        "RATE", "MRP", "RSP", "DISC", "DISCOUNT", "MARGIN", "TAXABLE", "VALUE", "GROSS",
        "NET", "CGST", "SGST", "IGST", "UTGST", "UT", "GST", "CESS", "TAX", "TOTAL", "AMT",
        "AMOUNT", "OTHER", "CHARGES", "OF", "AND", "PER",
    )
}
