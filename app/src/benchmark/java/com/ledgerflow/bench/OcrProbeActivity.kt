package com.ledgerflow.bench

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.lifecycleScope
import com.ledgerflow.core.designsystem.theme.LfTheme
import com.ledgerflow.feature.ocr.recognition.ReceiptTextRecognizer
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.launch

/**
 * Reads a drawn line of text with the app's own recognizer, in a build shrunk
 * the way release is (BUG38).
 *
 * The debug build can never show BUG38: it is not shrunk, so the ML Kit
 * registrar keeps the constructor that R8 removed from release. This activity
 * lives in `src/benchmark`, runs in `com.ledgerflow.bench` -- release code --
 * and reports "OCR ok: <text>" or "OCR failed: <why>" for
 * `Bug38_OcrWorksInAShrunkBuildTest` to read.
 */
@AndroidEntryPoint
class OcrProbeActivity : ComponentActivity() {

    @Inject lateinit var recognizer: ReceiptTextRecognizer

    private var status by mutableStateOf("OCR probe starting")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { LfTheme { Text(text = status) } }
        lifecycleScope.launch {
            status = runCatching {
                val text = recognizer.recognize(sample()).elements.joinToString(" ") { it.text }
                if (text.isBlank()) "OCR failed: nothing read" else "OCR ok: $text"
            }.getOrElse { "OCR failed: $it" }
            Log.i(TAG, status)
        }
    }

    /** Black text on white, large enough that a working recognizer cannot miss it. */
    private fun sample(): Bitmap {
        val bitmap = Bitmap.createBitmap(WIDTH, HEIGHT, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.WHITE)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.BLACK
            textSize = TEXT_SIZE
        }
        canvas.drawText(SAMPLE, MARGIN, BASELINE, paint)
        return bitmap
    }

    private companion object {
        const val TAG = "OcrProbe"
        const val SAMPLE = "TOTAL 245.00"
        const val WIDTH = 900
        const val HEIGHT = 240
        const val TEXT_SIZE = 96f
        const val MARGIN = 40f
        const val BASELINE = 150f
    }
}
