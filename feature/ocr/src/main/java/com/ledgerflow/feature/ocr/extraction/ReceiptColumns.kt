package com.ledgerflow.feature.ocr.extraction

import com.ledgerflow.core.model.Money
import com.ledgerflow.core.model.Quantity

/**
 * Step 7 of §5.3: **rightmost numeric run = amount, leftmost text run = name.**
 *
 * The spec's one-liner is right and incomplete, and the incompleteness is
 * where the bugs live. An Indian retail line is rarely two columns; it is
 * commonly four —
 *
 * ```
 * TOOR DAL 1KG        2    165.00    330.00
 * ```
 *
 * — and reading only the rightmost number would still get the *amount* right
 * while throwing away a quantity and a unit price the bill printed in plain
 * sight. Reading them naively is worse: assume the middle two are `qty × rate`
 * and a row laid out `NAME  MRP  RATE  AMOUNT` silently becomes a line with the
 * wrong quantity.
 *
 * ## The rule that decides it: the arithmetic has to close
 *
 * A quantity and a unit price are accepted **only when `unit × quantity`
 * reproduces the printed amount exactly**. Not within a tolerance — Law 3's
 * spirit is that money is right or it is wrong, and a tolerance here is how a
 * mis-read column becomes a plausible line nobody checks. When it does not
 * close, the amount survives alone and the description is dropped, which is the
 * same trade `ExtractedLinesMapping` makes one layer up and for the same
 * reason: the amount is the line, the quantity describes it.
 *
 * This also means a three-number row is *self-validating*. `2 × 165.00 = 330.00`
 * confirms the whole reading — the banding put the right runs on one row, the
 * gutters fell in the right places, and no digit was misread. A row that closes
 * is worth more confidence than one that merely parsed, and [ReceiptColumns]
 * says so.
 */
internal object ReceiptColumns {

    /**
     * Reads one row's columns, or null when the row holds no amount at all.
     *
     * Null is a real answer and not a failure: a header, a `THANK YOU`, a
     * `GSTIN: 29AA…` and a wrapped item name all have no amount, and §5.3's
     * classifier needs to see them as rows without one rather than not see them.
     */
    fun read(cells: List<ReceiptCell>, currency: String): ReceiptColumnReading? {
        if (cells.isEmpty()) return null

        // The rightmost cell that parses as money. Rightmost rather than "the
        // last one", because a trailing `*` or a tax-code letter can occupy the
        // final cell on a GST invoice.
        val amountIndex = cells.indices.lastOrNull { index ->
            ReceiptNumbers.money(cells[index].text, currency) != null
        } ?: return null

        val amount = requireNotNull(ReceiptNumbers.money(cells[amountIndex].text, currency)) {
            "cell $amountIndex parsed as money a moment ago and must still"
        }

        val before = cells.take(amountIndex)
        // The name is everything up to the first cell that is purely numeric:
        // `TOOR DAL 1KG` keeps its `1KG`, because that cell is not a bare
        // number, while the standalone `2` starts the figures.
        val nameEnd = before.indexOfFirst { ReceiptNumbers.money(it.text, currency) != null }
            .let { if (it < 0) before.size else it }

        val name = before.take(nameEnd).joinToString(" ") { it.text }.trim()
        val figures = before.drop(nameEnd)

        val figuresRead = readQuantityAndUnitPrice(figures, amount, currency)

        return ReceiptColumnReading(
            name = name,
            amount = amount,
            quantity = figuresRead?.quantity,
            unitPrice = figuresRead?.unitPrice,
            amountCellLeft = cells[amountIndex].left,
            closes = figuresRead?.printed == true,
        )
    }

    /**
     * The figures between the name and the amount, when they explain it.
     *
     * Two shapes are accepted and everything else is declined:
     *
     * - **`qty rate`** — two figures, in that order, which is how every POS
     *   this app will meet prints a line
     * - **`rate`** alone, when it divides the amount into a whole number of
     *   units. A bill printing `MILK 500ML  27.00  54.00` has no quantity
     *   column and the reader can still recover the 2.
     *
     * Both are confirmed against the amount before being believed. The second
     * is the weaker inference of the two — it invents a quantity the bill never
     * printed — so it is bounded: only a small whole number counts, because
     * `1.00 → 47300` would otherwise "recover" a quantity of 473.
     */
    private fun readQuantityAndUnitPrice(
        figures: List<ReceiptCell>,
        amount: Money,
        currency: String,
    ): Figures? {
        if (amount.isZero) return null

        if (figures.size >= 2) {
            val quantity = ReceiptNumbers.quantity(figures[figures.size - 2].text)
            val unit = ReceiptNumbers.money(figures[figures.size - 1].text, currency)
            if (quantity != null && unit != null && unit * quantity == amount) {
                return Figures(quantity, unit, printed = true)
            }
        }

        if (figures.size == 1) {
            val unit = ReceiptNumbers.money(figures[0].text, currency)
            if (unit != null && !unit.isZero && amount.minor % unit.minor == 0L) {
                val units = amount.minor / unit.minor
                if (units in 1..MAX_INFERRED_UNITS) {
                    // Deduced, not printed: the bill never stated this
                    // quantity, so it does not confirm the row's reading the
                    // way a closing `qty x rate` does.
                    return Figures(Quantity.ofUnits(units), unit, printed = false)
                }
            }
        }

        return null
    }

    /** A row's middle columns, and whether the bill actually printed them. */
    private data class Figures(
        val quantity: Quantity,
        val unitPrice: Money,
        val printed: Boolean,
    )

    /**
     * The largest quantity worth *inferring* from a bare unit price.
     *
     * A printed quantity has no bound here — a bill may legitimately say 24.
     * This caps only the case where no quantity was printed and one is being
     * deduced from a division, where a large answer is far more likely to mean
     * the two figures were never a rate and an amount in the first place.
     */
    private const val MAX_INFERRED_UNITS = 20L
}

/**
 * What one row's columns say.
 *
 * @param amountCellLeft where the amount column starts on this row. Kept
 *   because a receipt's amount column is vertically aligned and step 8 uses
 *   that agreement to tell a real item row from a stray number in the footer.
 * @param closes whether `unit price × quantity` reproduced [amount] exactly. A
 *   row that closes has confirmed its own banding, gutters and digits at once,
 *   which is worth more than a row that merely parsed.
 */
internal data class ReceiptColumnReading(
    val name: String,
    val amount: Money,
    val quantity: Quantity?,
    val unitPrice: Money?,
    val amountCellLeft: Float,
    val closes: Boolean,
)
