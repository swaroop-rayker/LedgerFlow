package com.ledgerflow.feature.ocr.extraction

import com.ledgerflow.core.domain.text.JaroWinkler

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

    /**
     * The spaced forms are not duplicates of the glued ones, and they are what
     * made the owner's real invoice readable.
     *
     * An Indian GST invoice prints `S GST 9%` and `C GST 9%` — with the space —
     * under every item, and ML Kit read one of them as `S 6ST 9%`. Exact
     * substring missed, the row fell through as a line item, and it was one of
     * the two "items" that reached the Inbox on a six-item bill.
     *
     * Listing the spaced form is what lets [matches]' fuzzy branch see the case
     * at all: `S 6ST` against `S GST` is a five-character comparison scoring
     * 0.8933, comfortably over §5.5's 0.88, while `6ST` against the bare `GST`
     * is a three-character one scoring 0.7778 — below the threshold, and below
     * what `GET` scores against `GST`. **The fix is a longer keyword, not a
     * looser threshold**, which is the only version of it that does not also
     * admit `BAT` as VAT.
     */
    val TAX = listOf(
        "CGST", "SGST", "IGST", "UTGST",
        "S GST", "C GST", "I GST", "UT GST",
        "GST", "VAT", "CESS",
        "SERVICE TAX", "SERVICE CHARGE", "SERV CHRG", "TAX", "कर",
    )

    /**
     * A discount **charged on this bill**, as a line of it.
     *
     * Note what left this list: `SAVING` and `YOU SAVED` moved to [ADMIN].
     * See the note there — a saving is a report of what was *not* charged,
     * and subtracting it again double-counts.
     */
    val DISCOUNT = listOf(
        "DISCOUNT", "DISC.", "DISC", "PROMO", "COUPON", "REBATE", "OFFER", "छूट",
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
        // A GST invoice prints an HSN/SAC classification code and a unit of
        // measure under every item. `HSN : 2005` parses as ₹20.05 and is
        // not shopping.
        "HSN", "SAC", "UOM",
        // **A saving is a report, not a charge**, and both halves of that
        // matter. `TOTAL SAVING: 75.00` on a real Food Bazaar bill contains
        // the word TOTAL, so left to the keyword sets it became the bill
        // total -- ₹75.00 in place of ₹1,075.46. Moved to DISCOUNT it
        // stopped being the total and started being subtracted from the
        // parts, which is just as wrong: the per-item discounts it sums were
        // already applied in each NET AMT, so taking it off again removes
        // them twice.
        //
        // It belongs here, with the identifiers: informational, never a line
        // of the bill. A discount genuinely charged as its own line still
        // says DISCOUNT, DISC or OFFER.
        "TOTAL SAVING", "YOU SAVED", "SAVINGS", "SAVING",
        "INVOICE", "BILL NO", "BILL NUMBER", "RECEIPT NO", "ORDER NO", "TOKEN",
        "DATE", "TIME", "CASHIER", "COUNTER", "TERMINAL", "TILL", "OPERATOR",
        "PHONE", "TEL", "MOBILE", "CUSTOMER", "MEMBER ID", "LOYALTY", "POINTS",
        "THANK", "VISIT AGAIN", "WELCOME", "TERMS", "EXCHANGE", "RETURN POLICY",
        "TOTAL QTY", "TOTAL ITEMS", "NO OF ITEMS", "ITEMS:", "QTY:",
        "धन्यवाद",
    )

    /** Words that mean the amount is coming back to the customer. */
    val REFUND = listOf("REFUND", "CREDIT NOTE", "RETURN", "REVERSAL")

    /**
     * Does [text] — **already uppercased by the caller** — carry one of
     * [keywords]?
     *
     * Two layers, in this order, and the order is not cosmetic.
     *
     * **1. Exact substring, exactly as before.** Every match this function made
     * before fuzzy matching existed, it still makes. That is deliberate: the
     * three ordering traps this object's KDoc describes (`SUB TOTAL`,
     * `TOTAL SAVING`, `TOTAL QTY`) are all properties of *which set matches
     * first*, and a rewritten first layer would have put every one of them back
     * in play. The fuzzy layer can only ever **add** a match.
     *
     * **2. One glyph's worth of doubt, at a word boundary.** §12 already
     * concedes the principle for item names — "OCR legitimately reads
     * `TOMATO 1KG` as `TOMAT0 1KG`" — and keywords are words too. A token
     * window matches a keyword when all three of these hold:
     *
     * - **the same length**, because a substituted glyph preserves length while
     *   an inserted or dropped character does not. Dropping this clause is what
     *   makes `REFINED` match `REFUND` (0.8944) — and `REFUND` decides the
     *   direction of the whole receipt, so a bottle of refined oil would turn a
     *   purchase into a credit. It also gives `TIL` ~ `TILL`, `PAD` ~ `PAID`,
     *   `CHANA` ~ `CHANGE` and `CASHEW` ~ `CASHIER`: eight wrong lines measured
     *   over a vocabulary of real Indian retail item names.
     * - **at most one differing position**, because two wrong glyphs on one
     *   short word is not a misread, it is a different word. Dropping this
     *   clause admits `CASHEWS` ~ `CASHIER` (0.8857), which three substitutions
     *   and a four-character prefix bonus carry over the threshold.
     * - **[JaroWinkler.MERCHANT_THRESHOLD]**, §5.5's own 0.88 and not a number
     *   invented here. Dropping it admits twelve more, all on three-character
     *   keywords: `TEA` ~ `TEL`, `TIL` ~ `TIN`, `CAN` ~ `PAN`, `PEN` ~ `PAN`,
     *   `BAT` ~ `VAT`, `MAT` ~ `VAT`, `CURD` ~ `CARD`. **The threshold is the
     *   length floor**, which is why there is no length constant here: no
     *   single substitution can reach 0.88 below four characters (the worst case
     *   at three is 0.8222), so short keywords stay exact-only for free rather
     *   than by a rule someone has to maintain.
     *
     * All three clauses together admit **zero** new wrong lines over that
     * vocabulary and still catch `S 6ST` ~ `S GST`. A transposition is
     * deliberately not admitted: a glyph recogniser substitutes characters it
     * misreads, it does not swap adjacent ones — that is a typing failure, not
     * an OCR one.
     */
    fun matches(text: String, keywords: List<String>): Boolean {
        if (keywords.any { text.contains(it) }) return true
        val words = words(text)
        return words.isNotEmpty() && keywords.any { fuzzyMatches(words, it) }
    }

    /**
     * One keyword against every same-shaped window of a row's words.
     *
     * A multi-word keyword takes a window of that many consecutive words, so
     * `S GST` is compared against `S 6ST` and not against either half alone.
     */
    private fun fuzzyMatches(words: List<String>, keyword: String): Boolean {
        val target = KEYWORD_WORDS[keyword] ?: words(keyword)
        if (target.isEmpty()) return false
        val joined = target.joinToString(" ")

        return (0..words.size - target.size).any { start ->
            isOneGlyphOff(words.subList(start, start + target.size).joinToString(" "), joined)
        }
    }

    /**
     * The three clauses, cheapest first — and `&&` short-circuits, so the
     * length test is what stops [differingPositions] from being handed strings
     * it cannot compare.
     */
    private fun isOneGlyphOff(window: String, keyword: String): Boolean =
        window.length == keyword.length &&
            differingPositions(window, keyword) <= 1 &&
            JaroWinkler.similarity(window, keyword) >= JaroWinkler.MERCHANT_THRESHOLD

    /** Callers guarantee equal lengths; this counts glyphs, not edits. */
    private fun differingPositions(a: String, b: String): Int =
        a.indices.count { a[it] != b[it] }

    /**
     * A row's words, for window matching.
     *
     * Splits on whitespace and **ASCII** punctuation only. Restricting the
     * class to ASCII is what keeps Devanagari intact: `राशि` is a consonant
     * followed by a combining matra, and a "not a letter or digit" split would
     * discard the matras and leave `रश` — so the two Devanagari entries in
     * these sets would quietly stop behaving like the Latin ones. `₹` survives
     * for the same reason and costs nothing, since no keyword contains a digit.
     *
     * It is also what makes `ROUND-OFF` and `SUB-TOTAL` reach the keywords
     * `ROUND OFF` and `SUB TOTAL` as windows, and `TOTAL QTY:` reach
     * `TOTAL QTY`.
     */
    private fun words(text: String): List<String> =
        text.split(SEPARATORS).filter { it.isNotEmpty() }

    private val SEPARATORS = Regex("""[\s\p{Punct}]+""")

    /**
     * Every keyword's words, split once at class-init rather than per row.
     *
     * `matches` is called with each of these sets for every row of every
     * receipt, so re-splitting ~90 constant strings each time would be ~12,000
     * pointless splits per bill. A caller passing an ad-hoc list — the tests do
     * — falls through to [words] and still works.
     */
    private val KEYWORD_WORDS: Map<String, List<String>> =
        (TOTAL + SUBTOTAL + TAX + DISCOUNT + TENDER + ADMIN + REFUND)
            .distinct()
            .associateWith { words(it) }
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
        // **The totals block begins at a SUBTOTAL or TOTAL row, never at a
        // TAX one**, and getting that wrong cost five items out of six on the
        // first real GST invoice this met.
        //
        // The original rule took the first row of *any* summary kind, on the
        // assumption that tax appears once, in a block at the bottom. An
        // Indian GST tax invoice does not work that way: it prints
        // `S GST 9% / C GST 9%` under **every single item**. So the first
        // item's own tax rows closed the item block at line 12 of 30, and
        // every product below was read as tender.
        //
        // A tax row is therefore a per-line annotation that may appear
        // anywhere, and only a stated subtotal or total marks the end of the
        // shopping. Bills that do print tax once, at the bottom, are
        // unaffected: their tax rows are still classified TAX by keyword,
        // which is positional-independent.
        val firstSummary = rows.indexOfFirst { it.summaryKind().endsTheItemBlock() }

        return rows.mapIndexed { index, row ->
            row.summaryKind() ?: row.placeByPosition(index, firstPriced, firstSummary)
        }
    }

    /**
     * Only a stated subtotal or total closes the shopping.
     *
     * A `TAX` row does not, and that is the whole of the GST-invoice fix: an
     * Indian tax invoice prints `S GST 9% / C GST 9%` under **every item**, so
     * treating the first tax row as the start of the totals block closed the
     * item list four lines into a thirty-line bill and lost five of six
     * products.
     */
    private fun ReceiptLineKind?.endsTheItemBlock(): Boolean =
        this == ReceiptLineKind.SUBTOTAL || this == ReceiptLineKind.TOTAL

    /** Where a row sits, for the rows their own text does not classify. */
    private fun ClassifiableRow.placeByPosition(
        index: Int,
        firstPriced: Int,
        firstSummary: Int,
    ): ReceiptLineKind {
        val belowSummary = firstSummary >= 0 && index >= firstSummary
        return when {
            firstPriced < 0 || index < firstPriced -> ReceiptLineKind.HEADER
            isAdministrative ->
                if (belowSummary) ReceiptLineKind.FOOTER else ReceiptLineKind.NOISE
            // Below the summary block, a priced row is tender or a
            // pleasantry, never shopping.
            firstSummary in 0..<index -> ReceiptLineKind.FOOTER
            !hasAmount || name.isBlank() -> ReceiptLineKind.NOISE
            else -> ReceiptLineKind.ITEM
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
