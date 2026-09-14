package com.ledgerflow.feature.ocr.recognition

import android.graphics.Bitmap
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.devanagari.DevanagariTextRecognizerOptions
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
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
    /** Which script model read this run. Kept for [RecognizedPage.merge]. */
    val script: RecognitionScript = RecognitionScript.LATIN,
) {
    /** Vertical middle — the value §5.3's line clustering bands on. */
    public val centerY: Float get() = (top + bottom) / 2f

    /**
     * Horizontal middle.
     *
     * Needed by skew estimation: a photographed page's rows slope, so where a
     * run sits vertically depends on how far along the row it is, and the
     * correction is a function of x.
     */
    public val centerX: Float get() = (left + right) / 2f

    /** Box height. Zero-height boxes are filtered before they reach geometry. */
    internal val height: Float get() = bottom - top

    /**
     * How much of the shorter box's height these two share vertically, 0..1.
     *
     * The test for "are these on the same printed line", and deliberately a
     * *ratio* rather than a distance: a header glyph and a body glyph on one
     * row differ in height by 3x, and any absolute tolerance is wrong for one
     * of them.
     */
    internal fun verticalOverlapWith(other: RecognizedElement): Float {
        val shared = minOf(bottom, other.bottom) - maxOf(top, other.top)
        val shorter = minOf(height, other.height)
        return if (shorter <= 0f) 0f else (shared / shorter).coerceIn(0f, 1f)
    }

    internal val area: Float get() = (right - left) * (bottom - top)

    /** Intersection over union with [other]. 0 when they do not overlap. */
    internal fun overlapWith(other: RecognizedElement): Float {
        val width = minOf(right, other.right) - maxOf(left, other.left)
        val height = minOf(bottom, other.bottom) - maxOf(top, other.top)
        if (width <= 0f || height <= 0f) return 0f
        val intersection = width * height
        val union = area + other.area - intersection
        return if (union <= 0f) 0f else intersection / union
    }
}

/**
 * The script models this app bundles.
 *
 * **ML Kit's models are per SCRIPT, not per language**, which is the fact that
 * decides what "add Hindi" can mean. Devanagari is one model covering Hindi,
 * Marathi, Nepali, Sanskrit and Konkani; there is no `text-recognition-hindi`
 * artifact and asking for one resolves to nothing.
 *
 * ML Kit ships exactly five: Latin, Chinese, Devanagari, Japanese, Korean.
 * **Kannada and Malayalam have no model and cannot be added** — probed against
 * dl.google.com, not assumed. See ADR-0021.
 */
public enum class RecognitionScript {
    LATIN,
    DEVANAGARI,
}

