package com.ledgerflow.feature.ocr.extraction

import com.google.common.truth.Truth.assertThat
import com.ledgerflow.core.domain.ingest.ExtractedDirection
import com.ledgerflow.core.domain.ingest.Reconciliation
import com.ledgerflow.core.model.LineItemKind
import com.ledgerflow.core.model.Money
import com.ledgerflow.core.model.Quantity
import com.ledgerflow.feature.ocr.extraction.ReceiptFixtures.LEFT
import com.ledgerflow.feature.ocr.extraction.ReceiptFixtures.amount
import com.ledgerflow.feature.ocr.extraction.ReceiptFixtures.ordinaryBill
import com.ledgerflow.feature.ocr.extraction.ReceiptFixtures.page
import com.ledgerflow.feature.ocr.extraction.ReceiptFixtures.row
import com.ledgerflow.feature.ocr.recognition.RecognizedPage
import org.junit.Test

/**
 * Steps 6 to 12 end to end, off-device (`docs/OCR-PIPELINE.md` section B).
 *
 * The whole pipeline is arithmetic over `RecognizedElement`, which is what
 * `ReceiptTextRecognizer` returning its own type exists to buy — so this runs
 * on the JVM in milliseconds with no device, no bitmap and no ML Kit.
 *
 * **This is not §12's gate.** These pages are hand-laid, so they can only
 * confirm the rules behave as designed. The ≥90%/≥95% measurement is against
 * real photographs with hand-transcribed ground truth in the private store.
 * See [ReceiptFixtures].
 */
class ReceiptExtractorTest {

    @Test
    fun anOrdinaryBill_yieldsItsItems_inOrder() {
        val extracted = ReceiptExtractor.extract(ordinaryBill())

        val items = extracted.lines.filter { it.kind == LineItemKind.ITEM }
        assertThat(items.map { it.name })
            .containsExactly("TOMATO 1KG", "TOOR DAL 1KG", "MILK 500ML", "ATTA 5KG")
            .inOrder()
        assertThat(items.map { it.total })
            .containsExactly(Money(4_000L), Money(33_000L), Money(5_400L), Money(28_500L))
            .inOrder()
    }

    @Test
    fun anOrdinaryBill_yieldsItsTotal_fromTheKeywordSet() {
        assertThat(ReceiptExtractor.extract(ordinaryBill()).amount).isEqualTo(Money(69_446L))
    }

    /**
     * The subtotal is not the total, and taking it would under-report every
     * taxed purchase — here by ₹35.46 of GST less ₹50 of discount.
     */
    @Test
    fun theSubtotal_isNotMistakenForTheTotal() {
        val extracted = ReceiptExtractor.extract(ordinaryBill())

        assertThat(extracted.amount).isNotEqualTo(Money(70_900L))
        assertThat(extracted.lines.none { it.name.contains("SUB TOTAL") }).isTrue()
    }

    @Test
    fun taxAndDiscountRows_areCarriedWithTheirOwnKinds() {
        val lines = ReceiptExtractor.extract(ordinaryBill()).lines

        assertThat(lines.filter { it.kind == LineItemKind.TAX }.map { it.total })
            .containsExactly(Money(1_773L), Money(1_773L))
        assertThat(lines.single { it.kind == LineItemKind.DISCOUNT }.total)
            .isEqualTo(Money(-5_000L))
    }

    /** §5.3's reconciliation, over what the extractor itself produced. */
    @Test
    fun anOrdinaryBill_reconciles() {
        val extracted = ReceiptExtractor.extract(ordinaryBill())

        val verdict = Reconciliation.of(extracted.lines, extracted.amount)

        assertThat(verdict).isInstanceOf(Reconciliation.Balanced::class.java)
        assertThat(verdict.delta).isEqualTo(Money.ZERO)
    }

    /**
     * A bill that prints more than one total: **the last one is what was paid.**
     *
     * A restaurant bill prints `TOTAL`, then the service charge, then
     * `NET AMOUNT`. Taking the first would under-report the bill by the
     * service charge, every time. Another gap the mutation sweep found —
     * the ordinary fixture has exactly one totals row, so `first` and `last`
     * agreed on it.
     */
    @Test
    fun whenABillPrintsTwoTotals_theLastOneIsTheAmount() {
        val restaurant = page(
            row(0, LEFT to "CAFE CENTRAL"),
            row(1, LEFT to "FILTER COFFEE", amount("400.00")),
            row(2, LEFT to "TOTAL", amount("400.00")),
            row(3, LEFT to "SERVICE CHARGE", amount("40.00")),
            row(4, LEFT to "NET AMOUNT", amount("440.00")),
        )

        val extracted = ReceiptExtractor.extract(restaurant)

        assertThat(extracted.amount).isEqualTo(Money(44_000L))
        assertThat(Reconciliation.of(extracted.lines, extracted.amount))
            .isInstanceOf(Reconciliation.Balanced::class.java)
    }

    @Test
    fun tenderRows_doNotBecomeLines() {
        val lines = ReceiptExtractor.extract(ordinaryBill()).lines

        assertThat(lines.none { it.name.contains("CASH") }).isTrue()
        assertThat(lines.none { it.name.contains("CHANGE") }).isTrue()
    }

    /** Step 9: the shop's name, chosen by type size over the address. */
    @Test
    fun theMerchant_isTheTallestHeaderRow() {
        assertThat(ReceiptExtractor.extract(ordinaryBill()).merchantRaw)
            .isEqualTo("SRI LAKSHMI STORES")
    }

