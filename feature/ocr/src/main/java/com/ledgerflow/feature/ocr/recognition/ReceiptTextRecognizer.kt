package com.ledgerflow.feature.ocr.recognition

import android.graphics.Bitmap
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine

/**
 * One recognised glyph run, with where it sat on the page.
 *
 * **Geometry is kept, and that is the point of this type.** §5.3's pipeline
 * reconstructs lines by clustering elements on their y-centroid and infers the
 * amount column from x — neither is possible from a flat string, and ML Kit's
 * own line grouping is not good enough on a thermal receipt, where a wide gap
 * between an item name and its price frequently splits or joins the wrong runs.
 *
 * Coordinates are pixels in the recognised image's own frame, so a caller that
 * downscaled before recognising must map back itself rather than assume these
 * relate to the original. `Float` throughout: these are positions, not money,
 * and Law 3 bans `Float` for an amount rather than for arithmetic in general.
 */
public data class RecognizedElement(
    val text: String,
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
) {
    /** Vertical middle — the value §5.3's line clustering bands on. */
    public val centerY: Float get() = (top + bottom) / 2f
}

/** Everything the recognizer saw, in no particular order. */
public data class RecognizedPage(
    val elements: List<RecognizedElement>,
) {
    public val isEmpty: Boolean get() = elements.isEmpty()
}

/**
 * ML Kit's on-device text recognizer, wrapped so the rest of P4 never imports
 * `com.google.mlkit` (ADR-0021).
 *
 * ## Why a wrapper rather than calling ML Kit at the call site
 *
 * Three reasons, in order of how much they matter.
 *
 * **It keeps the extraction pipeline testable off-device.** Everything §5.3
 * does after recognition — line reconstruction, column inference, line
 * classification, reconciliation — is arithmetic over [RecognizedElement], and
 * arithmetic with a correct answer belongs in a JVM test with hand-written
 * input. If those steps took an ML Kit `Text` they would each need a device and
 * a real image to exercise, and the corpus would end up testing the recognizer
 * and the parser at once, with no way to tell which one failed.
 *
 * **It is the seam ADR-0021 would be reversed at.** That decision is recorded
 * as close and cheap to revisit; a recognizer reachable only through this
 * interface is one that can be swapped without touching the pipeline.
 *
 * **It bounds the blast radius of a Google binary blob.** The library is
 * closed-source, ships native code, and drags in a telemetry uploader. One file
 * importing it is one file to review when it is upgraded.
 *
 * ## Latin only, for now
 *
 * `TextRecognizerOptions.DEFAULT_OPTIONS` is the bundled Latin model. Adding
 * Devanagari costs +0.61 MB (measured, ADR-0021), so the decision is not about
 * size: §12's corpus diversity floor asks for a Devanagari-bearing receipt, and
 * that fixture is what should decide it rather than a guess made in advance.
 */
public interface ReceiptTextRecognizer {

    /**
     * Recognises [bitmap], or throws.
     *
     * Deliberately not returning a `Result`: a recognition failure here is an
     * ML Kit internal error, not a domain outcome, and §5.3's caller has to
     * decide what an unreadable image means for the candidate. Wrapping it as a
     * typed error at this level would invent a vocabulary the pipeline does not
     * yet have.
     */
    public suspend fun recognize(bitmap: Bitmap): RecognizedPage
}

/**
 * The ML Kit implementation.
 *
 * **`@Singleton`, because the recognizer is expensive to build and holds native
 * resources.** ML Kit's client loads the bundled model on first use; creating
 * one per capture would pay that on every receipt.
 *
 * The client is never closed. That is deliberate for a process-scoped
 * singleton — closing it would only be correct if something knew no further
 * recognition was coming, and nothing does. If OCR ever moves behind a
 * `WorkManager` job with a bounded lifetime, this becomes a `Closeable` owned
 * by that job instead.
 */
@Singleton
public class MlKitReceiptTextRecognizer @Inject constructor() : ReceiptTextRecognizer {

    private val client by lazy {
        TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    }

    override suspend fun recognize(bitmap: Bitmap): RecognizedPage {
        val image = InputImage.fromBitmap(bitmap, 0)

        val text = suspendCancellableCoroutine { continuation ->
            client.process(image)
                .addOnSuccessListener { result -> continuation.resume(result) }
                .addOnFailureListener { error -> continuation.resumeWithException(error) }
        }

        // Element grain, not line grain. ML Kit's own line grouping merges runs
        // across the wide gap between an item name and its price on a receipt,
        // which is exactly the gap §5.3's column inference needs to see.
        val elements = text.textBlocks
            .asSequence()
            .flatMap { block -> block.lines.asSequence() }
            .flatMap { line -> line.elements.asSequence() }
            .mapNotNull { element ->
                val box = element.boundingBox ?: return@mapNotNull null
                RecognizedElement(
                    text = element.text,
                    left = box.left.toFloat(),
                    top = box.top.toFloat(),
                    right = box.right.toFloat(),
                    bottom = box.bottom.toFloat(),
                )
            }
            .toList()

        return RecognizedPage(elements)
    }
}