/** Everything the recognizer saw, in no particular order. */
public data class RecognizedPage(
    val elements: List<RecognizedElement>,
) {
    public val isEmpty: Boolean get() = elements.isEmpty()

    public companion object {

        /**
         * How much two boxes must overlap to be treated as the same run.
         *
         * Both script models read Latin digits, so on an ordinary receipt they
         * return the *same* boxes with the *same* text. 0.6 is deliberately
         * loose: the two models place a box around the same glyphs a few pixels
         * apart, and a strict threshold would keep both copies and double every
         * amount on the page — which is the failure this whole function exists
         * to prevent, and one that would look like a receipt costing twice what
         * it did.
         */
        private const val SAME_RUN_OVERLAP = 0.6f

        /**
         * Combines two script passes over the same image into one page.
         *
         * **This is arithmetic over geometry, so it is unit-tested off-device**
         * — which is the property [ReceiptTextRecognizer] returning
         * [RecognizedElement] rather than ML Kit's own types exists to give.
         *
         * Three cases, and the middle one is the interesting one:
         *
         * - **No overlap** — the Devanagari pass read a run the Latin pass could
         *   not see at all. Kept; this is the entire point of the second pass.
         * - **Overlapping, same text** — both models read the same Latin digits.
         *   One copy kept, [RecognitionScript.LATIN]'s, because that model is
         *   the one specialised for it.
         * - **Overlapping, different text** — the same region read two ways. The
         *   *longer* reading wins, on the reasoning that a model which resolved
         *   more glyphs in the same box saw more of what was there. It is a
         *   heuristic and it is the one thing here a corpus should confirm.
         */
        public fun merge(primary: RecognizedPage, secondary: RecognizedPage): RecognizedPage {
            val merged = primary.elements.toMutableList()

            // BUG22: a secondary run with no Devanagari letter in it contributes
            // nothing the primary pass lacks except digit lookalikes. See
            // [carriesDevanagari].
            secondary.elements.filter { it.isWorthMerging() }.forEach { candidate ->
                val twinIndex = merged.indexOfFirst { existing ->
                    existing.overlapWith(candidate) >= SAME_RUN_OVERLAP
                }
                when {
                    twinIndex < 0 -> merged += candidate
                    candidate.text.length > merged[twinIndex].text.length ->
                        merged[twinIndex] = candidate
                    // else: the existing reading is at least as complete. Keep it.
                }
            }

            return RecognizedPage(merged)
        }

        /**
         * **BUG22 — the Devanagari model's digits are not digits.** Run over a
         * crisp A4 invoice on the device, it returned Bengali and Devanagari
         * lookalikes for Latin figures — `০.০০` for `0.00`, `২` and `२` for
         * `2`, `৪.20` for `8.20` — in boxes a few pixels off the Latin model's.
         * Where the overlap cleared [SAME_RUN_OVERLAP] the equal-length tie
         * kept Latin; where it did not, both survived and rows read
         * `০.০০ 0.00` and `0 01८ 14`, which is garbage in exactly the columns
         * the extractor reads money from.
         *
         * The second pass exists for one reason — words the Latin model cannot
         * read — and a run with no Devanagari letter or vowel sign in it is not
         * one of those. Devanagari *digits* do not qualify either: `ReceiptNumbers`
         * reads ASCII figures only, so a Devanagari-digit run could only ever
         * sit beside the Latin reading as noise. What this gives up is a Latin
         * or numeric run the Latin pass missed entirely and the Devanagari pass
         * caught; on a Latin figure that is the Latin model's own job, and the
         * trade is a stray missed glyph against a duplicated amount column.
         */
        internal fun RecognizedElement.carriesDevanagari(): Boolean =
            text.any { it in DEVANAGARI_BLOCK && it !in DEVANAGARI_DIGITS_AND_DANDAS }

        /**
         * Scoped to runs the Devanagari pass *produced*, so [merge] stays a
         * general combine for any other page it is handed.
         */
        private fun RecognizedElement.isWorthMerging(): Boolean =
            script != RecognitionScript.DEVANAGARI || carriesDevanagari()

        private val DEVANAGARI_BLOCK = 'ऀ'..'ॿ'

        /** `।` `॥` and `०`–`९`: punctuation and figures, not words. */
        private val DEVANAGARI_DIGITS_AND_DANDAS = '।'..'९'
    }
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
 * ## Two scripts, one page
 *
 * Latin and Devanagari, on the owner's instruction. ML Kit's models are per
 * **script**, so Devanagari is what "Hindi" means here and it covers Marathi,
 * Nepali, Sanskrit and Konkani with the same artifact. **Kannada and Malayalam
 * have no ML Kit model at all** — probed, not assumed — so they are not
 * supported and no amount of configuration here changes that. ADR-0021 records
 * what the alternatives would cost.
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

    private val latin by lazy {
        TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    }

    private val devanagari by lazy {
        TextRecognition.getClient(DevanagariTextRecognizerOptions.Builder().build())
    }

    /**
     * Both scripts, **concurrently**.
     *
     * §11 budgets a single receipt page at 2.5 s. Running the two passes in
     * sequence would make the wall-clock cost their sum; `async` makes it
     * roughly their max, since ML Kit's work is native and off this thread
     * either way. That is the difference between a second script costing
     * ~nothing and costing another whole budget.
     *
     * **§11's 2.5 s has never been measured**, and this is now the single thing
     * most likely to breach it. The measurement belongs with the first real
     * receipt, on the device, not here.
     *
     * If it does breach: the cheap fix is to make the Devanagari pass
     * conditional rather than to drop it — run Latin, and only run Devanagari
     * when the Latin pass resolved little. That is deliberately *not* done
     * pre-emptively, because "resolved little" is a threshold and a threshold
     * chosen without a corpus is a guess that will look like a measurement.
     */
    override suspend fun recognize(bitmap: Bitmap): RecognizedPage = coroutineScope {
        val image = InputImage.fromBitmap(bitmap, 0)

        val latinPass = async { read(latin, image, RecognitionScript.LATIN) }
        val devanagariPass = async { read(devanagari, image, RecognitionScript.DEVANAGARI) }

        // Latin is `primary`: where both models read the same run, its reading
        // is kept, because it is the model specialised for that script.
        RecognizedPage.merge(latinPass.await(), devanagariPass.await())
    }

    private suspend fun read(
        client: TextRecognizer,
        image: InputImage,
        script: RecognitionScript,
    ): RecognizedPage {
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
                    script = script,
                )
            }
            .toList()

        return RecognizedPage(elements)
    }
}