    /**
     * Type size, **not** position — which is the whole reason the bounding
     * boxes are carried this far.
     *
     * Plenty of kirana slips print the address above the name. A mutation
     * sweep caught this gap: swapping "tallest" for "first" turned nothing red,
     * because on the ordinary bill the tallest row is also the first one.
     */
    @Test
    fun theMerchant_isNotSimplyTheFirstHeaderRow() {
        val addressFirst = page(
            row(0, LEFT to "12 MAIN ROAD BENGALURU"),
            row(1, LEFT to "SRI LAKSHMI STORES", glyph = ReceiptFixtures.GLYPH * 1.5f),
            row(2, LEFT to "TOMATO 1KG", amount("40.00")),
            row(3, LEFT to "GRAND TOTAL", amount("40.00")),
        )

        assertThat(ReceiptExtractor.extract(addressFirst).merchantRaw)
            .isEqualTo("SRI LAKSHMI STORES")
    }

    @Test
    fun aPrintedQuantity_survivesToTheLine() {
        val dal = ReceiptExtractor.extract(ordinaryBill())
            .lines
            .single { it.name == "TOOR DAL 1KG" }

        assertThat(dal.quantityMilli).isEqualTo(Quantity(2_000L).milli)
        assertThat(dal.unitPrice).isEqualTo(Money(16_500L))
    }

    /** A receipt is spend, and the review screen should not have to ask. */
    @Test
    fun aPurchase_isADebit() {
        assertThat(ReceiptExtractor.extract(ordinaryBill()).direction)
            .isEqualTo(ExtractedDirection.DEBIT)
    }

    /**
     * A refund is the case where a silent `DEBIT` would be wrong *and*
     * uncorrectable — the review screen only offers a book control when the
     * direction is unread.
     */
    @Test
    fun aRefundSlip_leavesTheBookToTheUser() {
        val refund = page(
            row(0, LEFT to "SRI LAKSHMI STORES"),
            row(1, LEFT to "CREDIT NOTE"),
            row(2, LEFT to "TOMATO 1KG", amount("40.00")),
            row(3, LEFT to "GRAND TOTAL", amount("40.00")),
        )

        assertThat(ReceiptExtractor.extract(refund).direction)
            .isEqualTo(ExtractedDirection.UNKNOWN)
    }

    @Test
    fun aPrintedCurrencyMarker_isReported() {
        val marked = page(
            row(0, LEFT to "SRI LAKSHMI STORES"),
            row(1, LEFT to "TOMATO 1KG", amount("Rs.40.00")),
            row(2, LEFT to "GRAND TOTAL", amount("Rs.40.00")),
        )

        assertThat(ReceiptExtractor.extract(marked).currency).isEqualTo("INR")
        assertThat(ReceiptExtractor.extract(ordinaryBill()).currency).isNull()
    }

    /**
     * A bill with no totals keyword produces lines and no amount.
     *
     * Guessing "the largest number on the page" would be a confident wrong
     * total on any bill printing an MRP column, and §5.3's detection is
     * keyword-driven for exactly that reason. The candidate still reaches the
     * Inbox — §5.1's never-drop rule — with the amount for the user to supply.
     */
    @Test
    fun aBillWithNoTotalsKeyword_hasNoAmount_andStillHasItems() {
        val noTotal = page(
            row(0, LEFT to "SRI LAKSHMI STORES"),
            row(1, LEFT to "TOMATO 1KG", amount("40.00")),
            row(2, LEFT to "ATTA 5KG", amount("285.00")),
        )

        val extracted = ReceiptExtractor.extract(noTotal)

        assertThat(extracted.amount).isNull()
        assertThat(extracted.lines).hasSize(2)
        assertThat(Reconciliation.of(extracted.lines, extracted.amount))
            .isEqualTo(Reconciliation.NotPossible)
    }

    /**
     * The negative case §12 and the corpus README both call out: a photograph
     * that is not a receipt must extract nothing rather than hallucinate a bill.
     */
    @Test
    fun aPageThatIsNotAReceipt_extractsNothing() {
        val notAReceipt = page(
            row(0, LEFT to "PLEASE KEEP OFF THE GRASS"),
            row(1, LEFT to "BY ORDER OF THE COUNCIL"),
        )

        val extracted = ReceiptExtractor.extract(notAReceipt)

        assertThat(extracted.amount).isNull()
        assertThat(extracted.lines).isEmpty()
        assertThat(extracted.isReviewable).isFalse()
    }

    @Test
    fun anEmptyPage_extractsNothing_andSaysSo() {
        val extracted = ReceiptExtractor.extract(RecognizedPage(emptyList()))

        assertThat(extracted.lines).isEmpty()
        assertThat(extracted.confidence).isEqualTo(0.0)
    }

    /**
     * Confidence is evidence, not decoration.
     *
     * A bill that reconciles must score above one that does not, or nothing
     * downstream — the Inbox's ordering, §12's precision triage — can use the
     * number for anything.
     */
    @Test
    fun aBillThatReconciles_scoresAboveOneThatDoesNot() {
        val broken = page(
            row(0, LEFT to "SRI LAKSHMI STORES"),
            row(1, LEFT to "TOMATO 1KG", amount("40.00")),
            row(2, LEFT to "GRAND TOTAL", amount("999.00")),
        )

        assertThat(ReceiptExtractor.extract(ordinaryBill()).confidence)
            .isGreaterThan(ReceiptExtractor.extract(broken).confidence)
    }
}
