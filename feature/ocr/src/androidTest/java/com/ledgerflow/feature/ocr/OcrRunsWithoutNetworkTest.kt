package com.ledgerflow.feature.ocr

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import com.google.common.truth.Truth.assertThat
import com.ledgerflow.feature.ocr.recognition.MlKitReceiptTextRecognizer
import java.net.InetSocketAddress
import java.net.Socket
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * **Recognition is on-device, enforced rather than claimed** (ADR-0021).
 *
 * ## Why this test is the load-bearing one
 *
 * ADR-0021 accepted `android.permission.INTERNET` into the release APK. Bundled
 * ML Kit merges it from `transport-backend-cct`, Google's telemetry uploader,
 * and R8 refuses to build without those classes — so the permission could not
 * be removed without removing the recogniser.
 *
 * That decision cost the project the thing Law 6 used to be checked by. "No
 * INTERNET in release" was a grep, and greps are cheap and total. What replaced
 * it is two mechanisms, and this is the second: `EXPECTED_MERGED_PERMISSIONS`
 * pins what may be *packaged*, and this test pins what the recogniser may
 * *need*.
 *
 * The distinction matters because it is the one a user cares about. The app
 * holding a permission is a fact about the manifest; a receipt image leaving
 * the device would be a fact about their finances. Only the second is what §1's
 * privacy position actually promises, and only this test can speak to it.
 *
 * ## What it would catch
 *
 * A future ML Kit version that quietly moves recognition, or part of it, behind
 * a server call — which is not far-fetched, because the *unbundled* variant of
 * this same library does exactly that, and the two differ by a coordinate. If
 * bundled ever starts falling back to it, recognition here degrades to an
 * error and this test goes red rather than the app silently uploading receipts.
 *
 * ## Method, and its honest limit
 *
 * Recognition runs on a synthetic bitmap, and the assertion is that it produces
 * text. There is no attempt to intercept traffic: that would need a proxy or a
 * `VpnService` and would test the harness more than the app.
 *
 * **The limit is worth stating plainly.** A green run here means recognition
 * *succeeded* without the network being needed for it, not that no packet was
 * sent — the telemetry uploader may well have fired, and this test cannot see
 * it. That is precisely the gap ADR-0021 accepted with its eyes open, and the
 * reason it records the alternative (`tools:node="remove"`, tested and working)
 * as cheap to return to.
 */
class OcrRunsWithoutNetworkTest {

    private val recognizer = MlKitReceiptTextRecognizer()

    /**
     * A bitmap with legible text on it.
     *
     * Drawn rather than loaded from a fixture: the receipt corpus is private
     * (ADR-0023) and this test asserts nothing about extraction quality, so
     * shipping an image for it would put a file in the repository to prove
     * something a `Canvas` proves for free.
     */
    private fun textBitmap(text: String): Bitmap {
        val bitmap = Bitmap.createBitmap(900, 220, Bitmap.Config.ARGB_8888)
        Canvas(bitmap).apply {
            drawColor(Color.WHITE)
            drawText(
                text,
                40f,
                140f,
                Paint().apply {
                    color = Color.BLACK
                    textSize = 92f
                    isAntiAlias = true
                },
            )
        }
        return bitmap
    }

    /**
     * The recogniser reads an image, and the reading is local.
     *
     * "Local" is argued rather than intercepted: the bundled model is in the
     * APK, so there is no server this could be asking. What the assertion adds
     * is that the *bundled* path is the one actually running — an unbundled
     * client with no Play Services and no network would fail here rather than
     * return text.
     */
    @Test
    fun recognize_readsTextFromABitmap() = runTest {
        val page = recognizer.recognize(textBitmap("TOTAL 473.00"))

        assertThat(page.isEmpty).isFalse()
        val joined = page.elements.joinToString(" ") { it.text }
        assertThat(joined).contains("473")
    }

    /**
     * Geometry survives, because §5.3's pipeline is built on it.
     *
     * Line reconstruction bands elements on their y-centroid and infers the
     * amount column from x. A recogniser wrapper that returned text and dropped
     * boxes would compile, pass the test above, and make every later step
     * impossible — so the boxes are asserted to be real rather than zeroed.
     */
    @Test
    fun recognize_keepsElementGeometry() = runTest {
        val page = recognizer.recognize(textBitmap("RICE 420"))

        assertThat(page.elements).isNotEmpty()
        page.elements.forEach { element ->
            assertThat(element.right).isGreaterThan(element.left)
            assertThat(element.bottom).isGreaterThan(element.top)
            assertThat(element.centerY).isGreaterThan(0f)
        }
    }

    /**
     * A blank image yields nothing, rather than inventing something.
     *
     * The image analogue of the SMS corpus's `"expected": null` cases, and the
     * behaviour §12's precision half depends on: an extractor that hallucinates
     * on an empty page would score perfect recall and be worthless.
     */
    @Test
    fun recognize_ofABlankImage_findsNothing() = runTest {
        val blank = Bitmap.createBitmap(900, 220, Bitmap.Config.ARGB_8888)
        Canvas(blank).drawColor(Color.WHITE)

        assertThat(recognizer.recognize(blank).isEmpty).isTrue()
    }

    /**
     * The device really has no route to a Google host during this run.
     *
     * Not an assertion — a **precondition report**. If the runner happens to be
     * online, the tests above still prove what they prove (the model is in the
     * APK), but they prove it less loudly. Printing the state keeps a green run
     * from being read as stronger evidence than it is, which is the same reason
     * `ReceiptCorpusTest` prints the corpus size beside its gate.
     *
     * Run this suite in aeroplane mode for the strong version.
     */
    @Test
    fun reportWhetherTheDeviceWasOffline() {
        val reachable = runCatching {
            Socket().use { socket ->
                socket.connect(InetSocketAddress("firebaselogging-pa.googleapis.com", 443), 1_500)
                true
            }
        }.getOrDefault(false)

        println(
            if (reachable) {
                "OcrRunsWithoutNetworkTest: device WAS online. Recognition still ran from the " +
                    "bundled model, but for the strong form of this evidence run in aeroplane mode."
            } else {
                "OcrRunsWithoutNetworkTest: device had no route to Google logging -- recognition " +
                    "above ran with no network available at all."
            },
        )
    }
}
