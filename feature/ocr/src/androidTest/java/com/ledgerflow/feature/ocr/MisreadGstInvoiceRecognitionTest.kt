package com.ledgerflow.feature.ocr

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.ledgerflow.core.domain.ingest.Reconciliation
import com.ledgerflow.core.model.LineItemKind
import com.ledgerflow.core.model.Money
import com.ledgerflow.feature.ocr.capture.ReceiptImageLoader
import com.ledgerflow.feature.ocr.extraction.ReceiptExtractor
import com.ledgerflow.feature.ocr.recognition.MlKitReceiptTextRecognizer
import com.ledgerflow.feature.ocr.recognition.RecognizedPage
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The `S GST 9%` / `S 6ST 9%` case through **real ML Kit**, on the device.
 *
 * The JVM tests for this feed hand-laid `RecognizedElement`s, so they pin the
 * rule and say nothing about the shape ML Kit actually returns — and the shape
 * is the whole question. Fuzzy keyword matching compares *word windows*, so it
 * depends on the recogniser emitting `S`, `6ST` and `9%` as separate runs. If
 * ML Kit glues them into `S6ST`, the window is four characters against the
 * five-character keyword `S GST`, the lengths differ, and the match is
 * correctly refused — the fix would not fire and no JVM test could tell.
 *
 * So this test **prints what the recogniser returned** as well as asserting on
 * it. The printed runs are the evidence; the assertions are the gate.
 *
 * **This page is a synthetic render and is not corpus material.** Crisp black
 * text on white says nothing about thermal paper, which is the substrate §12's
 * gate is actually about (`ReceiptFixtures`' KDoc, and section B of
 * `docs/OCR-PIPELINE.md`). What it does test is the classifier path end to end
 * against real recognition output, which is the part the JVM cannot reach.
 *
 * Note what is **not** claimed: that ML Kit misreads `GST` here. The misread is
 * *drawn into the page*, because the owner's real receipt is what established
 * that it happens and that image is not in the corpus yet (§8 item 3). Drawing
 * the misread reproduces the runs that receipt produced, which is the input the
 * extractor has to survive.
 */
@RunWith(AndroidJUnit4::class)
class MisreadGstInvoiceRecognitionTest {

    private val recognizer = MlKitReceiptTextRecognizer()

    /**
     * **The misread page now reads exactly like the clean one**, on real
     * recognition output.
     *
     * ML Kit returns this page's four tax labels in both shapes: `S | 6ST | 9%`
     * once, and glued as `C6ST` / `S6ST` three times. The separated form reaches
     * the spaced keyword `S GST`; the glued form reaches `SGST` only because
     * `ReceiptKeywords` folds confusable digits first. Before folding this page
     * gave seven items for four products — three tax rows read as purchases.
     *
     * The reconciliation is the sharp assertion: the four item amounts sum to
     * the printed total exactly, so any tax row left as an item puts the bill
     * out by its value.
     */
    @Test
    fun aMisreadGstPage_readsLikeTheCleanOne() = runBlocking {
        // ML Kit loads a ~10 MB native pipeline on first use. Warming it here
        // keeps the failure, if there is one, about the extraction.
        recognizer.recognize(invoice(GST_AS_READ, 640))

        val page = recognizer.recognize(invoice(GST_AS_READ, ReceiptImageLoader.MAX_RECOGNITION_EDGE))
        report("misread (S 6ST)", page)

        val bill = ReceiptExtractor.extract(page)
        bill.lines.forEach { println("   ${it.kind}  ${it.name}  ${it.total}") }

        assertThat(bill.lines.filter { it.kind == LineItemKind.ITEM }).hasSize(EXPECTED_ITEMS)
        assertThat(bill.lines.count { it.kind == LineItemKind.TAX }).isEqualTo(EXPECTED_TAX_ROWS)
        assertThat(bill.merchantRaw).isEqualTo("VALUE MART RETAIL LTD")
        assertThat(bill.amount).isEqualTo(Money(TOTAL_MINOR))

        val verdict = Reconciliation.of(bill.lines, bill.amount)
        assertThat(verdict).isInstanceOf(Reconciliation.Balanced::class.java)
        assertThat(verdict.delta).isEqualTo(Money.ZERO)
    }

    /**
     * **The premise the test above depends on, kept true.** If a future ML Kit
     * or preprocessing change stops gluing the label, this goes red — and the
     * glued-form half of the test above has stopped testing anything, which is
     * worth knowing rather than discovering.
     */
    @Test
    fun theRecogniser_gluesSomeTaxLabels() = runBlocking {
        recognizer.recognize(invoice(GST_AS_READ, 640))

        val page = recognizer.recognize(invoice(GST_AS_READ, ReceiptImageLoader.MAX_RECOGNITION_EDGE))
        val glued = page.elements.map { it.text }.filter {
            it.contains(MISREAD_LABEL) && it.length > MISREAD_LABEL.length
        }
        println("glued tax labels: $glued")

        assertThat(glued).isNotEmpty()
    }

