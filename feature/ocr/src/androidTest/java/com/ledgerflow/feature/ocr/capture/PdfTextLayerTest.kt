package com.ledgerflow.feature.ocr.capture

import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import android.net.Uri
import android.os.Build
import android.os.ext.SdkExtensions
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.truth.Truth.assertThat
import com.ledgerflow.core.domain.ingest.Reconciliation
import com.ledgerflow.core.model.LineItemKind
import com.ledgerflow.core.model.Money
import com.ledgerflow.feature.ocr.extraction.ReceiptExtractor
import java.io.File
import org.junit.After
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * [PdfTextLayer] against a real `PdfRenderer`, on a PDF this test writes.
 *
 * The owner's invoices proved the path (both extracted exactly on the device)
 * but are private and cannot be a committed test. This writes a digital PDF
 * with the platform's own `PdfDocument` — a real text layer, the same API a
 * billing system's PDF library produces — and reads it back. What it pins is the
 * part [PdfTextRunsTest] cannot reach: that `getTextContents` and `searchText`
 * behave on this platform the way that JVM test's fake assumes.
 */
@RunWith(AndroidJUnit4::class)
class PdfTextLayerTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var pdf: File

    @Before
    fun writeInvoice() {
        assumeTrue(
            "PDF text extraction needs S extension 13",
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.R &&
                SdkExtensions.getExtensionVersion(Build.VERSION_CODES.S) >= 13,
        )
        pdf = File(context.filesDir, "text-layer-test.pdf")
        val document = PdfDocument()
        val page = document.startPage(PdfDocument.PageInfo.Builder(PAGE_WIDTH, PAGE_HEIGHT, 1).create())
        val body = Paint().apply { textSize = TEXT_SIZE }
        val heading = Paint().apply { textSize = TEXT_SIZE * 1.4f }

        page.canvas.drawText("VALUE MART RETAIL LTD", LEFT, 40f, heading)
        LINES.forEachIndexed { index, (label, amount) ->
            val y = FIRST_LINE + index * PITCH
            page.canvas.drawText(label, LEFT, y, body)
            if (amount != null) page.canvas.drawText(amount, AMOUNT_RIGHT - body.measureText(amount), y, body)
        }
        document.finishPage(page)
        pdf.outputStream().use(document::writeTo)
        document.close()
    }

    @After
    fun deleteInvoice() {
        if (::pdf.isInitialized) pdf.delete()
    }

    @Test
    fun aDigitalPdf_isReadFromItsTextLayer_andExtractsExactly() {
        val page = PdfTextLayer(context).read(Uri.fromFile(pdf))

        assertThat(page).isNotNull()
        val bill = ReceiptExtractor.extract(requireNotNull(page))
        println("text layer: ${page.elements.size} runs -> ${bill.lines.map { it.name to it.total }}")

        assertThat(bill.lines.filter { it.kind == LineItemKind.ITEM }.map { it.name })
            .containsExactly("CRISPS 95G", "CRISPS 177G", "SHOWERGEL 250ML", "CLEANER JASMINE 2L")
            .inOrder()
        assertThat(bill.amount).isEqualTo(Money(61_400L))
        assertThat(Reconciliation.of(bill.lines, bill.amount))
            .isInstanceOf(Reconciliation.Balanced::class.java)
        assertThat(bill.merchantRaw).isEqualTo("VALUE MART RETAIL LTD")
    }

    /** A file with no text layer is "recognise the image", never an empty bill. */
    @Test
    fun aPdfWithNoText_isNull() {
        val blank = File(context.filesDir, "blank-layer-test.pdf")
        try {
            val document = PdfDocument()
            document.finishPage(document.startPage(PdfDocument.PageInfo.Builder(PAGE_WIDTH, PAGE_HEIGHT, 1).create()))
            blank.outputStream().use(document::writeTo)
            document.close()

            assertThat(PdfTextLayer(context).read(Uri.fromFile(blank))).isNull()
        } finally {
            blank.delete()
        }
    }

    private companion object {
        const val PAGE_WIDTH = 300
        const val PAGE_HEIGHT = 500
        const val TEXT_SIZE = 10f
        const val LEFT = 16f
        const val AMOUNT_RIGHT = 284f
        const val FIRST_LINE = 70f
        const val PITCH = 18f

        /** `ReceiptFixtures.gstTaxInvoice`'s structure: tax-inclusive, 614.00. */
        val LINES = listOf(
            "42 STATION ROAD BENGALURU 560001" to null,
            "GST TIN 29AADCB1093N1ZE" to null,
            "CRISPS 95G" to "70.00",
            "S GST 9%" to "5.34",
            "C GST 9%" to "5.34",
            "CRISPS 177G" to "55.00",
            "S GST 9%" to "4.19",
            "SHOWERGEL 250ML" to "174.00",
            "S GST 9%" to "13.27",
            "CLEANER JASMINE 2L" to "315.00",
            "SUBTOTAL" to "614.00",
            "TOTAL" to "614.00",
        )
    }
}
