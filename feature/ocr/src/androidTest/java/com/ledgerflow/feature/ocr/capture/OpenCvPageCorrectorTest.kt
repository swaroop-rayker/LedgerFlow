package com.ledgerflow.feature.ocr.capture

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import com.ledgerflow.core.domain.ingest.Reconciliation
import com.ledgerflow.core.model.LineItemKind
import com.ledgerflow.core.model.Money
import com.ledgerflow.feature.ocr.extraction.ReceiptExtractor
import com.ledgerflow.feature.ocr.recognition.MlKitReceiptTextRecognizer
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith

/**
 * [OpenCvPageCorrector] on real OpenCV and real ML Kit (ADR-0024).
 *
 * The page is drawn, then drawn again onto a dark surface **in perspective** —
 * the top edge much narrower than the bottom, as a receipt held below the camera
 * looks. That is a synthetic photograph, not corpus material: what it tests is
 * the geometry the warp exists for, on the platform, not how well thermal paper
 * reads.
 *
 * **Measured before this was written**, over five angles on the device: a mild
 * keystone reads exactly with or without correction; a strong keystone, a side
 * angle, a keystone with tilt and an extreme side angle read 3, 4-without-total,
 * 2 and 0 items uncorrected — and every one reads 4 items, balanced, corrected.
 * The four that fail uncorrected are the cases below, and each asserts both
 * halves: that the uncorrected read does **not** balance (the premise — if ML
 * Kit ever copes on its own, that goes red and the case has stopped proving
 * anything), and that the corrected read does.
 */
@RunWith(AndroidJUnit4::class)
class OpenCvPageCorrectorTest {

    private val corrector = OpenCvPageCorrector()
    private val recognizer = MlKitReceiptTextRecognizer()

    @Test
    fun aPagePhotographedAtAnAngle_readsExactlyOnlyOnceWarped() = runBlocking {
        val page = invoicePage()
        recognizer.recognize(page) // warm the model

        ANGLES.forEach { (label, corners) ->
            val photo = inPerspective(page, corners)

            val uncorrected = ReceiptExtractor.extract(recognizer.recognize(photo))
            val started = System.nanoTime()
            val corrected = corrector.correct(photo)
            val millis = (System.nanoTime() - started) / 1_000_000
            val bill = ReceiptExtractor.extract(recognizer.recognize(corrected))
            println("warp $label: ${millis}ms, uncorrected=${uncorrected.amount} corrected=${bill.amount}")

            assertWithMessage("$label: the uncorrected read must fail, or this case proves nothing")
                .that(Reconciliation.of(uncorrected.lines, uncorrected.amount))
                .isNotInstanceOf(Reconciliation.Balanced::class.java)

            assertWithMessage("$label: warped").that(corrected).isNotSameInstanceAs(photo)
            assertWithMessage("$label: items")
                .that(bill.lines.count { it.kind == LineItemKind.ITEM }).isEqualTo(EXPECTED_ITEMS)
            assertWithMessage("$label: total").that(bill.amount).isEqualTo(Money(61_400L))
            assertWithMessage("$label: balanced")
                .that(Reconciliation.of(bill.lines, bill.amount))
                .isInstanceOf(Reconciliation.Balanced::class.java)
            // §11's 2.5 s budget covers the whole read; the warp may take a small share of it.
            assertWithMessage("$label: warp time").that(millis).isLessThan(WARP_BUDGET_MILLIS)
        }
    }

    /** A flat page filling the frame has no outline to find: returned untouched. */
    @Test
    fun aPageWithNoBackground_isReturnedUntouched() {
        val page = invoicePage()
        assertThat(corrector.correct(page)).isSameInstanceAs(page)
    }

    /** A plain surface with no page on it: returned untouched, never stretched. */
    @Test
    fun aFrameWithNoPage_isReturnedUntouched() {
        val empty = Bitmap.createBitmap(1200, 1600, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.DKGRAY) }
        assertThat(corrector.correct(empty)).isSameInstanceAs(empty)
    }

    private fun invoicePage(): Bitmap {
        val width = 1000
        val height = 1400
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap).apply { drawColor(Color.WHITE) }
        val body = Paint().apply { color = Color.BLACK; textSize = 34f; isAntiAlias = true }
        val heading = Paint(body).apply { textSize = 48f }
        canvas.drawText("VALUE MART RETAIL LTD", 60f, 110f, heading)
        LINES.forEachIndexed { index, (label, amount) ->
            val y = 200f + index * 62f
            canvas.drawText(label, 60f, y, body)
            if (amount != null) canvas.drawText(amount, width - 60f - body.measureText(amount), y, body)
        }
        return bitmap
    }

    /** The page onto a dark surface, its corners at [corners] (TL, TR, BR, BL). */
    private fun inPerspective(page: Bitmap, corners: FloatArray): Bitmap {
        val photo = Bitmap.createBitmap(1800, 2400, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(photo).apply { drawColor(Color.rgb(60, 55, 50)) }
        val w = page.width.toFloat()
        val h = page.height.toFloat()
        val matrix = Matrix().apply {
            setPolyToPoly(
                floatArrayOf(0f, 0f, w, 0f, w, h, 0f, h), 0,
                corners, 0,
                4,
            )
        }
        canvas.drawBitmap(page, matrix, Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG))
        return photo
    }

    private companion object {
        const val WARP_BUDGET_MILLIS = 800L
        const val EXPECTED_ITEMS = 4

        /** Corners on an 1800x2400 frame: TL, TR, BR, BL. */
        val ANGLES = listOf(
            "strong keystone" to floatArrayOf(720f, 500f, 1080f, 520f, 1640f, 2150f, 160f, 2120f),
            "side angle" to floatArrayOf(300f, 200f, 1500f, 700f, 1500f, 1700f, 300f, 2200f),
            "keystone and tilt" to floatArrayOf(820f, 300f, 1300f, 520f, 1400f, 2300f, 120f, 1900f),
            "extreme side angle" to floatArrayOf(200f, 100f, 1600f, 900f, 1600f, 1500f, 200f, 2300f),
        )

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
