package com.ledgerflow.feature.ocr.capture

import android.content.Context
import android.graphics.Bitmap
import androidx.annotation.RequiresApi
import android.os.Build
import android.graphics.BitmapFactory
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

    /**
     * Decodes an image, downsampling to [MAX_RECOGNITION_EDGE] as it goes.
     *
     * **Two paths, because `ImageDecoder` is API 28 and `minSdk` is 26.**
     * Without the split this crashes on Android 8.0 and 8.1 the moment a user
     * imports a photo — `NoClassDefFoundError`, from a line that reads as
     * perfectly ordinary. Android Lint says so; nothing was listening, because
     * `preMergeCheck` runs lint on `:app` alone and never on a library module.
     *
     * Downsampling during the decode rather than after it is the point of
     * both paths: a full-resolution receipt photo is tens of megabytes as
     * `ARGB_8888`, and decoding it only to shrink it is how a capture screen
     * meets `OutOfMemoryError` on the devices least able to spare the memory.
     */
    private fun decodeImage(uri: Uri): Bitmap =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            decodeWithImageDecoder(uri)
        } else {
            decodeWithBitmapFactory(uri)
        }

    /**
     * The pre-API-28 path.
     *
     * `inSampleSize` only halves, so the result lands somewhere between the
     * cap and half of it; [cap] finishes the job exactly. Two passes over the
     * file — bounds, then pixels — which is what `inJustDecodeBounds` is for.
     */
    private fun decodeWithBitmapFactory(uri: Uri): Bitmap {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri)?.use { stream ->
            BitmapFactory.decodeStream(stream, null, bounds)
        } ?: error("Could not open the chosen file.")

        val longEdge = maxOf(bounds.outWidth, bounds.outHeight)
        check(longEdge > 0) { "That file is not an image." }

        var sample = 1
        while (longEdge / (sample * 2) >= MAX_RECOGNITION_EDGE) sample *= 2

        val options = BitmapFactory.Options().apply {
            inSampleSize = sample
            // ML Kit needs readable pixels, the same requirement the other
            // path sets ALLOCATOR_SOFTWARE for.
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        val decoded = context.contentResolver.openInputStream(uri)?.use { stream ->
            BitmapFactory.decodeStream(stream, null, options)
        } ?: error("That image could not be decoded.")

        return cap(decoded, MAX_RECOGNITION_EDGE)
    }

    @RequiresApi(Build.VERSION_CODES.P)
    private fun decodeWithImageDecoder(uri: Uri): Bitmap {
        val source = ImageDecoder.createSource(context.contentResolver, uri)
        return ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
            // ARGB_8888 rather than the default hardware bitmap: ML Kit needs
            // readable pixels, and a HARDWARE config throws the moment anything
            // asks for them. It fails at recognition time, one layer away from
            // the cause, which is why it is set here.
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            decoder.isMutableRequired = false

            // **[MAX_RECOGNITION_EDGE], not [MAX_LONG_EDGE].** What is decoded
            // is what ML Kit reads; the smaller cap is for what is *stored*.
            val longEdge = maxOf(info.size.width, info.size.height)
            if (longEdge > MAX_RECOGNITION_EDGE) {
                val scale = MAX_RECOGNITION_EDGE.toFloat() / longEdge
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
                    val scale =
                        (MAX_RECOGNITION_EDGE.toFloat() / longEdge).coerceAtMost(MAX_PDF_SCALE)
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
     * Caps a bitmap at [MAX_RECOGNITION_EDGE] — the camera path's input.
     *
     * `ImageCapture` hands back a frame at the sensor's resolution, which on a
     * modern phone is far past anything ML Kit gains from and well into
     * out-of-memory territory once the recogniser takes its own copy.
     *
     * The same cap as a decoded file gets, so camera and import reach the
     * recogniser at comparable sizes and can be measured against one corpus.
     */
    public fun forRecognition(bitmap: Bitmap): Bitmap = cap(bitmap, MAX_RECOGNITION_EDGE)

    /**
     * Caps a bitmap at [MAX_LONG_EDGE] — what is kept on disk (ADR-0023).
     *
     * Deliberately smaller than what was recognised. The stored copy exists so
     * a later "why did OCR read this wrong" is answerable and so a restore can
     * show the user their receipt; neither needs the resolution the recogniser
     * wanted, and ADR-0023's ~15x storage saving is the whole reason the cap
     * exists.
     */
    public fun downscale(bitmap: Bitmap): Bitmap = cap(bitmap, MAX_LONG_EDGE)

    private fun cap(bitmap: Bitmap, edge: Int): Bitmap {
        val longEdge = maxOf(bitmap.width, bitmap.height)
        if (longEdge <= edge) return bitmap

        val scale = edge.toFloat() / longEdge
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
        /**
         * The cap on what is **stored** (ADR-0023, §5.3).
         *
         * ~250 KB per receipt instead of ~4 MB, and readable by a human,
         * which is all the stored copy has to be.
         */
        public const val MAX_LONG_EDGE: Int = 1600

        /**
         * The cap on what is **recognised**, and it is deliberately larger.
         *
         * Recognition used to run on the stored size, and the arithmetic says
         * that was costing accuracy on exactly the text the §12 gate measures:
         * a phone photo at 3000x4000 capped to 1600 leaves a receipt occupying
         * perhaps 700 px of width, so a 42-character thermal line lands at
         * roughly 12-14 px of glyph height — at or under where ML Kit becomes
         * reliable.
         *
         * 2560 restores about 20 px on the same shot at ~2.5x the pixels of
         * 1600. The ceiling is memory rather than quality: ARGB_8888 at this
         * size is ~20 MB before ML Kit takes its own copy, and `ImageCapture`
         * would otherwise hand over a full sensor frame several times larger.
         *
         * **The number is reasoned, not yet measured against paper.** Wall
         * clock against §11's 2.5 s budget is measurable on the device and is;
         * whether the extra pixels actually raise item recall needs a real
         * photograph in the corpus, and until one exists this is an argument
         * rather than a result.
         */
        public const val MAX_RECOGNITION_EDGE: Int = 2560

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
