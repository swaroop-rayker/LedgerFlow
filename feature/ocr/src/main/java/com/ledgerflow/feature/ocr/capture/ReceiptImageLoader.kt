package com.ledgerflow.feature.ocr.capture

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ImageDecoder
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.roundToInt

/**
 * Turns a picked image or PDF into a bitmap the recogniser can read
 * (SPEC.md §5.3).
 *
 * ## Everything here is `suspend`-callable and none of it touches a file the
 * app keeps
 *
 * The decoded bitmap lives in memory and is handed straight to the recogniser.
 * Nothing is written to `cacheDir` — Law 5 allows it for "decoded-image scratch
 * only", and this path needs no scratch at all, so the exception is not used.
 * The image only reaches disk when the user approves the candidate and
 * `attachment` stores it, sealed, under `filesDir` (ADR-0023).
 *
 * ## The downscale is part of the specification, not an optimisation
 *
 * §5.3 caps the long edge at [MAX_LONG_EDGE] before recognition, and ADR-0023
 * makes the *same* frame the one stored as the attachment — "the image the
 * pipeline saw", so a later question about a misread line has an answer. A
 * 12 MP camera frame is ~4 MB; this is ~250 KB. Doing it here rather than at
 * storage time is what keeps those two the same picture.
 *
 * ## PDFs
 *
 * §5.3 says rasterise page by page via `PdfRenderer` at a 300 DPI equivalent.
 * Only the **first page** is read here, and that is a stated limit rather than
 * an oversight: a multi-page bill is one candidate, and deciding how several
 * pages compose into one set of line items is extraction's problem, not the
 * loader's. A second page silently ignored would be worse than one refused, so
 * the caller is told how many there were.
 */
