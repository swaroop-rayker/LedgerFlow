package com.ledgerflow.feature.ocr.extraction

import com.google.common.truth.Truth.assertThat
import com.ledgerflow.core.domain.ingest.Reconciliation
import com.ledgerflow.core.model.LineItemKind
import com.ledgerflow.core.model.Money
import org.junit.Test

/**
 * A4 GST invoices read as tables ([ReceiptTable]).
 *
 * **Every assertion here is a failure on a real invoice.** The owner's Zepto
 * and bigbasket PDFs, run through the real pipeline on the device, gave 0 of 4
 * and roughly 0 of 11 items, a ₹5,80,020 PIN code as a purchase, and no total.
 * The two fixtures copy those invoices' *structure* with invented products —
 * the real item lists are the owner's shopping and live only in the private
 * corpus store.
 */
class InvoiceTableTest {

    private val wrapped = ReceiptExtractor.extract(ReceiptFixtures.wrappedCellInvoice())
    private val straddled = ReceiptExtractor.extract(ReceiptFixtures.straddledNameInvoice())

    private fun items(bill: com.ledgerflow.core.domain.ingest.ExtractedTransaction) =
        bill.lines.filter { it.kind == LineItemKind.ITEM }

    // ── Wrapped description cells (Zepto's layout) ──────────────────────────

    /**
     * **Every wrapped name reassembled, and on the right product.** Adjacent
     * cells wrap to five, three and four lines; nearest-anchor assignment would
     * hand the first cell's last line to the second product.
     */
    @Test
    fun wrappedCells_eachNameIsReassembledOntoItsOwnProduct() {
        assertThat(items(wrapped).map { it.name }).containsExactly(
            "Crunchy Masala Peanuts Snack 1 pack (150 g)",
            "Oat Cookies (75 g)",
            "Instant Noodle Cup 1 pack (70 g)",
        ).inOrder()
        // Item 2's total printed `44,00`: refused as money, kept as a line.
        assertThat(items(wrapped).map { it.total })
            .containsExactly(Money(6_000L), null, Money(3_500L)).inOrder()
    }

    /**
     * **Any one of serial number, quantity or HSN code makes a line an item.**
     * ML Kit dropped a quantity `1` on the real Zepto page and bigbasket printed
     * a line with no serial number; a one-signal column-total rule ended the
     * table at each. The fixtures carry a line with each signal alone.
     */
    @Test
    fun wrappedCells_aMissingQuantity_doesNotEndTheTable() {
        assertThat(items(wrapped)).hasSize(3)
        assertThat(items(wrapped)[0].quantityMilli).isEqualTo(2_000L)
        assertThat(items(wrapped)[2].quantityMilli).isNull()
    }

    /**
     * **An unreadable total keeps its line.** Dropping it — as the first
     * version did on the real Zepto page — lost the product and shifted every
     * later wrapped name onto the wrong one. Kept, the bill is honestly
     * unbalanced by exactly the missing figure.
     */
    @Test
    fun wrappedCells_anUnreadableTotal_keepsItsLineAndTheDeltaIsExactlyThatFigure() {
        assertThat(wrapped.amount).isEqualTo(Money(13_900L))
        val verdict = Reconciliation.of(wrapped.lines, wrapped.amount)
        assertThat(verdict).isInstanceOf(Reconciliation.Unbalanced::class.java)
        assertThat(verdict.delta).isEqualTo(Money(-4_400L))
    }

    @Test
    fun wrappedCells_thePinCodeIsNotAPurchase_andTheLabelIsNotTheMerchant() {
        assertThat(wrapped.lines.none { (it.total?.minor ?: 0L) >= 58_002_000L }).isTrue()
        assertThat(wrapped.merchantRaw).isEqualTo("Quickmart Retail Private Limited")
    }

    // ── Names straddling the figures (bigbasket's layout) ───────────────────

    /**
     * Half the name above the figures, half below, joined onto one product —
     * and an HSN code glued to the end of a name dropped from it.
     */
    @Test
    fun straddledNames_areJoinedAcrossTheFigures() {
        assertThat(items(straddled).map { it.name }).containsExactly(
            "Cold Pressed Sesame Oil 1 Litre Bottle",
            "Whole Wheat Bread 400 g",
            "Green Chilli 100 g",
        ).inOrder()
        assertThat(items(straddled).map { it.quantityMilli }).containsExactly(2_000L, null, 1_070L).inOrder()
    }

    /**
     * **The side-by-side summary tables.** `Total Invoice value (In words)`
     * shares a line with the left table's `Rs.9.99`, printed after the
     * in-figures line — so reading the rightmost figure anywhere on a totals
     * line would make the ₹539.95 bill ₹9.99.
     */
    @Test
    fun straddledNames_theTotalIsTheFigureBesideItsOwnLabel() {
        assertThat(straddled.amount).isEqualTo(Money(53_995L))
        assertThat(Reconciliation.of(straddled.lines, straddled.amount))
            .isInstanceOf(Reconciliation.Balanced::class.java)
    }

    /** Only items come out of a table: tax and discount are columns there. */
    @Test
    fun tables_produceOnlyItemLines() {
        assertThat(wrapped.lines.all { it.kind == LineItemKind.ITEM }).isTrue()
        assertThat(straddled.lines.all { it.kind == LineItemKind.ITEM }).isTrue()
    }

    // ── What must not take the table path ───────────────────────────────────

    /**
     * A thermal slip and the one-line GST invoice keep reading line by line.
     * The GST fixture has a header (`ITEM DESC  QTY  NET AMT`) — three columns,
     * under the five a table needs.
     */
    @Test
    fun aPageWithoutAWideHeader_isNotATable() {
        val rows = { page: com.ledgerflow.feature.ocr.recognition.RecognizedPage -> ReceiptGeometry.rows(page) }
        listOf(ReceiptFixtures.ordinaryBill(), ReceiptFixtures.gstTaxInvoice()).forEach { page ->
            val scale = ReceiptGeometry.medianHeight(ReceiptGeometry.contentElements(page))
            assertThat(ReceiptTable.read(rows(page), scale, "INR")).isNull()
        }
    }
}
