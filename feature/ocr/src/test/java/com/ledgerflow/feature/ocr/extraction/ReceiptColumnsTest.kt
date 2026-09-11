package com.ledgerflow.feature.ocr.extraction

import com.google.common.truth.Truth.assertThat
import com.ledgerflow.core.model.Money
import com.ledgerflow.core.model.Quantity
import com.ledgerflow.feature.ocr.extraction.ReceiptFixtures.AMOUNT_X
import com.ledgerflow.feature.ocr.extraction.ReceiptFixtures.GLYPH
import com.ledgerflow.feature.ocr.extraction.ReceiptFixtures.LEFT
import com.ledgerflow.feature.ocr.extraction.ReceiptFixtures.QUANTITY_X
import com.ledgerflow.feature.ocr.extraction.ReceiptFixtures.RATE_X
import com.ledgerflow.feature.ocr.extraction.ReceiptFixtures.amount
import com.ledgerflow.feature.ocr.extraction.ReceiptFixtures.page
import com.ledgerflow.feature.ocr.extraction.ReceiptFixtures.row
import org.junit.Test

/** Step 7: rightmost amount, leftmost name, and the figures in between. */
class ReceiptColumnsTest {

    private fun read(vararg columns: Pair<Float, String>): ReceiptColumnReading? {
        val receiptRow = ReceiptGeometry.rows(page(row(0, *columns))).single()
        return ReceiptColumns.read(ReceiptGeometry.cells(receiptRow, GLYPH), "INR")
    }

    @Test
    fun twoColumns_areANameAndAnAmount() {
        val reading = read(LEFT to "TOMATO 1KG", amount("40.00"))

        assertThat(reading?.name).isEqualTo("TOMATO 1KG")
        assertThat(reading?.amount).isEqualTo(Money(4_000L))
        assertThat(reading?.quantity).isNull()
    }

    /**
     * `NAME  QTY  RATE  AMOUNT`, the common Indian retail layout.
     *
     * Reading only the rightmost number would still get the amount right and
     * throw away a quantity the bill printed in plain sight.
     */
    @Test
    fun fourColumns_whereTheArithmeticCloses_yieldQuantityAndUnitPrice() {
        val reading = read(
            LEFT to "TOOR DAL 1KG",
            QUANTITY_X to "2",
            RATE_X to "165.00",
            amount("330.00"),
        )

        assertThat(reading?.name).isEqualTo("TOOR DAL 1KG")
        assertThat(reading?.quantity).isEqualTo(Quantity(2_000L))
        assertThat(reading?.unitPrice).isEqualTo(Money(16_500L))
        assertThat(reading?.amount).isEqualTo(Money(33_000L))
        assertThat(reading?.closes).isTrue()
    }

    /**
     * When the arithmetic does not close, the money survives and the
     * description does not.
     *
     * A row laid out `NAME  MRP  RATE  AMOUNT` would otherwise become a line
     * with a confidently wrong quantity — worse than no quantity, because
     * nothing downstream would question it.
     */
    @Test
    fun figuresThatDoNotMultiplyOut_areDiscarded_andTheAmountIsKept() {
        val reading = read(
            LEFT to "OIL 1L",
            QUANTITY_X to "2",
            RATE_X to "165.00",
            amount("340.00"),
        )

        assertThat(reading?.amount).isEqualTo(Money(34_000L))
        assertThat(reading?.quantity).isNull()
        assertThat(reading?.unitPrice).isNull()
        assertThat(reading?.closes).isFalse()
    }

    /** A rate with no quantity column: the 2 is recoverable by division. */
    @Test
    fun aBareUnitPriceThatDividesTheAmount_recoversTheQuantity() {
        val reading = read(LEFT to "MILK 500ML", RATE_X to "27.00", amount("54.00"))

        assertThat(reading?.quantity).isEqualTo(Quantity(2_000L))
        assertThat(reading?.unitPrice).isEqualTo(Money(2_700L))
        assertThat(reading?.amount).isEqualTo(Money(5_400L))
    }

    /**
     * A deduced quantity is not a confirmed reading.
     *
     * The division case invents a number the bill never printed, so it must
     * not raise the row's confidence the way a printed `qty × rate` does.
     */
    @Test
    fun aDeducedQuantity_doesNotCountAsTheRowClosing() {
        val reading = read(LEFT to "MILK 500ML", RATE_X to "27.00", amount("54.00"))

        assertThat(reading?.quantity).isNotNull()
        assertThat(reading?.closes).isFalse()
    }

    /**
     * The division is bounded, because an unbounded one "recovers" nonsense.
     *
     * `1.00` divides `473.00` exactly, and a quantity of 473 is not a reading
     * of anything — those two cells were never a rate and an amount.
     */
    @Test
    fun anImplausibleDeducedQuantity_isDeclined() {
        val reading = read(LEFT to "ROUND OFF", RATE_X to "1.00", amount("473.00"))

        assertThat(reading?.amount).isEqualTo(Money(47_300L))
        assertThat(reading?.quantity).isNull()
    }

    /** A size inside a name is not a figure: it is not a bare number. */
    @Test
    fun aSizeInTheName_staysInTheName() {
        val reading = read(LEFT to "ATTA 5KG", amount("285.00"))

        assertThat(reading?.name).isEqualTo("ATTA 5KG")
        assertThat(reading?.amount).isEqualTo(Money(28_500L))
    }

    @Test
    fun aRowWithNoAmount_readsAsNull() {
        assertThat(read(LEFT to "THANK YOU VISIT AGAIN")).isNull()
        assertThat(read(LEFT to "GSTIN 29AAACT2727Q1ZW")).isNull()
    }

    /** The amount column's left edge is carried for the classifier's benefit. */
    @Test
    fun theAmountColumnPosition_isReported() {
        val reading = read(LEFT to "TOMATO 1KG", amount("40.00"))

        assertThat(reading?.amountCellLeft).isWithin(TOLERANCE).of(AMOUNT_X - "40.00".length * ReceiptFixtures.ADVANCE)
    }

    private companion object {
        const val TOLERANCE = 0.01f
    }
}
