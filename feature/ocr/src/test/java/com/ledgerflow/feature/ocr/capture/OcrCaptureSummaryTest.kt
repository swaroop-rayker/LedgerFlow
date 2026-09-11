package com.ledgerflow.feature.ocr.capture

import com.google.common.truth.Truth.assertThat
import com.ledgerflow.feature.ocr.extraction.ReceiptExtractor
import com.ledgerflow.feature.ocr.extraction.ReceiptFixtures
import com.ledgerflow.feature.ocr.recognition.RecognizedPage
import org.junit.Test

/**
 * What the capture screen says about a page it has read (§5.3).
 *
 * **The mapping, not the ViewModel.** Everything before this point either
 * needs a device — ML Kit's pipeline is native, `Bitmap` is a framework type —
 * or is already covered by the extraction tests. What is left is the step that
 * would silently drop a field, so that is what has an answer worth checking.
 *
 * What no off-device test can check is whether a real thermal printer's
 * geometry agrees with `ReceiptGeometry`'s bands. That is the corpus's job,
 * and until the corpus exists it is the physical device's.
 */
class OcrCaptureSummaryTest {

    private fun summaryFor(page: RecognizedPage, currency: String = "INR") =
        summaryOf(page, ReceiptExtractor.extract(page, currency), "Camera", currency)

    @Test
    fun anExtractedBill_isReportedWithItsMerchantItemsAndTotal() {
        val summary = summaryFor(ReceiptFixtures.ordinaryBill())

        assertThat(summary.isBill).isTrue()
        assertThat(summary.merchant).isEqualTo("SRI LAKSHMI STORES")
        assertThat(summary.totalText).isEqualTo("₹694.46")
        assertThat(summary.items.map { it.name })
            .containsExactly("TOMATO 1KG", "TOOR DAL 1KG", "MILK 500ML", "ATTA 5KG")
            .inOrder()
        assertThat(summary.items.map { it.amountText })
            .containsExactly("₹40.00", "₹330.00", "₹54.00", "₹285.00")
            .inOrder()
        assertThat(summary.balance).isEqualTo("Balanced")
    }

    /** Tax and discount are parts of the bill, but they are not items. */
    @Test
    fun onlyItemLines_areListed() {
        val summary = summaryFor(ReceiptFixtures.ordinaryBill())

        assertThat(summary.items.none { it.name.contains("CGST") }).isTrue()
        assertThat(summary.items.none { it.name.contains("DISCOUNT") }).isTrue()
    }

    @Test
    fun anUnbalancedBill_statesTheDelta() {
        val broken = ReceiptFixtures.page(
            ReceiptFixtures.row(0, ReceiptFixtures.LEFT to "SRI LAKSHMI STORES"),
            ReceiptFixtures.row(
                1,
                ReceiptFixtures.LEFT to "TOMATO 1KG",
                ReceiptFixtures.amount("40.00"),
            ),
            ReceiptFixtures.row(
                2,
                ReceiptFixtures.LEFT to "GRAND TOTAL",
                ReceiptFixtures.amount("90.00"),
            ),
        )

        assertThat(summaryFor(broken).balance).isEqualTo("Off by -₹50.00")
    }

    /**
     * No total is **not** "off by the whole bill".
     *
     * Reporting a delta against zero would put the sum of the items on screen
     * as a discrepancy — a specific, believable, wrong number.
     */
    @Test
    fun aBillWithNoTotal_reportsNoBalanceRatherThanAFailedOne() {
        val noTotal = ReceiptFixtures.page(
            ReceiptFixtures.row(0, ReceiptFixtures.LEFT to "SRI LAKSHMI STORES"),
            ReceiptFixtures.row(
                1,
                ReceiptFixtures.LEFT to "TOMATO 1KG",
                ReceiptFixtures.amount("40.00"),
            ),
        )

        val summary = summaryFor(noTotal)

        assertThat(summary.balance).isNull()
        assertThat(summary.items).hasSize(1)
        assertThat(summary.isBill).isTrue()
    }

    /**
     * A page with no bill falls back to the raw runs.
     *
     * "90 runs and no items" and "the image was unreadable" are different
     * reports, and on a real device the raw text is the only thing that tells
     * them apart.
     */
    @Test
    fun aPageWithNoBill_fallsBackToTheRawRuns() {
        val notAReceipt = ReceiptFixtures.page(
            ReceiptFixtures.row(0, ReceiptFixtures.LEFT to "PLEASE KEEP OFF THE GRASS"),
        )

        val summary = summaryFor(notAReceipt)

        assertThat(summary.isBill).isFalse()
        assertThat(summary.items).isEmpty()
        assertThat(summary.rawPreview).contains("PLEASE")
    }

    @Test
    fun anEmptyPage_saysSoRatherThanShowingNothing() {
        val summary = summaryFor(RecognizedPage(emptyList()))

        assertThat(summary.elementCount).isEqualTo(0)
        assertThat(summary.rawPreview).isEqualTo("No text found.")
        assertThat(summary.isBill).isFalse()
    }

    /**
     * The currency reaches the *extractor*, not only the formatter.
     *
     * `CurrencyExponent` decides how many minor units a decimal point
     * separates, so a zero-decimal install reading `400` as 40,000 minor units
     * would be wrong by a factor of a hundred before anything was formatted.
     */
    @Test
    fun theBaseCurrency_changesTheReadingAndNotJustTheSymbol() {
        val bill = ReceiptFixtures.page(
            ReceiptFixtures.row(0, ReceiptFixtures.LEFT to "TOKYO SHOP"),
            ReceiptFixtures.row(
                1,
                ReceiptFixtures.LEFT to "COFFEE",
                ReceiptFixtures.amount("400"),
            ),
            ReceiptFixtures.row(
                2,
                ReceiptFixtures.LEFT to "GRAND TOTAL",
                ReceiptFixtures.amount("400"),
            ),
        )

        assertThat(summaryFor(bill, currency = "JPY").totalText).contains("400")
        assertThat(summaryFor(bill, currency = "INR").totalText).contains("400.00")
    }
}
