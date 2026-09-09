package com.ledgerflow.feature.ocr.capture

import android.graphics.Bitmap
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ledgerflow.core.common.di.IoDispatcher
import com.ledgerflow.feature.ocr.recognition.ReceiptTextRecognizer
import com.ledgerflow.feature.ocr.recognition.RecognizedPage
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Receipt capture (SPEC.md §5.3).
 *
 * ## What this deliberately stops short of
 *
 * It captures, decodes, downscales and **recognises** — and then reports how
 * much text came back. It does not extract line items, because §5.3's pipeline
 * does not exist yet, and a screen that showed a plausible-looking bill it had
 * not actually parsed would be the worst possible placeholder.
 *
 * What it does buy is the thing nothing else could: proof that the whole
 * capture path works on real hardware against a real receipt. ML Kit has been
 * exercised on a synthetic bitmap in a test; this is the first thing that
 * points it at paper.
 *
 * ## Nothing here writes to the ledger, or anywhere else
 *
 * No `pending_transaction`, no `attachment`, no file. Law 1 is not even in
 * reach — the candidate this will eventually produce is written by the
 * extraction step, and approval remains `ApproveTransactionUseCase`'s alone.
 * Until then a capture is a read that leaves no trace, which is also why
 * cancelling one costs nothing to clean up.
 *
 * ## Dispatchers are injected
 *
 * `CLAUDE.md` §5. Decode and recognition both run on [io]: `StrictMode` runs
 * with `penaltyDeath` in debug, so a decode on the main thread kills the
 * process rather than merely being slow.
 */
@HiltViewModel
public class OcrCaptureViewModel @Inject constructor(
    private val recognizer: ReceiptTextRecognizer,
    private val images: ReceiptImageLoader,
    @param:IoDispatcher private val io: CoroutineDispatcher,
) : ViewModel() {

    private val internalState = MutableStateFlow(OcrCaptureUiState())
    public val state: StateFlow<OcrCaptureUiState> = internalState.asStateFlow()

    public fun onEvent(event: OcrCaptureEvent) {
        when (event) {
            is OcrCaptureEvent.CameraPermissionChanged -> internalState.update {
                it.copy(
                    cameraPermission = if (event.granted) {
                        CameraPermission.Granted
                    } else {
                        CameraPermission.Denied
                    },
                )
            }

            is OcrCaptureEvent.FrameCaptured -> read(SOURCE_CAMERA) {
                images.downscale(event.bitmap)
            }

            is OcrCaptureEvent.FileChosen -> {
                // A cancelled picker is silent. Reporting "import failed" for a
                // deliberate back-press is how a screen teaches people to
                // ignore its messages -- the same position §5.9's export takes.
                val uri = event.uri ?: return
                readFile(uri)
            }

            is OcrCaptureEvent.CaptureFailed -> internalState.update {
                it.copy(reading = false, failure = event.reason)
            }

            OcrCaptureEvent.Dismissed -> internalState.update {
                it.copy(result = null, failure = null)
            }
        }
    }

    private fun readFile(uri: Uri) {
        viewModelScope.launch {
            val pages = withContext(io) { runCatching { images.pageCount(uri) }.getOrDefault(1) }
            val label = if (pages > 1) {
                // Stated rather than silently dropped: a second page ignored
                // without a word is worse than one refused out loud.
                "$SOURCE_FILE · page 1 of $pages"
            } else {
                SOURCE_FILE
            }
            read(label) { images.load(uri) }
        }
    }

    /**
     * The one path every input funnels through.
     *
     * [decode] runs on [io] and may throw; a failure becomes a sentence rather
     * than an exception reaching the UI. `Result` at this boundary would be
     * ceremony — there is exactly one caller and one recovery, which is to say
     * so and let the user try again.
     */
    private fun read(sourceLabel: String, decode: suspend () -> Bitmap) {
        internalState.update { it.copy(reading = true, failure = null, result = null) }

        viewModelScope.launch {
            val outcome = runCatching {
                withContext(io) {
                    val bitmap = decode()
                    recognizer.recognize(bitmap)
                }
            }

            internalState.update { current ->
                outcome.fold(
                    onSuccess = { page ->
                        current.copy(
                            reading = false,
                            result = page.toSummary(sourceLabel),
                            failure = null,
                        )
                    },
                    onFailure = {
                        current.copy(
                            reading = false,
                            result = null,
                            // No exception text. A revoked grant, a corrupt file
                            // and an out-of-memory decode are one sentence to
                            // the person holding the phone.
                            failure = "That image could not be read. Try again, " +
                                "or pick a different one.",
                        )
                    },
                )
            }
        }
    }

    private fun RecognizedPage.toSummary(sourceLabel: String) = RecognitionSummary(
        elementCount = elements.size,
        // Reading order is not guaranteed by the recogniser, and imposing one
        // is line reconstruction's job, not this screen's. Sorted top-to-bottom
        // so the preview at least resembles the page.
        preview = elements
            .sortedBy { it.centerY }
            .take(PREVIEW_RUNS)
            .joinToString(" ") { it.text }
            .ifBlank { "No text found." },
        sourceLabel = sourceLabel,
    )

    private companion object {
        const val SOURCE_CAMERA = "Camera"
        const val SOURCE_FILE = "Imported"

        /** Enough to recognise your own receipt, short enough not to be a wall. */
        const val PREVIEW_RUNS = 24
    }
}
