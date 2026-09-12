package com.ledgerflow.feature.ocr.capture

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import androidx.core.net.toUri
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.truth.Truth.assertThat
import com.ledgerflow.feature.ocr.recognition.MlKitReceiptTextRecognizer
import java.io.File
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Test

/**
 * Decoding what a user picks (SPEC.md §5.3).
 *
 * ## Instrumented, because every line of it is platform behaviour
 *
 * `ImageDecoder`, `PdfRenderer` and `ContentResolver` are all Android, and
 * Robolectric's shadows of the last two do not render pixels. A JVM test here
 * would assert that the code called the right methods, which is not the
 * question — the question is whether what comes back is something a recogniser
 * can read.
 *
 * ## The two properties worth testing are both silent failures
 *
 * **The downscale cap.** §5.3 fixes it and ADR-0023 makes the same frame the
 * stored attachment, so a loader that ignored it would put a 12 MP image
 * through recognition and then into `filesDir` — slower, larger, and no longer
 * "the image the pipeline saw".
 *
 * **The PDF's white background.** `PdfRenderer` draws onto a *transparent*
 * bitmap. A page rendered without filling it first is black glyphs on nothing,
 * which every recogniser reads as blank — so the failure is not an error, it is
 * a PDF that silently yields no text and looks like a model that could not cope.
 * That one is asserted through the recogniser rather than by inspecting pixels,
 * because "can it be read" is the property that matters.
 */
class ReceiptImageLoaderTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val loader = ReceiptImageLoader(context)
    private val scratch = mutableListOf<File>()

    @After
    fun tearDown() {
        scratch.forEach { it.delete() }
    }

    /** A file under the app's own `filesDir` — never `cacheDir` (Law 5). */
    private fun tempFile(name: String): File =
        File(context.filesDir, name).also { scratch += it }

    private fun writePng(name: String, width: Int, height: Int, text: String?): File {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        Canvas(bitmap).apply {
            drawColor(Color.WHITE)
            text?.let {
                drawText(
                    it,
                    width * TEXT_INSET,
                    height * TEXT_BASELINE,
                    Paint().apply {
                        color = Color.BLACK
                        textSize = height * TEXT_SIZE_RATIO
                        isAntiAlias = true
                    },
                )
            }
        }
        val file = tempFile(name)
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        return file
    }

    private fun writePdf(name: String, pages: Int, text: String): File {
        val document = PdfDocument()
        repeat(pages) { index ->
            val info = PdfDocument.PageInfo.Builder(PDF_WIDTH, PDF_HEIGHT, index + 1).create()
            val page = document.startPage(info)
            page.canvas.apply {
                drawColor(Color.WHITE)
                drawText(
                    text,
                    PDF_TEXT_X,
                    PDF_TEXT_Y,
                    Paint().apply {
                        color = Color.BLACK
                        textSize = PDF_TEXT_SIZE
                        isAntiAlias = true
                    },
                )
            }
            document.finishPage(page)
        }
        val file = tempFile(name)
        file.outputStream().use { document.writeTo(it) }
        document.close()
        return file
    }

    // ── Two caps: one for reading, a smaller one for keeping ────────────────

    /**
     * What is decoded is what the recogniser reads, so it takes the **larger**
     * cap.
     *
     * This asserted `MAX_LONG_EDGE` until recognition and storage were split.
     * Capping the decode at the storage size was costing glyph height on
     * exactly the text §12's recall gate measures — a phone photo at 3000x4000
     * squeezed to 1600 leaves a thermal line at roughly 12 px.
     */
    @Test
    fun anOversizedImage_isDecodedAtTheRecognitionCap() {
        val file = writePng("big.png", width = 4000, height = 3000, text = "TOTAL 473.00")

        val bitmap = loader.load(file.toUri())

        assertThat(maxOf(bitmap.width, bitmap.height))
            .isAtMost(ReceiptImageLoader.MAX_RECOGNITION_EDGE)
        // And genuinely larger than what will be stored, or the split is
        // machinery with no effect.
        assertThat(maxOf(bitmap.width, bitmap.height))
            .isGreaterThan(ReceiptImageLoader.MAX_LONG_EDGE)
    }

    /**
     * The stored copy is the smaller one (ADR-0023).
     *
     * The ~15x saving the ADR counted on is this cap; storing what was
     * recognised instead would quietly give it up.
     */
    @Test
    fun theStoredCopy_isCappedSmallerThanWhatWasRead() {
        val file = writePng("big.png", width = 4000, height = 3000, text = "TOTAL 473.00")

        val read = loader.load(file.toUri())
        val stored = loader.downscale(read)

        assertThat(maxOf(stored.width, stored.height))
            .isAtMost(ReceiptImageLoader.MAX_LONG_EDGE)
        assertThat(maxOf(stored.width, stored.height))
            .isLessThan(maxOf(read.width, read.height))
    }

    /** And the aspect ratio survives, or the geometry §5.3 reads is distorted. */
    @Test
    fun theDownscaleKeepsTheAspectRatio() {
        val file = writePng("wide.png", width = 4000, height = 2000, text = "TOTAL")

        val bitmap = loader.load(file.toUri())

        val ratio = bitmap.width.toFloat() / bitmap.height
        assertThat(ratio).isWithin(RATIO_TOLERANCE).of(2f)
    }

    /** A small image is left alone rather than upscaled into blur. */
    @Test
    fun anImageUnderTheCap_isNotEnlarged() {
        val file = writePng("small.png", width = 800, height = 600, text = "TOTAL")

        val bitmap = loader.load(file.toUri())

        assertThat(bitmap.width).isEqualTo(800)
        assertThat(bitmap.height).isEqualTo(600)
    }

    /**
     * The camera path takes the same recognition cap as an imported file.
     *
     * `ImageCapture` hands back a full sensor frame — far past anything ML Kit
     * gains from, and into out-of-memory territory once the recogniser takes
     * its own copy. If the two inputs reached the recogniser at different
     * sizes their results could not be measured against one corpus.
     */
    @Test
    fun forRecognition_appliesTheRecognitionCapToAnInMemoryFrame() {
        val frame = Bitmap.createBitmap(4000, 3000, Bitmap.Config.ARGB_8888)

        val result = loader.forRecognition(frame)

        assertThat(maxOf(result.width, result.height))
            .isAtMost(ReceiptImageLoader.MAX_RECOGNITION_EDGE)
        assertThat(maxOf(result.width, result.height))
            .isGreaterThan(ReceiptImageLoader.MAX_LONG_EDGE)
    }

    @Test
    fun downscale_appliesTheStorageCapToAnInMemoryFrame() {
        val frame = Bitmap.createBitmap(4000, 3000, Bitmap.Config.ARGB_8888)

        val result = loader.downscale(frame)

        assertThat(maxOf(result.width, result.height))
            .isAtMost(ReceiptImageLoader.MAX_LONG_EDGE)
    }

    // ── PDFs ────────────────────────────────────────────────────────────────

    @Test
    fun aPdf_rendersItsFirstPage() {
        val file = writePdf("bill.pdf", pages = 1, text = "GRAND TOTAL 473.00")

        val bitmap = loader.load(file.toUri())

        assertThat(bitmap.width).isGreaterThan(0)
        assertThat(bitmap.height).isGreaterThan(0)
    }

    /**
     * **And the page is readable, which is the assertion that matters.**
     *
     * Without the white fill this is black text on transparency: the bitmap has
     * the right dimensions, the render "succeeds", and the recogniser returns
     * nothing. Asserting through the recogniser is what makes the difference
     * visible — a dimension check would pass either way.
     */
    @Test
    fun aRenderedPdfPage_canActuallyBeRead() = runTest {
        val file = writePdf("readable.pdf", pages = 1, text = "GRAND TOTAL 473.00")

        val page = MlKitReceiptTextRecognizer().recognize(loader.load(file.toUri()))

        assertThat(page.isEmpty).isFalse()
        assertThat(page.elements.joinToString(" ") { it.text }).contains("473")
    }

    /**
     * A multi-page PDF reports its count, so the caller can say so.
     *
     * §5.3's loader reads page one only. That is a stated limit — how several
     * pages compose into one candidate is extraction's problem — and a second
     * page silently ignored would be worse than one refused out loud, which is
     * why the count is available rather than swallowed.
     */
    @Test
    fun aMultiPagePdf_reportsHowManyPagesItHas() {
        val file = writePdf("long.pdf", pages = 3, text = "PAGE")

        assertThat(loader.pageCount(file.toUri())).isEqualTo(3)
    }

    /** Anything that is not a PDF is one page, without opening it as one. */
    @Test
    fun anImage_countsAsASinglePage() {
        val file = writePng("single.png", width = 400, height = 400, text = "X")

        assertThat(loader.pageCount(file.toUri())).isEqualTo(1)
    }

    private companion object {
        const val TEXT_INSET = 0.05f
        const val TEXT_BASELINE = 0.6f
        const val TEXT_SIZE_RATIO = 0.18f
        const val RATIO_TOLERANCE = 0.02f

        const val PDF_WIDTH = 595
        const val PDF_HEIGHT = 842
        const val PDF_TEXT_X = 40f
        const val PDF_TEXT_Y = 120f
        const val PDF_TEXT_SIZE = 48f
    }
}
