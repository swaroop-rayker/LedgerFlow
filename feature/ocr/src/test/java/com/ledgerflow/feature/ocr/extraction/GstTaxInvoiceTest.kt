package com.ledgerflow.feature.ocr.extraction

import com.google.common.truth.Truth.assertThat
import com.ledgerflow.core.domain.ingest.Reconciliation
import com.ledgerflow.core.model.LineItemKind
import com.ledgerflow.core.model.Money
import org.junit.Test

/**
 * The Indian GST tax invoice, which the first synthetic bill did not resemble.
 *
 * **Every case here is a regression from one real receipt.** Pointed at a real
 * Food Bazaar bill, the extractor found 2 line items out of 6 and reported the
 * total as **₹75.00** on a ₹1,075.46 purchase. Three independent modelling
 * errors, each of which the hand-laid fixture could never have caught, because
 * that fixture was written by the same person who wrote the rules.
 *
 * The receipt itself and its hand transcription live in the private corpus
 * store; the *structure* is reproduced in [ReceiptFixtures.gstTaxInvoice] with
 * invented names, which is what can be said in public.
 */
class GstTaxInvoiceTest {

    private val extracted = ReceiptExtractor.extract(ReceiptFixtures.gstTaxInvoice())

    /**
     * **The one that mattered most.** A GST invoice prints `S GST 9%` and
     * `C GST 9%` under *every item*, not once at the bottom.
     *
     * The totals block used to be delimited by the first row of any summary
     * kind, tax included — so the first product's own tax rows closed the item
     * block four lines in, and everything below was read as tender. Five of
     * six products were silently lost, and the Inbox showed a bill with two
     * lines.
     */
    @Test
    fun perItemTaxRows_doNotEndTheItemBlock() {
        val items = extracted.lines.filter { it.kind == LineItemKind.ITEM }

        assertThat(items.map { it.name })
            .containsExactly("CRISPS 95G", "CRISPS 177G", "SHOWERGEL 250ML", "CLEANER JASMINE 2L")
            .inOrder()
    }

    /**
     * `TOTAL SAVING: 75.00` is a savings summary, not the bill.
     *
     * The DISCOUNT keyword list had `TOTAL SAVINGS` — plural — and the receipt
     * says `TOTAL SAVING`. So the discount check missed, `TOTAL` matched, and
     * because the **last** totals row wins it replaced a ₹1,075.46 bill with
     * ₹75.00. A one-letter gap in a list this file's own KDoc warns is matched
     * by exact substring.
     */
    @Test
    fun totalSaving_isNotTheBillTotal() {
        assertThat(extracted.amount).isEqualTo(Money(61_400L))
        assertThat(extracted.amount).isNotEqualTo(Money(7_500L))
    }

    /**
     * A savings summary is neither the total **nor a line of the bill**.
     *
     * Two wrong answers were available and the first fix found the second
     * one. Left in the TOTAL set it replaced the bill total; moved to the
     * DISCOUNT set it stopped being the total and started being *subtracted*
     * from the parts — but the per-item discounts it sums were already
     * applied in each NET AMT, so taking it off again removed them twice and
     * the bill came out ₹75 short. It is informational, like the HSN code.
     */
    @Test
    fun aSavingsSummary_isNeitherTheTotalNorAPart() {
        val kinds = ReceiptLineClassifier.classify(
            listOf(
                ClassifiableRow("TOMATO", "TOMATO", hasAmount = true),
                ClassifiableRow("TOTAL SAVING: 75.00", "TOTAL SAVING", hasAmount = true),
            ),
        )

        assertThat(kinds[1]).isNotEqualTo(ReceiptLineKind.TOTAL)
        assertThat(kinds[1]).isNotEqualTo(ReceiptLineKind.DISCOUNT)
        assertThat(extracted.lines.none { it.name.contains("SAVING") }).isTrue()
    }

    /** A discount genuinely charged as its own line is still a discount. */
    @Test
    fun aRealDiscountLine_isStillSubtracted() {
        val kinds = ReceiptLineClassifier.classify(
            listOf(
                ClassifiableRow("TOMATO", "TOMATO", hasAmount = true),
                ClassifiableRow("DISCOUNT", "DISCOUNT", hasAmount = true),
            ),
        )

        assertThat(kinds[1]).isEqualTo(ReceiptLineKind.DISCOUNT)
    }

