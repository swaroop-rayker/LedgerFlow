package com.ledgerflow.feature.ocr.extraction

/**
 * Step 8 of §5.3: what each reconstructed row *is*.
 *
 * ```
 * HEADER | ITEM | TAX | DISCOUNT | SUBTOTAL | TOTAL | FOOTER | NOISE
 * ```
 *
 * **Only [ITEM] counts toward §12's ≥90% recall**, which is what makes this the
 * step the gate is most sensitive to. A tax line mistaken for an item inflates
 * the numerator and the denominator disagrees; an item mistaken for noise is a
 * miss the user has to type back in.
 */
internal enum class ReceiptLineKind {
    HEADER,
    ITEM,
    TAX,
    DISCOUNT,
    SUBTOTAL,
    TOTAL,
    FOOTER,
    NOISE,
}

/**
 * The keyword sets, and the order they are tried in.
 *
 * **Order is the whole design.** Every one of these words is a substring of
 * another line's words on some real bill, so a flat "does it contain TOTAL"
 * test is wrong in three separate ways at once:
 *
 * - `SUB TOTAL` contains `TOTAL`, so [SUBTOTAL] must be tried first
 * - `TOTAL SAVINGS` contains `TOTAL` and means a discount, so [DISCOUNT] must
 *   be tried before [TOTAL] too
 * - `TOTAL QTY: 14` contains `TOTAL` and is not money at all, which is why a
 *   row with no amount can never be classified as a totals line no matter what
 *   it says
 *
 * Devanagari is in the sets rather than transliterated, because ADR-0021 ships
 * the Devanagari recogniser and a Hindi bill prints `कुल` and `बिल राशि`
 * literally. Matching is on the uppercased text, which leaves Devanagari
 * unchanged — it is a unicameral script — so one comparison serves both.
 */
internal object ReceiptKeywords {

    /**
     * §5.3's set, plus the ones the spec's list implies.
     *
     * The spec names {TOTAL, GRAND TOTAL, NET AMOUNT, AMOUNT PAYABLE, बिल राशि}.
     * `BILL AMOUNT`, `NET PAYABLE`, `AMOUNT DUE` and `कुल` are the same words in
     * the orders other POS software prints them; adding one that turns out not
     * to appear costs nothing, and missing one costs the bill's total.
     */
    val TOTAL = listOf(
        "GRAND TOTAL", "NET AMOUNT", "AMOUNT PAYABLE", "NET PAYABLE", "AMOUNT DUE",
        "BILL AMOUNT", "TOTAL AMOUNT", "बिल राशि", "कुल योग", "कुल", "TOTAL",
    )

    val SUBTOTAL = listOf(
        "SUB TOTAL", "SUBTOTAL", "SUB-TOTAL", "TAXABLE VALUE", "TAXABLE AMT",
        "GROSS AMOUNT", "GROSS TOTAL", "उप योग",
    )

    val TAX = listOf(
        "CGST", "SGST", "IGST", "UTGST", "GST", "VAT", "CESS",
        "SERVICE TAX", "SERVICE CHARGE", "SERV CHRG", "TAX", "कर",
    )

    val DISCOUNT = listOf(
        "DISCOUNT", "DISC.", "DISC", "TOTAL SAVINGS", "SAVINGS", "YOU SAVED",
        "PROMO", "COUPON", "REBATE", "OFFER", "छूट",
    )

    /**
     * Words that mean "this row is about the transaction, not the shopping".
     *
     * A tender line (`CASH 500.00`, `CHANGE 27.00`, `CARD ****1234`) carries an
     * amount and sits below the total, so nothing but a keyword separates it
     * from an item. Getting this wrong does not merely add a line — `CASH` and
     * `CHANGE` on the same bill add roughly *twice* the total to the item sum
     * and make every receipt look unbalanced.
     */
    val TENDER = listOf(
        "CASH", "CHANGE", "CARD", "UPI", "PAYTM", "GPAY", "PHONEPE", "TENDER",
        "PAID", "BALANCE", "ROUND OFF", "ROUNDOFF", "ROUND-OFF", "CHANGE DUE",
    )

    /**
     * Identifiers and pleasantries. A row matching one is never an item.
     *
     * `INVOICE`, `BILL NO` and `GSTIN` matter most: each is followed by a long
     * digit run that [ReceiptNumbers] already declines, but a *short* invoice
     * number would parse, and `BILL NO 4521` would become a ₹45.21 purchase.
     */
    val ADMIN = listOf(
        "GSTIN", "GST NO", "FSSAI", "TIN", "CIN", "PAN",
        "INVOICE", "BILL NO", "BILL NUMBER", "RECEIPT NO", "ORDER NO", "TOKEN",
        "DATE", "TIME", "CASHIER", "COUNTER", "TERMINAL", "TILL", "OPERATOR",
        "PHONE", "TEL", "MOBILE", "CUSTOMER", "MEMBER ID", "LOYALTY", "POINTS",
        "THANK", "VISIT AGAIN", "WELCOME", "TERMS", "EXCHANGE", "RETURN POLICY",
        "TOTAL QTY", "TOTAL ITEMS", "NO OF ITEMS", "ITEMS:", "QTY:",
        "धन्यवाद",
    )