@Singleton
public class ReceiptImageLoader @Inject constructor(
    @param:ApplicationContext private val context: Context,
) {

    /**
     * Decodes [uri], downscaled, or throws.
     *
     * Call from a background dispatcher: `StrictMode` runs with `penaltyDeath`
     * in debug, so a decode on the main thread does not degrade — it kills the
     * process, which is the system working.
     */
    public fun load(uri: Uri): Bitmap =
        if (isPdf(uri)) renderFirstPdfPage(uri) else decodeImage(uri)

    /**
     * MIME first, then the file's own first bytes.
     *
     * **The sniff is not belt-and-braces, it is the reliable half.**
     * `ContentResolver.getType` answers for a `content://` URI — which is what
     * SAF hands over — and returns **null** for a `file://` one. Anything
     * reaching this loader by another route (a share intent, a test, a future
     * caller with a path) would therefore be typed as "not a PDF" and sent to
     * `ImageDecoder`, which fails with `DecodeException: unimplemented` — an
     * error that names the decoder rather than the actual mistake.
     *
     * Found exactly that way: `ReceiptImageLoaderTest` feeds `file://` URIs and
     * every PDF case failed inside the image path.
     *
     * `%PDF-` is the format's magic number and is required to be at the start of
     * the file, so this is the format's own answer rather than a guess.
     */
    private fun isPdf(uri: Uri): Boolean {
        val type = context.contentResolver.getType(uri).orEmpty()
        if (type == PDF_MIME || type.endsWith("/pdf")) return true
        if (type.startsWith("image/")) return false

        return runCatching {
            context.contentResolver.openInputStream(uri)?.use { stream ->
                val header = ByteArray(PDF_MAGIC.size)
                val read = stream.read(header)
                read == PDF_MAGIC.size && header.contentEquals(PDF_MAGIC)
            } ?: false
        }.getOrDefault(false)
    }

    private fun decodeImage(uri: Uri): Bitmap {
        val source = ImageDecoder.createSource(context.contentResolver, uri)
        return ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
            // ARGB_8888 rather than the default hardware bitmap: ML Kit needs
            // readable pixels, and a HARDWARE config throws the moment anything
            // asks for them. It fails at recognition time, one layer away from
            // the cause, which is why it is set here.
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            decoder.isMutableRequired = false

            val longEdge = maxOf(info.size.width, info.size.height)
            if (longEdge > MAX_LONG_EDGE) {
                val scale = MAX_LONG_EDGE.toFloat() / longEdge
                decoder.setTargetSize(
                    (info.size.width * scale).roundToInt().coerceAtLeast(1),
                    (info.size.height * scale).roundToInt().coerceAtLeast(1),
                )
            }
        }
    }

    /**
     * The first page, rendered onto white.
     *
     * The white fill is load-bearing: `PdfRenderer` draws onto a *transparent*
     * bitmap, so a page rendered without it arrives as black glyphs on nothing,
     * and every recogniser reads that as a blank page. It fails silently and
     * looks like a PDF the model simply could not handle.
     */
    private fun renderFirstPdfPage(uri: Uri): Bitmap {
        val descriptor: ParcelFileDescriptor = context.contentResolver
            .openFileDescriptor(uri, "r")
            ?: error("Could not open the chosen file.")

        return descriptor.use { fd ->
            PdfRenderer(fd).use { renderer ->
                check(renderer.pageCount > 0) { "That PDF has no pages." }
                renderer.openPage(0).use { page ->
                    // PdfRenderer's page size is in points (1/72 inch). Scaling
                    // to the long-edge cap lands close to §5.3's 300 DPI
                    // equivalent for a receipt-sized page without hardcoding a
                    // DPI that would be wrong for A4.
                    val longEdge = maxOf(page.width, page.height)
                    val scale = (MAX_LONG_EDGE.toFloat() / longEdge).coerceAtMost(MAX_PDF_SCALE)
                    val bitmap = Bitmap.createBitmap(
                        (page.width * scale).roundToInt().coerceAtLeast(1),
                        (page.height * scale).roundToInt().coerceAtLeast(1),
                        Bitmap.Config.ARGB_8888,
                    )
                    Canvas(bitmap).drawColor(Color.WHITE)
                    page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    bitmap
                }
            }
        }
    }

    /** How many pages [uri] holds, or 1 for anything that is not a PDF. */
    public fun pageCount(uri: Uri): Int {
        if (!isPdf(uri)) return 1
        return runCatching {
            context.contentResolver.openFileDescriptor(uri, "r")?.use { fd ->
                PdfRenderer(fd).use { it.pageCount }
            } ?: 1
        }.getOrDefault(1)
    }

    /**
     * Downscales a bitmap already in memory — the camera path.
     *
     * `ImageCapture` hands back a frame at the sensor's resolution, so the same
     * cap has to be applied here as to a decoded file, or the two inputs would
     * reach the recogniser at wildly different sizes and produce results that
     * cannot be compared against one corpus.
     */
    public fun downscale(bitmap: Bitmap): Bitmap {
        val longEdge = maxOf(bitmap.width, bitmap.height)
        if (longEdge <= MAX_LONG_EDGE) return bitmap

        val scale = MAX_LONG_EDGE.toFloat() / longEdge
        return Bitmap.createScaledBitmap(
            bitmap,
            (bitmap.width * scale).roundToInt().coerceAtLeast(1),
            (bitmap.height * scale).roundToInt().coerceAtLeast(1),
            true,
        )
    }

    /**
     * The bitmap as bytes, for storage (ADR-0023).
     *
     * **PNG, not JPEG, and lossless is the point.** The stored image is the
     * one the recogniser read, kept so that "why did OCR misread this line"
     * stays answerable later. Re-encoding through a lossy codec would mean the
     * bytes on disk are *not* the bytes that were recognised, and the
     * artefacts a JPEG adds to thin thermal-printer strokes are exactly the
     * kind that change what a recogniser sees.
     *
     * It is already downscaled to [MAX_LONG_EDGE], which is where the ~15x
     * saving ADR-0023 counted on comes from; PNG on top of that is a receipt
     * of a few hundred KB rather than a few MB.
     */
    public fun encode(bitmap: Bitmap): ByteArray =
        java.io.ByteArrayOutputStream().use { out ->
            // `quality` is ignored for PNG; the parameter is not optional.
            bitmap.compress(Bitmap.CompressFormat.PNG, PNG_QUALITY_IGNORED, out)
            out.toByteArray()
        }

    public companion object {
        /** §5.3's cap. Also the size ADR-0023 stores as the attachment. */
        public const val MAX_LONG_EDGE: Int = 1600

        /** PNG is lossless; `compress` takes the argument and ignores it. */
        private const val PNG_QUALITY_IGNORED = 100

        /**
         * Never enlarge a small PDF past this.
         *
         * A receipt-sized PDF page is a few hundred points, so an uncapped
         * long-edge scale would blow it up 5-6x and hand the recogniser a
         * blurry upscale that reads worse than the original, not better.
         */
        private const val MAX_PDF_SCALE = 3f

        private const val PDF_MIME = "application/pdf"

        /** `%PDF-`, which the format requires at offset zero. */
        private val PDF_MAGIC = byteArrayOf(0x25, 0x50, 0x44, 0x46, 0x2D)
    }
}
