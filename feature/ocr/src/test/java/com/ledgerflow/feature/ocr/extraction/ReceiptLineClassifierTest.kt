package com.ledgerflow.feature.ocr.extraction

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import org.junit.Test

/**
 * Step 8. **Only `ITEM` counts toward §12's ≥90% recall**, so every
 * misclassification here moves the gate in one direction or the other.
 *
 * The cases are chosen from the overlaps rather than from the easy rows: every
 * keyword in these sets is a substring of some other line's words on some real
 * bill, and a flat "contains TOTAL" test is wrong three separate ways.
 */
class ReceiptLineClassifierTest {

    private fun rows(vararg pairs: Pair<String, Boolean>) =
        pairs.map { (text, priced) ->
            ClassifiableRow(
                upper = text.uppercase(),
                name = text.substringBefore("  ").trim(),
                hasAmount = priced,
            )
        }

    private fun classify(vararg pairs: Pair<String, Boolean>) =
        ReceiptLineClassifier.classify(rows(*pairs))

    @Test
    fun anOrdinaryBill_placesEveryRow() {
        val kinds = classify(
            "SRI LAKSHMI STORES" to false,
            "GSTIN 29AAACT2727Q1ZW" to false,
            "TOMATO 1KG" to true,
            "ATTA 5KG" to true,
            "SUB TOTAL" to true,
            "CGST 2.5%" to true,
            "DISCOUNT" to true,
            "GRAND TOTAL" to true,
            "CASH" to true,
            "THANK YOU" to false,
        )

        assertThat(kinds).containsExactly(
            ReceiptLineKind.HEADER,
            ReceiptLineKind.HEADER,
            ReceiptLineKind.ITEM,
            ReceiptLineKind.ITEM,
            ReceiptLineKind.SUBTOTAL,
            ReceiptLineKind.TAX,
            ReceiptLineKind.DISCOUNT,
            ReceiptLineKind.TOTAL,
            ReceiptLineKind.FOOTER,
            ReceiptLineKind.FOOTER,
        ).inOrder()
    }

    /** `SUB TOTAL` contains `TOTAL`. It is not the bill's total. */
    @Test
    fun subTotal_isNotTheTotal() {
        val kinds = classify("TOMATO" to true, "SUB TOTAL" to true, "TOTAL" to true)

        assertThat(kinds[1]).isEqualTo(ReceiptLineKind.SUBTOTAL)
        assertThat(kinds[2]).isEqualTo(ReceiptLineKind.TOTAL)
    }

    /**
     * `TOTAL SAVINGS` contains `TOTAL` and is not one.
     *
     * **This used to assert DISCOUNT and that was wrong**, found on a real
     * Food Bazaar bill. A savings line sums discounts the per-item prices
     * already reflect, so treating it as a line of the bill subtracts them a
     * second time — the bill came out short by exactly the printed saving.
     * It is informational, and the assertion is now the property that
     * actually matters: it is neither the total nor a part.
     */
    @Test
    fun totalSavings_isNeitherTheTotalNorAPart() {
        val kinds = classify("TOMATO" to true, "TOTAL SAVINGS" to true)

        assertThat(kinds[1]).isNotEqualTo(ReceiptLineKind.TOTAL)
        assertThat(kinds[1]).isNotEqualTo(ReceiptLineKind.DISCOUNT)
    }

    /**
     * `TOTAL QTY: 14` contains `TOTAL` and carries a number that parses.
     *
     * Without the administrative check running *before* the keyword sets, this
     * row becomes the bill's total and the receipt is filed as ₹14.
     */
    @Test
    fun totalQty_isNotTheTotal() {
        val kinds = classify("TOMATO" to true, "TOTAL QTY: 14" to true, "GRAND TOTAL" to true)

        assertThat(kinds[1]).isNotEqualTo(ReceiptLineKind.TOTAL)
        assertThat(kinds[2]).isEqualTo(ReceiptLineKind.TOTAL)
    }

