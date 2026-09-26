package com.ledgerflow.core.data.vault

import android.graphics.Bitmap
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.truth.Truth.assertThat
import com.google.zxing.BinaryBitmap
import com.google.zxing.MultiFormatReader
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.ledgerflow.core.domain.vault.PhraseQr
import com.ledgerflow.core.domain.vault.RecoveryKitFormat
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The Recovery Kit's QR code (ADR-0028), read back out of the PDF it was drawn
 * into — rendered and decoded exactly as a camera would see it off paper.
 *
 * **This is the test that keeps the kit and the scanner honest with each
 * other:** the same library draws and reads, so a change to either side that
 * breaks the round trip fails here rather than on a phone someone is trying to
 * restore.
 *
 * The phrase is the **public BIP-39 test vector**, never a real one.
 */
@RunWith(AndroidJUnit4::class)
class RecoveryKitQrTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val words = List(23) { "abandon" } + "art"
    private lateinit var file: File

    @Before
    fun setUp() {
        file = File(context.cacheDir, "recovery-kit-qr-test.pdf").apply { delete() }
    }

    @After
    fun tearDown() {
        file.delete()
    }

    private val writer = RecoveryKitWriter(context, Dispatchers.IO)

    private fun renderFirstPage(): Bitmap =
        ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { descriptor ->
            PdfRenderer(descriptor).use { renderer ->
                renderer.openPage(0).use { page ->
                    // Three times the page's own size: a camera reads a printed
                    // code at far more than one pixel per module, and at 1x the
                    // modules land on half-pixels.
                    Bitmap.createBitmap(page.width * SCALE, page.height * SCALE, Bitmap.Config.ARGB_8888)
                        .also { bitmap ->
                            bitmap.eraseColor(android.graphics.Color.WHITE)
                            page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                        }
                }
            }
        }

    private fun decode(bitmap: Bitmap): String? {
        val pixels = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        val source = RGBLuminanceSource(bitmap.width, bitmap.height, pixels)
        return runCatching {
            MultiFormatReader().decode(BinaryBitmap(HybridBinarizer(source))).text
        }.getOrNull()
    }

    @Test
    fun theKitsQr_decodesToTheWordsOnThePage() = runTest {
        val written = writer.write(Uri.fromFile(file).toString(), RecoveryKitFormat.Pdf, words)
        assertThat(written).isTrue()

        val text = decode(renderFirstPage())

        assertThat(text).isNotNull()
        assertThat(PhraseQr.decode(text!!)).isEqualTo(PhraseQr.Scan.Phrase(words))
    }

    /** The text kit carries no code, and must not pretend to: nothing to decode. */
    @Test
    fun theTextKit_isStillPlainText() = runTest {
        val text = File(context.cacheDir, "recovery-kit-qr-test.txt").apply { delete() }

        assertThat(writer.write(Uri.fromFile(text).toString(), RecoveryKitFormat.Text, words)).isTrue()

        assertThat(text.readText()).contains("art")
        assertThat(text.readText()).doesNotContain(PhraseQr.PREFIX)
        text.delete()
    }

    /**
     * BUG36 on the rendered page: across the rows the code occupies, nothing
     * dark lies outside the code's own columns. The first layout drew the
     * restore steps along those rows, under the code; the owner saw it on a
     * printed kit. The code is found the way a camera finds it, by its finder
     * patterns, so this does not trust the layout it is checking.
     */
    @Test
    fun Bug36_nothingIsDrawnBesideOrUnderTheCode() = runTest {
        assertThat(writer.write(Uri.fromFile(file).toString(), RecoveryKitFormat.Pdf, words)).isTrue()
        val page = renderFirstPage()
        val pixels = IntArray(page.width * page.height).also { page.getPixels(it, 0, page.width, 0, 0, page.width, page.height) }
        val points = MultiFormatReader()
            .decode(BinaryBitmap(HybridBinarizer(RGBLuminanceSource(page.width, page.height, pixels))))
            .resultPoints
        // Finder centres sit 3.5 modules in; pad by a generous margin beyond that.
        val span = points.maxOf { it.x } - points.minOf { it.x }
        val pad = span * PAD_FRACTION
        val left = (points.minOf { it.x } - pad).toInt().coerceAtLeast(0)
        val right = (points.maxOf { it.x } + pad).toInt().coerceAtMost(page.width - 1)
        val top = (points.minOf { it.y } - pad).toInt().coerceAtLeast(0)
        val bottom = (points.maxOf { it.y } + pad).toInt().coerceAtMost(page.height - 1)

        val stray = (top..bottom).sumOf { y ->
            (0 until page.width).count { x -> (x < left || x > right) && pixels[y * page.width + x].isDark() }
        }
        assertThat(stray).isEqualTo(0)
    }

    /**
     * BUG36 with the real font: every restore-step line and every warning line
     * fits the page's text width, and the code sits below the last step and
     * above the bottom margin. `RecoveryKitLayoutTest` checks the same rules
     * with a stand-in font; this is the one with Android's own glyph widths.
     */
    @Test
    fun Bug36_withTheRealFont_everyLineFits_andTheCodeIsBelowTheSteps() {
        val body = android.graphics.Paint().apply { textSize = RecoveryKitWriter.BODY_SIZE }
        val bold = android.graphics.Paint().apply {
            textSize = RecoveryKitWriter.BODY_SIZE
            typeface = android.graphics.Typeface.create(android.graphics.Typeface.DEFAULT, android.graphics.Typeface.BOLD)
        }
        val section = RecoveryKitLayout.restoreSection(460f, RecoveryKitWriter.RESTORE_STEPS, body::measureText)
        val textRight = RecoveryKitLayout.MARGIN + RecoveryKitLayout.TEXT_WIDTH

        section.steps.forEach { assertThat(it.x + body.measureText(it.text)).isAtMost(textRight) }
        RecoveryKitWriter.WARNING_LINES.forEach {
            assertThat(RecoveryKitLayout.MARGIN + bold.measureText(it)).isAtMost(textRight)
        }
        assertThat(section.qrTop).isGreaterThan(section.steps.maxOf { it.baseline } + RecoveryKitLayout.LINE_HEIGHT)
        assertThat(section.bottom).isAtMost(RecoveryKitLayout.PAGE_HEIGHT - RecoveryKitLayout.MARGIN)
    }

    private fun Int.isDark(): Boolean {
        val luminance = (android.graphics.Color.red(this) + android.graphics.Color.green(this) +
            android.graphics.Color.blue(this)) / 3
        return luminance < DARK
    }

    private companion object {
        const val SCALE = 3

        /** Beyond the finder centres: 3.5 modules plus quiet zone, as a share of the code's span. */
        const val PAD_FRACTION = 0.2f
        const val DARK = 128
    }
}