    /**
     * `HSN : 2005   UOM : Pcs` is a classification code, not shopping.
     *
     * Defensive rather than a confirmed regression, and worth saying which:
     * on the real bill the two GST rows are what reached the Inbox as items,
     * and whether the HSN row also did could not be told from the screenshot.
     * What is certain is that the code parses as ₹20.05 whenever the
     * recogniser puts it in its own cell, and a classification code is never
     * a purchase.
     */
    @Test
    fun theHsnAndUomRow_isNotAnItem() {
        // Asserted against the classifier directly rather than through the
        // page. Routed through the fixture the assertion passed whether or
        // not the keyword existed -- the row's geometry happened to make it
        // unreadable either way -- so it was proving nothing. A mutation
        // sweep caught that, which is the second thing on this bill that
        // looked tested and was not.
        val kinds = ReceiptLineClassifier.classify(
            listOf(
                ClassifiableRow("TOMATO 1KG", "TOMATO 1KG", hasAmount = true),
                ClassifiableRow("HSN : 2005 UOM : PCS", "HSN :", hasAmount = true),
                ClassifiableRow("CRISPS 95G", "CRISPS 95G", hasAmount = true),
            ),
        )

        assertThat(kinds[1]).isNotEqualTo(ReceiptLineKind.ITEM)
        // And it must not end the item block either.
        assertThat(kinds[2]).isEqualTo(ReceiptLineKind.ITEM)
        assertThat(extracted.lines.none { it.name.contains("HSN") }).isTrue()
    }

    /**
     * **Tax is inside the prices on this format**, and the arithmetic is what
     * says so.
     *
     * The four item NET AMTs sum to the printed total on their own; the GST
     * rows restate tax already inside them. Added on top, this bill would read
     * ₹22.80 over — and on the real receipt, ₹171.36 over, which looks exactly
     * like an extraction failure and is not one.
     *
     * Nothing on the paper distinguishes an inclusive bill from an additive
     * one, so `Reconciliation` computes both and takes the reading that closes.
     */
    @Test
    fun aTaxInclusiveBill_reconciles() {
        val verdict = Reconciliation.of(extracted.lines, extracted.amount)

        assertThat(verdict).isInstanceOf(Reconciliation.Balanced::class.java)
        assertThat(verdict.delta).isEqualTo(Money.ZERO)
    }

    /** The tax rows are still *read* — they are simply not added twice. */
    @Test
    fun theTaxRowsAreStillExtracted() {
        val tax = extracted.lines.filter { it.kind == LineItemKind.TAX }

        assertThat(tax).isNotEmpty()
        assertThat(tax.map { it.total })
            .containsExactly(Money(534L), Money(534L), Money(419L), Money(1_327L))
    }

    /**
     * An **additive** bill must still reconcile the additive way.
     *
     * The inclusive reading is a fallback, not a replacement: a bill that
     * prints a net subtotal and adds GST to it is the case §5.3's formula was
     * written for, and making the new rule win there would break it.
     */
    @Test
    fun anAdditiveBill_stillReconcilesTheStatedWay() {
        val additive = ReceiptFixtures.page(
            ReceiptFixtures.row(0, ReceiptFixtures.LEFT to "VALUE MART"),
            ReceiptFixtures.row(
                1,
                ReceiptFixtures.LEFT to "TOMATO 1KG",
                ReceiptFixtures.amount("100.00"),
            ),
            ReceiptFixtures.row(
                2,
                ReceiptFixtures.LEFT to "CGST 9%",
                ReceiptFixtures.amount("9.00"),
            ),
            ReceiptFixtures.row(
                3,
                ReceiptFixtures.LEFT to "GRAND TOTAL",
                ReceiptFixtures.amount("109.00"),
            ),
        )

        val bill = ReceiptExtractor.extract(additive)

        assertThat(bill.amount).isEqualTo(Money(10_900L))
        assertThat(Reconciliation.of(bill.lines, bill.amount))
            .isInstanceOf(Reconciliation.Balanced::class.java)
    }

    /** A bill that closes neither way is still reported as unbalanced. */
    @Test
    fun aBillThatClosesNeitherWay_isUnbalanced() {
        val broken = ReceiptFixtures.page(
            ReceiptFixtures.row(0, ReceiptFixtures.LEFT to "VALUE MART"),
            ReceiptFixtures.row(
                1,
                ReceiptFixtures.LEFT to "TOMATO 1KG",
                ReceiptFixtures.amount("100.00"),
            ),
            ReceiptFixtures.row(
                2,
                ReceiptFixtures.LEFT to "GRAND TOTAL",
                ReceiptFixtures.amount("999.00"),
            ),
        )

        val bill = ReceiptExtractor.extract(broken)

        assertThat(Reconciliation.of(bill.lines, bill.amount))
            .isInstanceOf(Reconciliation.Unbalanced::class.java)
    }

    /** The merchant is still the tallest header row, not a tax line. */
    @Test
    fun theMerchant_isTheShopName() {
        assertThat(extracted.merchantRaw).isEqualTo("VALUE MART RETAIL LTD")
    }
}
