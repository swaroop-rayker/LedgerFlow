package com.ledgerflow.feature.ocr.capture

import android.content.Context
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.Build
import android.os.ext.SdkExtensions
import com.ledgerflow.feature.ocr.recognition.RecognizedPage
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The first page of a digital PDF, read from its text layer rather than its
 * pixels. See [PdfTextRuns] for why and how.
 *
 * **Null means "recognise the image instead", never "this file is empty".** It
 * is null on a platform without the API, on a scanned PDF (pictures of paper
 * carry no text layer), and on any failure — and every one of those still has
 * the rasterise-and-recognise path behind it, unchanged.
 *
 * **Availability is an SDK extension, not an API level.** Text extraction
 * arrived for `PdfRenderer` through the S extension (version 13) rather than
 * with a platform release, so a device's extension version decides — measured
 * at 22 on the owner's Android 16 phone. `minSdk` 26 predates `SdkExtensions`
 * itself (API 30), hence both checks.
 */
@Singleton
public class PdfTextLayer @Inject constructor(
    @param:ApplicationContext private val context: Context,
) {

    public fun read(uri: Uri): RecognizedPage? {
        if (!isAvailable()) return null
        return runCatching { readAvailable(uri) }.getOrNull()?.takeUnless { it.isEmpty }
    }

    private fun readAvailable(uri: Uri): RecognizedPage? =
        context.contentResolver.openFileDescriptor(uri, "r")?.use { fd ->
            PdfRenderer(fd).use { renderer ->
                if (renderer.pageCount == 0) null else renderer.openPage(0).use(::readPage)
            }
        }

    @Suppress("NewApi") // Reached only through isAvailable(), which lint cannot follow across calls.
    private fun readPage(page: PdfRenderer.Page): RecognizedPage? {
        val text = page.textContents.joinToString("\n") { it.text }
        if (text.isBlank()) return null
        return PdfTextRuns.build(text, ReceiptImageLoader.pdfScale(page.width, page.height)) { token ->
            page.searchText(token).map { match ->
                match.bounds.map { PdfTextRuns.Box(it.left, it.top, it.right, it.bottom) }
            }
        }
    }

    private fun isAvailable(): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.R &&
            SdkExtensions.getExtensionVersion(Build.VERSION_CODES.S) >= REQUIRED_S_EXTENSION

    private companion object {
        const val REQUIRED_S_EXTENSION = 13
    }
}
