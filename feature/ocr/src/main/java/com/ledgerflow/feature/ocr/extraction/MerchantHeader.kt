package com.ledgerflow.feature.ocr.extraction

/**
 * Step 9 of §5.3: the shop's name, from the top of the bill.
 *
 * §5.3 says "merchant detection from the top-3 header lines", and leaves open
 * *which* of the three. On a real receipt they are typically the name, the
 * street address and the phone or GSTIN — in that order more often than not,
 * but not reliably: plenty of chains print a franchise code first, and plenty
 * of kirana slips print the address above the name.
 *
 * ## The signal is the type size, and it is why the geometry was kept
 *
 * A shop prints its own name larger than its address. That is nearly universal
 * and it is a fact a flat string has thrown away — which is the concrete
 * payoff of `RecognizedElement` carrying bounding boxes through the whole
 * pipeline. So among the candidate header rows, the **tallest** wins, with the
 * earliest breaking a tie.
 *
 * Falling back to "the first line" when the sizes are equal is the honest
 * default: on a uniform-width thermal slip there is no size signal, and first
 * is then the best available guess.
 *
 * ## It produces `merchantRaw` and stops there
 *
 * Exactly as written — `ExtractedTransaction.merchantRaw`'s contract is "the
 * merchant exactly as written, never normalised here". Resolution happens at
 * approval through `MerchantRepository.createOrGet` (§5.1, the P2-4 decision),
 * and §5.5's Jaro-Winkler ≥ 0.88 match is a *suggestion* offered at review
 * time, never a gate and never applied here. A receipt that names a shop the
 * vault has never seen must still produce a candidate.
 */
internal object MerchantHeader {

    /**
     * How many header rows are considered.
     *
     * §5.3's number. Going deeper starts picking up `GSTIN` and street
     * addresses, which the filters below would mostly reject anyway — but a
     * rejected candidate on row 7 is a row that was never the shop's name, and
     * widening the window only adds ways to be wrong.
     */
    private const val CANDIDATE_ROWS = 3

    /** Shorter than this is a logo fragment or a stray glyph, not a name. */
    private const val MIN_NAME_LENGTH = 3

    /**
     * Above this share of digits, the row is an address, a phone number or a
     * registration — never a shop's name.
     *
     * A third is deliberately generous: `24X7 STORES` and `SHOP 7 PROVISIONS`
     * are real names with real digits in them, and excluding them to catch a
     * phone number would be trading a common case for a rare one.
     */
    private const val MAX_DIGIT_SHARE = 0.34f

    /**
     * The shop's name as printed, or null.
     *
     * Null is a legitimate outcome — a photograph of the item block alone has
     * no header — and it is not a failure: the candidate reaches the review
     * screen with the merchant row empty and the user fills it, which is one
     * tap against a wrong shop that files a month of history to the wrong
     * place.
     */
    fun detect(headerRows: List<ReceiptRow>): String? {
        val candidates = headerRows.take(CANDIDATE_ROWS).filter { it.isPlausibleName() }
        if (candidates.isEmpty()) return null

        // Tallest wins; earliest breaks the tie. `maxByOrNull` already returns
        // the first maximum, so the tie-break is the iteration order and needs
        // no comparator of its own.
        return candidates.maxByOrNull { it.height }?.text?.trim()?.withoutSellerLabel()
    }

    /**
     * Drops a printed label in front of the name.
     *
     * Zepto's invoice heads the page `Seller Name: Geddit Convenience Private
     * Limited`, in the largest type on it, so height correctly picked the row
     * and the label came along with the shop. §5.5's merchant normalisation
     * would then key the merchant as `seller name geddit…`, a different shop
     * from every other bill that names Geddit plainly.
     *
     * Only a label **followed by a colon** at the very start: `SELLER` on its
     * own could be the shop.
     */
    private fun String.withoutSellerLabel(): String {
        val label = SELLER_LABELS.firstOrNull { startsWith(it, ignoreCase = true) } ?: return this
        return drop(label.length).trim().ifEmpty { this }
    }

    private val SELLER_LABELS = listOf(
        "SELLER NAME:", "SELLER:", "SOLD BY:", "SUPPLIER NAME:", "SUPPLIER:", "MERCHANT:",
    )

    private fun ReceiptRow.isPlausibleName(): Boolean {
        val trimmed = text.trim()
        val digits = trimmed.count(Char::isDigit)

        // One expression, and the length test is first: `&&` short-circuits,
        // so the division below can never see a zero.
        return trimmed.length >= MIN_NAME_LENGTH &&
            // An identifier row says what it is. `GSTIN: 29AAACT…` and
            // `TAX INVOICE` are both above the first price and neither is a shop.
            !ReceiptKeywords.matches(trimmed.uppercase(), ReceiptKeywords.ADMIN) &&
            digits.toFloat() / trimmed.length <= MAX_DIGIT_SHARE &&
            // A name has letters in it. A row of dashes or asterisks -- the
            // rule every thermal printer draws under its header -- has none.
            trimmed.any(Char::isLetter)
    }
}
