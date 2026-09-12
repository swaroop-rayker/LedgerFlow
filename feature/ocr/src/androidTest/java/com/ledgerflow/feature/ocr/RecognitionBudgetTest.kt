package com.ledgerflow.feature.ocr

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.ledgerflow.feature.ocr.capture.ReceiptImageLoader
import com.ledgerflow.feature.ocr.recognition.MlKitReceiptTextRecognizer
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith

/**
 * §11's 2.5 s OCR budget, measured for the first time.
 *
 * The budget has been in the spec since P0 and nothing had ever checked it.
 * It matters now because recognition moved off the storage-sized image onto a
 * larger one, and ADR-0021 already names the thing most likely to breach it:
 * two script models running on every page.
 *
 * **Printed, not only asserted.** The assertion is a ceiling; the number is
 * what a future reader needs when they wonder whether a resolution change is
 * affordable.
 */
@RunWith(AndroidJUnit4::class)
class RecognitionBudgetTest {

    private val recognizer = MlKitReceiptTextRecognizer()

    /** A receipt-shaped page of dense monospaced text. */
    private fun receipt(longEdge: Int): Bitmap {
        val width = (longEdge * 0.42f).toInt()
        val bitmap = Bitmap.createBitmap(width, longEdge, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap).apply { drawColor(Color.WHITE) }
        val paint = Paint().apply {
            color = Color.BLACK
            textSize = longEdge / 60f
            isAntiAlias = true
        }
        val lines = 46
        val step = longEdge / (lines + 4f)
        repeat(lines) { line ->
            val y = step * (line + 2)
            canvas.drawText("ITEM DESCRIPTION ${line + 1} 500ML", step, y, paint)
            canvas.drawText("%.2f".format((line + 1) * 13.5), width - step * 5, y, paint)
        }
        return bitmap
    }

    @Test
    fun recognitionStaysInsideTheBudget() = runBlocking {
        // Warm the model: ML Kit loads a 10 MB native pipeline on first use,
        // and charging that to the first receipt would measure the install
        // rather than the budget.
        recognizer.recognize(receipt(640))

        listOf(
            "storage size" to ReceiptImageLoader.MAX_LONG_EDGE,
            "recognition size" to ReceiptImageLoader.MAX_RECOGNITION_EDGE,
        ).forEach { (label, edge) ->
            val page = receipt(edge)
            val started = System.nanoTime()
            val recognized = recognizer.recognize(page)
            val millis = (System.nanoTime() - started) / 1_000_000

            println("OCR budget: $label (${page.width}x${page.height}) -> ${millis}ms, ${recognized.elements.size} runs")

            assertThat(recognized.elements).isNotEmpty()
            assertThat(millis).isLessThan(BUDGET_MILLIS)
        }
    }

    private companion object {
        /** SPEC.md §11. */
        const val BUDGET_MILLIS = 2_500L
    }
}
