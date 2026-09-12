package com.ledgerflow.feature.ocr.extraction

import com.ledgerflow.core.domain.ingest.ExtractedDirection
import com.ledgerflow.core.domain.ingest.ExtractedLineItem
import com.ledgerflow.core.domain.ingest.ExtractedTransaction
import com.ledgerflow.core.domain.ingest.Reconciliation
import com.ledgerflow.core.model.LineItemKind
import com.ledgerflow.core.model.Money
import com.ledgerflow.feature.ocr.recognition.RecognizedPage

/**
 * §5.3's extraction pipeline, steps 6 to 12 (`docs/OCR-PIPELINE.md` section B).
 *
 * ```
 * RecognizedPage
 *   -> rows            (6: cluster runs into printed lines by y-centroid band)
 *   -> cells           (7a: split each row at the column gutters)
 *   -> columns         (7b: rightmost amount, leftmost name, qty x rate if it closes)
 *   -> kinds           (8: HEADER | ITEM | TAX | DISCOUNT | SUBTOTAL | TOTAL | FOOTER | NOISE)
 *   -> merchant        (9: the tallest plausible header row)
 *   -> total           (10: the keyword set)
 *   -> reconciliation  (11: |parts - total| <= max(₹1, 0.5%))
 *   -> ExtractedTransaction with lines   (12, ADR-0022)
 * ```
 *
 * **Every step is arithmetic and every step is a JVM test.** Nothing here
 * imports `com.google.mlkit`, decodes a bitmap or touches a database. That is
 * the property `ReceiptTextRecognizer` returning [RecognizedPage] exists to
 * give, and it is what lets the corpus test the extractor and the recogniser
 * separately rather than testing their composition and guessing which failed.
 *
 * ## What this does not do yet, stated rather than hidden
 *
 * - **No date detection.** §5.3's pipeline does not list one, and the review
 *   screen falls back to the capture time. A receipt photographed days later
 *   therefore lands on the wrong day until the user corrects it. Worth doing;
 *   not done here, and not silently half-done.
 * - **No fuzzy merchant match.** Step 9 produces `merchantRaw` and stops, which
 *   is that field's contract. §5.5's Jaro-Winkler ≥ 0.88 suggestion is a review
 *   -time surface and needs the merchant table, which this layer deliberately
 *   cannot see.
 * - **Nothing is written.** No `pending_transaction`, no `attachment`, no file.
 *   Those are steps 13 to 15, and Law 1 is not in reach from here.
 */
internal object ReceiptExtractor {

    /**
     * Reads a recognised page as a bill.
     *
     * [currency] is the install's base currency, used only to know how many
     * minor units a decimal point separates (`CurrencyExponent`). It is not a
     * claim about what the receipt is denominated in — the returned
     * `currency` carries that, and it is null unless the bill printed a marker.
     */
    fun extract(page: RecognizedPage, currency: String = "INR"): ExtractedTransaction {
        val rows = ReceiptGeometry.rows(page)
        if (rows.isEmpty()) return ExtractedTransaction(confidence = 0.0)

        // The *filtered* runs, not the raw page, so the whole pipeline works
        // from one scale rather than two.
        //
        // **No test distinguishes this, and the reason is worth recording.**
        // Speckle only ever drags the median *down*, so the raw-page scale
        // gives a narrower column gutter — and a narrow gutter splits a name
        // into cells that `ReceiptColumns.read` rejoins with a space. The
        // dangerous direction is a gutter too *wide*, which swallows the
        // amount into the name, and noise cannot cause that. Kept because one
        // scale is right and two is a latent disagreement, not because a
        // failing case was found.
        val scale = ReceiptGeometry.medianHeight(ReceiptGeometry.contentElements(page))
        val readings = rows.map { row ->
            ReceiptColumns.read(ReceiptGeometry.cells(row, scale), currency)
        }

        val kinds = ReceiptLineClassifier.classify(
            rows.mapIndexed { index, row ->
                ClassifiableRow(
                    upper = row.text.uppercase(),
                    name = readings[index]?.name.orEmpty(),
                    hasAmount = readings[index] != null,
                )
            },
        )

        val headerRows = rows.filterIndexed { index, _ -> kinds[index] == ReceiptLineKind.HEADER }
        val lines = buildLines(rows, readings, kinds)
        val total = detectTotal(readings, kinds)
        val reconciliation = Reconciliation.of(lines, total)

        return ExtractedTransaction(
            amount = total,
            currency = detectCurrency(rows),
            direction = detectDirection(rows, total),
            merchantRaw = MerchantHeader.detect(headerRows),
            // Deliberately absent: a receipt states no account, no instrument
            // and no reference the ledger wants. Inventing one from an invoice
            // number would put a shop's internal id in a field §5.1 means for
            // a bank's UTR.
            confidence = confidenceOf(lines, total, reconciliation, readings),
            lines = lines,
        )
    }

    /**
     * Step 10: **the LAST totals row wins.**
     *
     * A bill commonly prints more than one. `SUB TOTAL`, the tax breakdown,
     * `ROUND OFF`, then `GRAND TOTAL` — and on a restaurant bill, `TOTAL`
     * before service charge and `NET AMOUNT` after it. The figure the customer
     * paid is the last one printed, every time, because that is the order a
     * bill is computed in.
     *
     * Taking the largest instead would be wrong on a bill with a large
     * discount, and taking the first would be wrong on nearly every bill with
     * tax. `SUBTOTAL` is deliberately not a candidate: it is the sum before the
     * additions, and using it would under-report every taxed purchase.
     *
     * Null when no row matched the keyword set — §5.3's totals detection is
     * keyword-driven, and guessing "the biggest number on the page" would
     * produce a confident wrong amount on any bill that prints an MRP column.
     */
    private fun detectTotal(
        readings: List<ReceiptColumnReading?>,
        kinds: List<ReceiptLineKind>,
    ): Money? = kinds.indices
        .lastOrNull { kinds[it] == ReceiptLineKind.TOTAL }
        ?.let { readings[it]?.amount }