    /** Words that mean the amount is coming back to the customer. */
    val REFUND = listOf("REFUND", "CREDIT NOTE", "RETURN", "REVERSAL")

    /** Substring match on already-uppercased text. */
    fun matches(text: String, keywords: List<String>): Boolean =
        keywords.any { text.contains(it) }
}

/**
 * Classifies the rows of one receipt.
 *
 * **Classification is a property of the page, not of a row.** `47.00` on its
 * own is an item on line 8 and a tender amount on line 40, and `MILK` above the
 * first priced row is part of a shop's name. So this takes the whole list and
 * uses two page-level facts a single row cannot see: where the totals block
 * begins, and where the header ends.
 */
internal object ReceiptLineClassifier {

    /**
     * The boundary between the header and the body is **the first priced row**.
     *
     * Not "the first N rows" and not a fraction of the page height: a kirana
     * slip has two header lines and an A4 GST invoice has fifteen, and any
     * constant is wrong for one of them. A shop prints its name, address, GSTIN
     * and the bill's identifiers before it prints a price, so the first row
     * carrying an amount is where the shopping starts — with the one exception
     * the keyword sets already handle, an identifier whose number happens to
     * parse.
     */
    fun classify(rows: List<ClassifiableRow>): List<ReceiptLineKind> {
        val firstPriced = rows.indexOfFirst { it.hasAmount && !it.isAdministrative }
        // The totals block starts at the FIRST subtotal/total/tax row, so that
        // everything below it is read as summary and tender rather than as
        // more shopping. Taken from the first because some bills print TOTAL,
        // then the tax breakdown, then GRAND TOTAL.
        val firstSummary = rows.indexOfFirst { it.summaryKind() != null }

        return rows.mapIndexed { index, row ->
            val summary = row.summaryKind()
            when {
                summary != null -> summary
                firstPriced < 0 || index < firstPriced -> ReceiptLineKind.HEADER
                row.isAdministrative -> if (firstSummary >= 0 && index >= firstSummary) {
                    ReceiptLineKind.FOOTER
                } else {
                    ReceiptLineKind.NOISE
                }
                // Below the summary block, a priced row is tender or a
                // pleasantry, never shopping.
                firstSummary >= 0 && index > firstSummary -> ReceiptLineKind.FOOTER
                !row.hasAmount -> ReceiptLineKind.NOISE
                row.name.isBlank() -> ReceiptLineKind.NOISE
                else -> ReceiptLineKind.ITEM
            }
        }
    }

    /**
     * The kinds a row can claim on its own evidence, in the order §5.3's
     * keywords overlap.
     *
     * **Every one of them requires an amount.** `TOTAL QTY: 14` says TOTAL and
     * is not a total; a summary line without money is a label, and treating it
     * as one is what stops a heading from eating the figure on the row below.
     */
    private fun ClassifiableRow.summaryKind(): ReceiptLineKind? {
        if (!hasAmount) return null
        // **Before the keyword sets, not after.** `TOTAL QTY: 14` contains
        // TOTAL and carries a parseable number, so without this it would be
        // read as the bill's total -- fourteen rupees, replacing the real one.
        if (isAdministrative) return null
        val text = upper
        return when {
            ReceiptKeywords.matches(text, ReceiptKeywords.DISCOUNT) -> ReceiptLineKind.DISCOUNT
            ReceiptKeywords.matches(text, ReceiptKeywords.SUBTOTAL) -> ReceiptLineKind.SUBTOTAL
            ReceiptKeywords.matches(text, ReceiptKeywords.TAX) -> ReceiptLineKind.TAX
            ReceiptKeywords.matches(text, ReceiptKeywords.TOTAL) -> ReceiptLineKind.TOTAL
            ReceiptKeywords.matches(text, ReceiptKeywords.TENDER) -> ReceiptLineKind.FOOTER
            else -> null
        }
    }
}

/** What the classifier needs to know about a row, and nothing else. */
internal data class ClassifiableRow(
    val upper: String,
    val name: String,
    val hasAmount: Boolean,
) {
    val isAdministrative: Boolean
        get() = ReceiptKeywords.matches(upper, ReceiptKeywords.ADMIN)
}