    /**
     * A summary word with no amount is a label, not a summary line.
     *
     * A bill that prints `TOTAL` on one line and the figure on the next would
     * otherwise have its totals block start a row early, and every row below
     * the label — including the figure — would read as footer.
     */
    @Test
    fun aSummaryWordWithNoAmount_isNotASummaryLine() {
        val kinds = classify("TOMATO" to true, "TOTAL" to false)

        assertThat(kinds[1]).isNotEqualTo(ReceiptLineKind.TOTAL)
    }

    /**
     * Tender rows are the expensive mistake.
     *
     * `CASH 700.00` and `CHANGE 5.54` sit below the total and look exactly like
     * items. Counting them adds roughly twice the bill to the item sum, and
     * every receipt then reads as badly unbalanced.
     */
    @Test
    fun tenderRows_areNeverItems() {
        val kinds = classify(
            "TOMATO" to true,
            "GRAND TOTAL" to true,
            "CASH" to true,
            "CHANGE" to true,
            "UPI" to true,
        )

        assertThat(kinds.drop(2)).containsExactly(
            ReceiptLineKind.FOOTER,
            ReceiptLineKind.FOOTER,
            ReceiptLineKind.FOOTER,
        )
    }

    /**
     * The case where the tender keywords are the *only* thing standing between
     * `CASH 500.00` and the item list.
     *
     * Below a total, position already disqualifies a tender row. On a slip
     * that prints no total at all -- a handwritten kirana bill, a cash memo --
     * there is no totals block to be below, so the keyword is carrying the
     * whole decision. A mutation sweep found this: dropping the TENDER set
     * turned nothing red, because every tender row in every other case here
     * sits under a `GRAND TOTAL`.
     */
    @Test
    fun tenderRowsWithNoTotalAbove_areStillNotItems() {
        val kinds = classify("TOMATO" to true, "ATTA" to true, "CASH" to true)

        assertThat(kinds).containsExactly(
            ReceiptLineKind.ITEM,
            ReceiptLineKind.ITEM,
            ReceiptLineKind.FOOTER,
        ).inOrder()
    }

    /**
     * A short identifier parses as money, and an identifier is never shopping.
     *
     * `BILL NO 4521` would be a ₹45.21 purchase, and worse, it would be the
     * *first* priced row — which is what the header boundary is measured from,
     * so the shop's name would fall outside the header block as well.
     */
    @Test
    fun anIdentifier_neitherBecomesAnItem_norEndsTheHeader() {
        val kinds = classify(
            "SRI LAKSHMI STORES" to false,
            "BILL NO 4521" to true,
            "TOMATO 1KG" to true,
        )

        assertThat(kinds[0]).isEqualTo(ReceiptLineKind.HEADER)
        assertThat(kinds[1]).isEqualTo(ReceiptLineKind.HEADER)
        assertThat(kinds[2]).isEqualTo(ReceiptLineKind.ITEM)
    }

    /** Every row above the first price is header, however many there are. */
    @Test
    fun theHeaderIsAsLongAsItNeedsToBe() {
        val kinds = classify(
            "SHOP" to false,
            "ADDRESS LINE" to false,
            "GSTIN 29AA" to false,
            "TAX INVOICE" to false,
            "PHONE 9876543210" to false,
            "TOMATO" to true,
        )

        assertThat(kinds.take(5).toSet()).containsExactly(ReceiptLineKind.HEADER)
        assertThat(kinds[5]).isEqualTo(ReceiptLineKind.ITEM)
    }

    /** A page with nothing priced at all is all header, not all item. */
    @Test
    fun aPageWithNoPrices_hasNoItems() {
        val kinds = classify("SOME TEXT" to false, "MORE TEXT" to false)

        assertThat(kinds.toSet()).containsExactly(ReceiptLineKind.HEADER)
    }

    /** A Hindi bill prints its total in Devanagari, which ADR-0021 can read. */
    @Test
    fun devanagariTotalKeywords_areRecognised() {
        listOf("बिल राशि", "कुल").forEach { keyword ->
            val kinds = classify("TOMATO" to true, keyword to true)
            assertWithMessage("'%s'", keyword)
                .that(kinds[1])
                .isEqualTo(ReceiptLineKind.TOTAL)
        }
    }
}
