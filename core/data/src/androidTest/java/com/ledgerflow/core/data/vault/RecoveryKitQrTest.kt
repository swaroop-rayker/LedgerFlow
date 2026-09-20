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

    private companion object {
        const val SCALE = 3
    }
}