    /**
     * Step 12's `lines`: the rows that are parts of the bill.
     *
     * `ITEM`, `TAX` and `DISCOUNT` only. A `SUBTOTAL` or `TOTAL` row is the sum
     * and carrying it as a line would double the bill the first time anything
     * added them up — including [Reconciliation], which would then call every
     * receipt balanced for the wrong reason.
     *
     * **A discount is stored negative**, matching `line_item`'s convention
     * (`Ledger.kt`). A bill prints `DISCOUNT 50.00` without a sign far more
     * often than with one, so the sign is applied here rather than trusted from
     * the page — and applied as `-absolute` rather than as a negation, so a
     * bill that *did* print `-50.00` does not come back positive.
     */
    private fun buildLines(
        rows: List<ReceiptRow>,
        readings: List<ReceiptColumnReading?>,
        kinds: List<ReceiptLineKind>,
    ): List<ExtractedLineItem> = rows.indices.mapNotNull { index ->
        val reading = readings[index] ?: return@mapNotNull null
        val kind = when (kinds[index]) {
            ReceiptLineKind.ITEM -> LineItemKind.ITEM
            ReceiptLineKind.TAX -> LineItemKind.TAX
            ReceiptLineKind.DISCOUNT -> LineItemKind.DISCOUNT
            else -> return@mapNotNull null
        }

        val total = if (kind == LineItemKind.DISCOUNT) -reading.amount.absolute else reading.amount
        ExtractedLineItem(
            // A tax or discount row's "name" is the label that identified it;
            // an item's is what was bought. Falling back to the row's own text
            // keeps a line that had a price and an unreadable name visible,
            // which is §5.1's never-silently-drop rule one grain down.
            name = reading.name.ifBlank { rows[index].text }.trim(),
            kind = kind,
            quantityMilli = reading.quantity?.milli,
            unitPrice = reading.unitPrice,
            total = total,
            confidence = if (reading.closes) CLOSED_LINE_CONFIDENCE else OPEN_LINE_CONFIDENCE,
        )
    }

    /** The marker the bill printed, if it printed one. INR or nothing (D-02). */
    private fun detectCurrency(rows: List<ReceiptRow>): String? =
        rows.firstNotNullOfOrNull { ReceiptNumbers.currencyMarkerIn(it.text) }

    /**
     * A receipt is spend — unless it says otherwise.
     *
     * `DEBIT` rather than `UNKNOWN` because the review screen only offers a
     * book control when the direction is unread (`bookIsUnread`), and making
     * every single receipt ask "expense or income?" would be one extra tap on
     * every bill to serve a case that is a fraction of a percent of them.
     *
     * The exceptions are the ones that would otherwise be silently wrong and
     * unfixable: a refund slip, a credit note, or a bill whose total came out
     * negative. Those return `UNKNOWN`, which is what puts the book control
     * back on the screen so the user can choose.
     */
    private fun detectDirection(rows: List<ReceiptRow>, total: Money?): ExtractedDirection {
        val refund = rows.any { ReceiptKeywords.matches(it.text.uppercase(), ReceiptKeywords.REFUND) }
        return if (refund || total?.isNegative == true) {
            ExtractedDirection.UNKNOWN
        } else {
            ExtractedDirection.DEBIT
        }
    }

    /**
     * How much of this reading to believe, in [0, 1].
     *
     * A score, not money — Law 3 bans `Double` for an amount, not for a
     * probability. It exists because the Inbox sorts attention by it and
     * because §12's precision half needs a machine-readable "this one is
     * shaky", and it is built from three things the extractor actually knows
     * rather than from a feeling:
     *
     * - **a total was found at all** — without one nothing downstream can be
     *   checked, so this is the largest single term
     * - **the bill reconciles** — the strongest evidence available that the
     *   lines were read correctly, because it is the only check that uses a
     *   number the extractor did not itself produce
     * - **how many item rows closed their own `qty × rate`** — per-row
     *   corroboration, which is why [ReceiptColumnReading.closes] is carried
     *   this far
     *
     * The weights are stated, not tuned. Tuning them against anything before
     * the corpus exists would be fitting to the one receipt in front of us,
     * which is the mistake `guard-corpus-order.sh` exists to prevent one level
     * up.
     */
    private fun confidenceOf(
        lines: List<ExtractedLineItem>,
        total: Money?,
        reconciliation: Reconciliation,
        readings: List<ReceiptColumnReading?>,
    ): Double {
        if (total == null && lines.isEmpty()) return 0.0

        val hasTotal = if (total != null) WEIGHT_TOTAL else 0.0
        val balances = if (reconciliation is Reconciliation.Balanced) WEIGHT_BALANCED else 0.0

        val priced = readings.filterNotNull()
        val closedShare = if (priced.isEmpty()) {
            0.0
        } else {
            priced.count { it.closes }.toDouble() / priced.size
        }

        return hasTotal + balances + WEIGHT_CLOSED_ROWS * closedShare
    }

    private const val WEIGHT_TOTAL = 0.5
    private const val WEIGHT_BALANCED = 0.3
    private const val WEIGHT_CLOSED_ROWS = 0.2

    /** A row whose `qty × rate` reproduced its amount has checked itself. */
    private const val CLOSED_LINE_CONFIDENCE = 0.9

    /** A row that parsed but had nothing to check against. */
    private const val OPEN_LINE_CONFIDENCE = 0.6
}
