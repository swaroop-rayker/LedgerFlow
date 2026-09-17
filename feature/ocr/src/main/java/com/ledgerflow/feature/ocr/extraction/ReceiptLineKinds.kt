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
        "BILL AMOUNT", "TOTAL AMOUNT", "बिल राशि", "कुल योग", "कुल",
    ) + INVOICE_TOTAL + "TOTAL"

    /**
     * **Totals phrased with the word INVOICE**, which ADMIN would otherwise
     * claim first.
     *
     * The administrative check runs before every keyword set, deliberately,
     * so that `TOTAL QTY` cannot be the bill. But `INVOICE` is in ADMIN too, so
     * Zepto's `Invoice Value 195.00` and bigbasket's `Total Invoice value (In
     * Figure): Rs.1776.17` were both thrown away as identifiers. On Zepto the
     * total still came out right, by luck — `Item Total` printed the same
     * figure a line above. Any delivery fee or packaging charge separates the
     * two, and last-wins would then have reported the item subtotal as the
     * bill.
     *
     * So these phrases are exempted from ADMIN *by name* rather than by moving
     * `INVOICE` out of it: `INVOICE NO 4521` is still an identifier.
     */
    val INVOICE_TOTAL: List<String>
        get() = listOf("TOTAL INVOICE VALUE", "INVOICE VALUE", "INVOICE AMOUNT", "INVOICE TOTAL")

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
     * **Neither entry is redundant, because ML Kit does both things.** Measured
     * on the device against a page printing `S GST 9%` four times, it returned
     * `S | 6ST | 9%` once and glued the label three times as `C6ST` / `S6ST`:
     *
     * - the **separated** form needs the spaced keyword. `S 6ST` against
     *   `S GST` is a five-character window; against the bare three-character
     *   `GST` it is not reachable at all, and must not be — `6ST`/`GST` scores
     *   0.7778, which is *below* what `GET` scores against `GST` (0.80).
     * - the **glued** form needs [foldConfusableGlyphs]. `S6ST` against `SGST`
     *   scores 0.8500, and no threshold can admit it: `CURD`/`CARD` scores
     *   0.8500 to the same four places. Folding makes the two strings
     *   identical instead, so the match is exact rather than tolerant.
     *
     * **In neither case was the answer a looser threshold** — that is the one
     * version of this that also reads `BAT` as VAT and `CURD` as CARD.
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
        // Spelled out because a keyword now has to end at a word boundary
        // (BUG24): `TENDERED` no longer contains `TENDER` as a match, and
        // `MASTERCARD` no longer contains `CARD`.
        "TENDERED", "MASTERCARD",
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
        // The inflections a word boundary would otherwise refuse (BUG24).
        // Listed rather than matched as open stems: a `THANK*` stem also
        // takes `THANKSGIVING CARD`, and `INVOICE*` takes `INVOICE BOOK`'s
        // neighbours. `THANK YOU` is here for its glued form `THANKYOU`.
        "THANKS", "THANK YOU", "INVOICE NO",
    )

    /**
     * Words that mean the amount is coming back to the customer.
     *
     * `RETURNS` is spelled out rather than reached by a `RETURN*` stem, which
     * would also read a `RETURNABLE BOTTLE` as a refund (BUG24).
     */
    val REFUND = listOf("REFUND", "CREDIT NOTE", "RETURN", "RETURNS", "REVERSAL")

    /**
     * Does [text] — **already uppercased by the caller** — carry one of
     * [keywords]?
     *
     * Two layers, in this order, and the order is not cosmetic.
     *
     * **1. Exact, at a word boundary (BUG24).** This layer was a bare
     * `text.contains(keyword)`, and a keyword is a substring of a great many
     * real item names: `PANEER` carries `PAN`, `CARDAMOM` carries `CARD`,
     * `CASHEW` carries `CASH`, `DATES` carries `DATE`, `CINNAMON` carries `CIN`
     * and `शक्कर` carries `कर`. Each of those dropped a purchase from the bill.
     * Measured over 300 real item lines, substring misfiled **87**; see
     * [exactMatches] for the rule and what each of its clauses protects.
     *
     * The three ordering traps this object's KDoc describes (`SUB TOTAL`,
     * `TOTAL SAVING`, `TOTAL QTY`) are untouched, because every keyword in them
     * is a whole word on the page: the boundary rule only removes matches
     * *inside* a word. The fuzzy layer can still only ever **add** a match.
     *
     * **2. One glyph's worth of doubt, at a word boundary.** §12 already
     * concedes the principle for item names — "OCR legitimately reads
     * `TOMATO 1KG` as `TOMAT0 1KG`" — and keywords are words too. A token
     * window matches a keyword when all of these hold, measured on both sides
     * after [foldConfusableGlyphs]:
     *
     * - **the same length**, because a substituted glyph preserves length while
     *   an inserted or dropped character does not. Dropping this clause is what
     *   makes `REFINED` match `REFUND` (0.8944) — and `REFUND` decides the
     *   direction of the whole receipt, so a bottle of refined oil would turn a
     *   purchase into a credit. It also gives `TIL` ~ `TILL`, `PAD` ~ `PAID`,
     *   `CHANA` ~ `CHANGE`, `CASHEW` ~ `CASHIER` and `BILL NO` ~ `BILL AMOUNT`:
     *   **ten** wrong lines measured over real Indian retail item names.
     * - **at most one differing position** — currently subsumed by the
     *   threshold, kept as a structural floor. See [isOneGlyphOff].
     * - **[KEYWORD_THRESHOLD]**, which is 0.89 and derived rather than chosen:
     *   see its own note. Dropping it admits **fourteen** more, almost all on
     *   three-character keywords: `TEA` ~ `TEL`, `TIL` ~ `TIN`, `CAN` ~ `PAN`,
     *   `PEN` ~ `PAN`, `BAT` ~ `VAT`, `MAT` ~ `VAT`, `CURD` ~ `CARD`,
     *   `CASE` ~ `CASH`. **The threshold is the length floor**, which is why
     *   there is no length constant here: no single substitution can reach it
     *   below five characters, so short keywords match only after folding makes
     *   them identical, never on a score.
     *
     * Together they admit **zero** new wrong lines over that vocabulary while
     * catching every misread form the device produced. A transposition is
     * deliberately not admitted: a glyph recogniser substitutes characters it
     * misreads, it does not swap adjacent ones — that is a typing failure, not
     * an OCR one.
     */
    fun matches(text: String, keywords: List<String>): Boolean {
        if (keywords.any { exactMatches(text, it) }) return true
        val words = words(text)
        return words.isNotEmpty() && keywords.any { fuzzyMatches(words, it) }
    }

    /**
     * Is [text] a tender row — a payment line and **nothing else**?
     *
     * Matching [TENDER] is necessary and no longer sufficient. A boundary rule
     * cannot help when the keyword is a whole word of the item's own name —
     * `CHANGE MAKER TOY`, `CASH KARO VOUCHER`, `GREETING CARD`, `TENDER
     * COCONUT` — and a tender row is dropped from the bill wherever it sits. So
     * a tender row must consist only of tender words (fuzzily, so `CA5H` still
     * counts), [TENDER_FILLER], and figures, masked or not (`XXXX1234`,
     * `RS500`).
     *
     * **The trade is measured, not free, and it is lopsided on purpose.** Over
     * 300 real item lines it removes six whole-word misfilings — the four above,
     * `TENDERED MEAT` and `THANKSGIVING CARD` — and adds none. Over 147 label
     * rows it costs one: `HDFC CARD XXXX1234`, because a bank's name is not a
     * word this list can enumerate. A missed tender row matters only on a slip
     * that prints no total, since below a total the row is footer by position;
     * a misfiled item is lost from every bill it appears on.
     */
    fun isTenderRow(text: String): Boolean {
        if (!matches(text, TENDER)) return false
        return words(text).all { word ->
            word in TENDER_FILLER ||
                MASKED_FIGURE.matches(word) ||
                TENDER_WORDS.any { it == word || isOneGlyphOff(word, it) }
        }
    }

    /**
     * [keyword], or its glued form, bounded on both sides by something that is
     * not part of a word.
     *
     * - **Both edges.** Only the left edge refuses nothing that matters:
     *   `PANEER`, `CARDAMOM` and `DATES` all *start* with their keyword (65 of
     *   87 misfilings survive it). Only the right edge leaves `HOTEL` and
     *   `JAPANESE`.
     * - **A digit is a boundary.** `GSTIN29AAACT2727Q1ZW` is one run from ML
     *   Kit more often than not, and `CGST2.5%` is how a text layer glues a
     *   rate to its label. Counting digits as word characters loses both.
     * - **A combining mark is part of a word**, not a boundary. Devanagari
     *   vowel signs and the virama are marks rather than letters, so without
     *   this `कुल` would still match inside `कुल्फी` and `कर` inside `शक्कर`.
     * - **The glued form of a multi-word keyword** — `GRANDTOTAL`, `BILLNO`,
     *   `TOTALQTY`. Real ML Kit glued `C GST` into `C6ST` on the device, and the
     *   boundary would otherwise refuse what substring used to find by accident.
     *
     * The inflections that substring also found by accident (`THANKS`,
     * `RETURNS`, `TENDERED`) are **spelled out in the sets**, not admitted by a
     * stem: stems measured one more misfiling than they recovered labels.
     */
    private fun exactMatches(text: String, keyword: String): Boolean =
        (EXACT_FORMS[keyword] ?: exactForms(keyword)).any { form -> occursAsWord(text, form) }

    private fun occursAsWord(text: String, form: String): Boolean {
        var start = text.indexOf(form)
        while (start >= 0) {
            val end = start + form.length
            val leftClear = start == 0 || !text[start - 1].isWordCharacter()
            val rightClear = end == text.length || !text[end].isWordCharacter()
            if (leftClear && rightClear) return true
            start = text.indexOf(form, start + 1)
        }
        return false
    }

    private fun Char.isWordCharacter(): Boolean =
        isLetter() ||
            category == CharCategory.NON_SPACING_MARK ||
            category == CharCategory.COMBINING_SPACING_MARK

    private fun exactForms(keyword: String): List<String> {
        val glued = keyword.replace(GLUE_SEPARATORS, "")
        return if (glued == keyword || glued.isEmpty()) listOf(keyword) else listOf(keyword, glued)
    }

    private val GLUE_SEPARATORS = Regex("""[\s-]+""")

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
     * The clauses, cheapest first — and `&&` short-circuits, so the length
     * test is what stops [differingPositions] from being handed strings it
     * cannot compare.
     *
     * Both sides are [foldConfusableGlyphs]ed before anything is measured.
     * Folding is a 1:1 character map, so it never changes a length and the
     * first clause is unaffected by it.
     *
     * **The `<= 1` clause is currently subsumed by the threshold and is kept
     * anyway.** At [KEYWORD_THRESHOLD] nothing the cap would reject survives
     * the score either — `CASHEWS`/`CASHIER` is the worst case at 0.8857 — so
     * it catches nothing the measured vocabulary contains. It stays as a
     * structural floor that does not depend on a float: one glyph's worth of
     * doubt on a short keyword, whatever the threshold is later set to. Said
     * out loud rather than left to imply it is load-bearing, which is how the
     * redundant `%` rejection in `ReceiptNumbers` is handled too.
     */
    private fun isOneGlyphOff(window: String, keyword: String): Boolean {
        val a = foldConfusableGlyphs(window)
        val b = foldConfusableGlyphs(keyword)
        return a.length == b.length &&
            differingPositions(a, b) <= 1 &&
            JaroWinkler.similarity(a, b) >= KEYWORD_THRESHOLD
    }

    /**
     * Digits that OCR reads for letters, folded onto the letter.
     *
     * **This is the half of the fix the device found, and the JVM could not.**
     * Fed a page printing `S GST 9%`, real ML Kit glued the label on three of
     * four tax rows and returned `C6ST` / `S6ST` rather than `S | 6ST`. A glued
     * run is a four-character window against the four-character `SGST`, one
     * position different, scoring **0.8500** — and no threshold can admit it,
     * because `CURD`/`CARD` scores 0.8500 to the same four places and curd is
     * not a tender row. The hand-laid JVM fixtures never showed this because
     * `ReceiptFixtures.row` splits its text on spaces.
     *
     * Folding solves it structurally instead: `S6ST` and `SGST` become the
     * **same string**, so the comparison is exact rather than tolerant, and no
     * tolerance has to be widened to admit it. The same table also earns
     * `CA5H`, `DI5COUNT`, `SUBT0TAL`, `T0TAL`, `B1LL NO` and `T0KEN` — and
     * `25OML` for `250ML`, which ML Kit produced unprompted on that same page.
     *
     * **Why this is not the glyph correction §12 forbids.** That rule is about
     * *money*: `ReceiptNumbers` stays integer-only and exact, and a digit there
     * is never reinterpreted — `4O.00` is still rejected rather than read as
     * ₹40.00. This table is consulted only when comparing a row's words
     * against a fixed keyword list, decides only a row's *kind*, and never
     * touches a value. A keyword is a word, and §12 already concedes that words
     * compare with a tolerance.
     *
     * **Measured, not assumed: it admits nothing.** Over 116 real Indian
     * retail item rows — deliberately including 40 that carry digits, which is
     * what folding turns into letters — the folded rule produces **zero** new
     * wrong lines. The dangerous short keywords are the reason it is safe:
     * `PAN`, `TEL`, `TIN`, `VAT`, `CARD` and `DATE` contain no confusable
     * character at all, so folding cannot bring `CURD`, `TEA`, `TIL`, `BAT` or
     * `DATES` any closer to them than they already were.
     *
     * Uppercase only, because every caller uppercases first.
     */
    private fun foldConfusableGlyphs(text: String): String {
        if (text.none { it in CONFUSABLE_DIGITS }) return text
        return buildString(text.length) {
            text.forEach { append(CONFUSABLE_GLYPHS[it] ?: it) }
        }
    }

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
     * §5.5's 0.88 is for *merchant* names, and it is too loose here.
     *
     * **Derived, not chosen.** A four-character keyword with one substituted
     * character tops out at **0.8833** (the substitution in the last position,
     * collecting a three-character prefix bonus), and `CASE`/`CASH` is exactly
     * that case — so at 0.88 a phone case or a soap case is read as a tender
     * row and vanishes from the bill. This has to sit above 0.8833; 0.89 is the
     * next step that does. It also puts `CASHEWS`/`CASHIER` (0.8857) out of
     * reach, which used to need [differingPositions] to refuse it.
     *
     * A separate constant rather than a change to
     * [JaroWinkler.MERCHANT_THRESHOLD], because that number is specified by
     * §5.5 for a genuinely different comparison: long shop names, where a
     * single wrong character is a far smaller share of the evidence. One
     * algorithm, two thresholds, each stated where it applies.
     */
    private const val KEYWORD_THRESHOLD = 0.89

    /** See [foldConfusableGlyphs]. Digit -> the letter OCR mistook it for. */
    private val CONFUSABLE_GLYPHS = mapOf(
        '0' to 'O',
        '1' to 'I',
        '2' to 'Z',
        '5' to 'S',
        '6' to 'G',
        '8' to 'B',
    )

    private val CONFUSABLE_DIGITS = CONFUSABLE_GLYPHS.keys

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

    /** Every keyword's [exactForms], built once for the same reason. */
    private val EXACT_FORMS: Map<String, List<String>> =
        (TOTAL + SUBTOTAL + TAX + DISCOUNT + TENDER + ADMIN + REFUND)
            .distinct()
            .associateWith { exactForms(it) }

    /** The words [TENDER]'s keywords are made of, for [isTenderRow]. */
    private val TENDER_WORDS: Set<String> = TENDER.flatMap { words(it) }.toSet()

    /**
     * Words a payment line prints around its keyword that are not shopping.
     *
     * Card networks are here rather than in [TENDER] so that they never *make*
     * a row a tender row on their own; they only stop `VISA CARD` or `RUPAY
     * CARD` being refused as one. None of them can move an item line, and that
     * is structural rather than measured luck: a filler word only keeps a row
     * that already matched a tender keyword.
     */
    private val TENDER_FILLER: Set<String> = setOf(
        "BY", "VIA", "MODE", "PAYMENT", "AMOUNT", "RECEIVED", "RETURNED", "DUE",
        "NO", "NUMBER", "ID", "REF", "RS", "INR",
        "VISA", "RUPAY", "MAESTRO", "AMEX", "DEBIT", "CREDIT",
    )

    /** A figure, optionally currency-marked, optionally masked: `614`, `RS500`, `XXXX1234`. */
    private val MASKED_FIGURE = Regex("""(RS|INR|₹)?[0-9X]+""")
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
            ReceiptKeywords.isTenderRow(text) -> ReceiptLineKind.FOOTER
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
        get() = ReceiptKeywords.matches(upper, ReceiptKeywords.ADMIN) &&
            !ReceiptKeywords.matches(upper, ReceiptKeywords.INVOICE_TOTAL)
}