    /**
     * The correctly-printed page, as the control.
     *
     * Its job is to show that the assertions above are about the *misread* and
     * not about the page being easy: if this one failed too, the test would be
     * measuring the render rather than the rule.
     */
    @Test
    fun aCorrectlyReadGstRow_isStillTaxAndNotAnItem() = runBlocking {
        recognizer.recognize(invoice(GST_AS_PRINTED, 640))

        val page = recognizer.recognize(
            invoice(GST_AS_PRINTED, ReceiptImageLoader.MAX_RECOGNITION_EDGE),
        )
        report("clean (S GST)", page)

        val bill = ReceiptExtractor.extract(page)

        assertThat(bill.lines.filter { it.kind == LineItemKind.ITEM }).hasSize(EXPECTED_ITEMS)
        assertThat(bill.amount).isEqualTo(Money(TOTAL_MINOR))
        assertThat(Reconciliation.of(bill.lines, bill.amount))
            .isInstanceOf(Reconciliation.Balanced::class.java)
    }

    /**
     * **Whether the fix can fire at all**, printed rather than inferred.
     *
     * Fuzzy matching needs the tax label to survive recognition at all.
     * Asserted separately from the extraction so that a failure says *which* of
     * the two broke: the recogniser's segmentation, or the classifier.
     */
    @Test
    fun theRecogniser_emitsTheMisreadLabelAtAll() = runBlocking {
        recognizer.recognize(invoice(GST_AS_READ, 640))

        val page = recognizer.recognize(invoice(GST_AS_READ, ReceiptImageLoader.MAX_RECOGNITION_EDGE))
        val texts = page.elements.map { it.text }
        println("runs containing $MISREAD_LABEL: " + texts.filter { it.contains(MISREAD_LABEL) })

        assertThat(texts.any { it.contains(MISREAD_LABEL) }).isTrue()
    }

    private fun report(label: String, page: RecognizedPage) {
        println("--- $label: ${page.elements.size} runs")
        println(page.elements.joinToString(" | ") { it.text })
    }

    /**
     * An Indian GST tax invoice, rendered.
     *
     * The same structure as `ReceiptFixtures.gstTaxInvoice` and the same
     * arithmetic, so a failure here and a failure there mean the same thing:
     * the four NET AMTs sum to the printed total on their own, and the tax rows
     * restate tax already inside them (tax-inclusive, which is what
     * `Reconciliation` has to discover).
     *
     * ```
     * 70.00 + 55.00 + 174.00 + 315.00 = 614.00 == TOTAL 614.00
     * ```
     */
    private fun invoice(taxLabel: String, longEdge: Int): Bitmap {
        val width = (longEdge * 0.42f).toInt()
        val bitmap = Bitmap.createBitmap(width, longEdge, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap).apply { drawColor(Color.WHITE) }

        val body = Paint().apply {
            color = Color.BLACK
            textSize = longEdge / 60f
            isAntiAlias = true
        }
        // The shop prints its name larger than its address: §5.3 selects the
        // merchant by type size, so a uniform page would leave that untested.
        val heading = Paint(body).apply { textSize = body.textSize * 1.4f }

        val rows = listOf(
            "42 STATION ROAD BENGALURU 560001" to null,
            "GST TIN 29AADCB1093N1ZE" to null,
            "CRISPS 95G" to "70.00",
            "$taxLabel 9%" to "5.34",
            "${taxLabel.replaceFirst("S", "C")} 9%" to "5.34",
            "CRISPS 177G" to "55.00",
            "$taxLabel 9%" to "4.19",
            "SHOWERGEL 250ML" to "174.00",
            "$taxLabel 9%" to "13.27",
            "CLEANER JASMINE 2L" to "315.00",
            "SUBTOTAL" to "614.00",
            "TOTAL" to "614.00",
        )

        // **Line pitch comes from the type size, and the amount column is
        // right-aligned by measurement.** The first version of this divided the
        // page height by the row count and drew amounts at a fixed offset from
        // the right edge -- which, on a twelve-row bill, put the amount column
        // at x=322 while `CLEANER JASMINE 2L` ran to x=530. The two columns
        // overlapped, `ReceiptGeometry.cells` found one cell per row instead of
        // two, and the extractor read **zero items from both pages**. The clean
        // control failing too is what said it was the render and not the rule.
        val pitch = body.textSize * PITCH_RATIO
        val margin = body.textSize
        val amountRight = width - margin

        canvas.drawText("VALUE MART RETAIL LTD", margin, pitch * 2, heading)
        rows.forEachIndexed { index, (label, amount) ->
            val y = pitch * (index + 4)
            canvas.drawText(label, margin, y, body)
            if (amount != null) {
                canvas.drawText(amount, amountRight - body.measureText(amount), y, body)
            }
        }
        return bitmap
    }

    private companion object {
        /** What the invoice prints. */
        const val GST_AS_PRINTED = "S GST"

        /** What ML Kit returned for it on the owner's real Food Bazaar bill. */
        const val GST_AS_READ = "S 6ST"

        /**
         * Line pitch as a multiple of the glyph height.
         *
         * Comfortably over the 1.2x that `ReceiptGeometry`'s 0.6 banding
         * tolerance is derived from, so two printed lines can never band into
         * one -- the same property `ReceiptFixtures` states for the JVM pages.
         */
        const val PITCH_RATIO = 1.8f

        const val EXPECTED_ITEMS = 4
        const val EXPECTED_TAX_ROWS = 4
        const val TOTAL_MINOR = 61_400L

        /** What ML Kit returned for `GST`. A longer run means it glued a neighbour on. */
        const val MISREAD_LABEL = "6ST"
    }
}
