package com.ledgerflow.feature.ocr.capture

import android.graphics.Bitmap
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ledgerflow.core.common.di.IoDispatcher
import com.ledgerflow.core.designsystem.format.MoneyFormat
import com.ledgerflow.core.domain.ingest.AttachmentOutcome
import com.ledgerflow.core.domain.ingest.AttachmentRepository
import com.ledgerflow.core.domain.ingest.DedupeKey
import com.ledgerflow.core.domain.ingest.ExtractedTransaction
import com.ledgerflow.core.domain.ingest.PendingCandidate
import com.ledgerflow.core.domain.ingest.PendingWriteOutcome
import com.ledgerflow.core.domain.ingest.RawIngestRepository
import com.ledgerflow.core.domain.ingest.Reconciliation
import com.ledgerflow.core.domain.ledger.LedgerRepository
import com.ledgerflow.core.model.EntrySource
import com.ledgerflow.core.model.LineItemKind
import com.ledgerflow.feature.ocr.extraction.ReceiptExtractor
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
    private val ledgerRepository: LedgerRepository,
    private val attachments: AttachmentRepository,
    private val ingest: RawIngestRepository,
    @param:IoDispatcher private val io: CoroutineDispatcher,
) : ViewModel() {

    private val internalState = MutableStateFlow(OcrCaptureUiState())
    public val state: StateFlow<OcrCaptureUiState> = internalState.asStateFlow()

    /**
     * The install's base currency (D-02).
     *
     * Needed by the extractor, not only by the formatter: `CurrencyExponent`
     * decides how many minor units a decimal point separates, so a
     * zero-decimal install would otherwise read every amount a hundred times
     * too large.
     */
    private var currency: String = DEFAULT_CURRENCY

    /**
     * The bytes the recogniser actually read, and the extraction it produced.
     *
     * **Held so that Save seals the same image the reading came from.**
     * Re-encoding from the viewfinder at save time would store a frame the
     * pipeline never saw, and ADR-0023's stated reason for keeping the
     * downscaled copy — that "why did OCR read this wrong" stays answerable —
     * would quietly stop being true.
     *
     * Cleared on `Dismissed` and after a successful save, so a second tap
     * cannot file the previous receipt again.
     */
    private var lastRead: ReadResult? = null

    private data class ReadResult(val png: ByteArray, val extracted: ExtractedTransaction)

    init {
        viewModelScope.launch { currency = ledgerRepository.baseCurrency() ?: DEFAULT_CURRENCY }
    }

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

            OcrCaptureEvent.Dismissed -> {
                lastRead = null
                internalState.update { it.copy(result = null, failure = null, saved = null) }
            }

            OcrCaptureEvent.SaveRequested -> save()
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
                    val page = recognizer.recognize(bitmap)
                    // Extraction is pure arithmetic and runs in microseconds,
                    // but it runs on [io] with the recognition rather than on
                    // the main thread after it: StrictMode has penaltyDeath in
                    // debug and there is no reason to find out where the line
                    // is on a 60-line supermarket roll.
                    val extracted = ReceiptExtractor.extract(page, currency)
                    // Encoded here, on IO, and held: this is the image the
                    // recogniser read, which is the one ADR-0023 says to keep.
                    Triple(page, extracted, images.encode(bitmap))
                }
            }

            internalState.update { current ->
                outcome.fold(
                    onSuccess = { (page, extracted, png) ->
                        lastRead = ReadResult(png, extracted)
                        current.copy(
                            reading = false,
                            result = summaryOf(page, extracted, sourceLabel, currency),
                            failure = null,
                            saved = null,
                        )
                    },
                    onFailure = {
                        lastRead = null
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

    /**
     * Steps 13 to 15: the image, the row, the candidate (§5.3).
     *
     * **Three writes and not one of them reaches the ledger.** The image is
     * sealed into `filesDir/attachments/`, an `attachment` row records it, and
     * a `pending_transaction` at `PENDING` points at that row through
     * `raw_ref_id`. Law 1 is untouched: `ApproveTransactionUseCase` is still
     * the only thing that writes `ledger_entry`, and it runs when the user
     * taps approve in the Inbox.
     *
     * **§3.1's cross-source dedupe comes for free**, because the candidate goes
     * through the same insert a bank SMS does. A UPI payment that fired an SMS
     * minutes ago and is now photographed produces one row, not two — the
     * loser is suppressed and stays visible under the Inbox's "Suppressed"
     * filter rather than being discarded.
     *
     * Ordered image-then-row deliberately. A sealed file with no row is
     * garbage that a later sweep can find by name; a row pointing at a file
     * that was never written is a receipt that appears to exist and cannot be
     * opened, which is the failure ADR-0023 asks every drawing surface to
     * handle and there is no reason to manufacture it here.
     */
    private fun save() {
        val read = lastRead ?: return
        internalState.update { it.copy(saving = true, saved = null) }

        viewModelScope.launch {
            val stored = attachments.store(read.png, MIME_PNG)
            val attachmentId = when (stored) {
                is AttachmentOutcome.Stored -> stored.attachmentId
                // The same receipt, scanned twice. The id is the first one's,
                // so the candidate write below sees a `raw_ref_id` it already
                // holds and answers AlreadyPending rather than making a twin.
                is AttachmentOutcome.AlreadyStored -> stored.attachmentId
                AttachmentOutcome.VaultClosed -> return@launch finishSave(
                    "The vault is locked. Open LedgerFlow and try again.",
                )
                AttachmentOutcome.WriteFailed -> return@launch finishSave(
                    "That receipt could not be saved. Check your free space.",
                )
            }

            val candidate = PendingCandidate(
                source = EntrySource.OCR,
                extracted = read.extracted,
                // The same key a bank SMS computes, which is what lets the two
                // land in one bucket (§3.1).
                dedupeKey = DedupeKey.compute(read.extracted, attachmentId),
            )

            val outcome = ingest.recordOcrCandidate(attachmentId, candidate)
            finishSave(
                message = when (outcome) {
                    is PendingWriteOutcome.Created ->
                        "Saved to your Inbox for review."

                    // Retained and visible, never discarded (§3.1) -- so the
                    // message says where it went rather than implying loss.
                    is PendingWriteOutcome.Suppressed ->
                        "Looks like a duplicate. It is in the Inbox under Suppressed."

                    is PendingWriteOutcome.AlreadyPending ->
                        "You have already scanned this receipt. It is in your Inbox."

                    is PendingWriteOutcome.Failed ->
                        "That receipt could not be saved. ${outcome.reason}"
                },
                // A failed write keeps the read, so the user can tap Save
                // again without pointing the camera at the bill a second time.
                // Every other outcome is terminal for this capture.
                clearRead = outcome !is PendingWriteOutcome.Failed,
            )
        }
    }

    private fun finishSave(message: String, clearRead: Boolean = true) {
        if (clearRead) lastRead = null
        internalState.update { it.copy(saving = false, saved = message) }
    }

    private companion object {
        const val SOURCE_CAMERA = "Camera"
        const val SOURCE_FILE = "Imported"

        /** Until `app_meta` answers. Onboarding guarantees a real one exists. */
        const val DEFAULT_CURRENCY = "INR"

        /** What `ReceiptImageLoader.encode` produces. */
        const val MIME_PNG = "image/png"
    }
}

// ── Pure transforms, top-level for the reason ReviewViewModel's are ──────────

/**
 * What to put on the screen, already formatted.
 *
 * The composable does no arithmetic and no money formatting of its own —
 * `MoneyFormat` needs the install's currency, and a card has no business
 * knowing about that.
 *
 * **Top-level and `internal`, so it is testable without a ViewModel.** The
 * ViewModel's own path runs a `Bitmap` through ML Kit, which needs a device;
 * this mapping is the part with an answer that can be checked, and it is the
 * part that would quietly drop a field.
 */
internal fun summaryOf(
    page: RecognizedPage,
    extracted: ExtractedTransaction,
    sourceLabel: String,
    currency: String,
): RecognitionSummary = RecognitionSummary(
        elementCount = page.elements.size,
        // Reading order is not guaranteed by the recogniser. Sorted
        // top-to-bottom so the raw preview at least resembles the page.
        rawPreview = page.elements
            .sortedBy { it.centerY }
            .take(PREVIEW_RUNS)
            .joinToString(" ") { it.text }
            .ifBlank { "No text found." },
        sourceLabel = sourceLabel,
        merchant = extracted.merchantRaw,
        totalText = extracted.amount?.let { MoneyFormat.symbolised(it.minor, currency) },
        items = extracted.lines
            .filter { it.kind == LineItemKind.ITEM }
            .map { line ->
                ExtractedItemRow(
                    name = line.name,
                    amountText = line.total
                        ?.let { MoneyFormat.symbolised(it.minor, currency) }
                        .orEmpty(),
                )
            },
        balance = balanceSentence(extracted, currency),
    )

/**
 * §5.3's reconciliation, in words.
 *
 * Null when there was no total — **not** "off by the whole bill". A total the
 * recogniser could not read has not failed a check; reporting a delta computed
 * against zero would state the sum of the items as a discrepancy, which is a
 * specific and entirely believable wrong answer.
 */
private fun balanceSentence(extracted: ExtractedTransaction, currency: String): String? =
    when (val verdict = Reconciliation.of(extracted.lines, extracted.amount)) {
        is Reconciliation.Balanced -> "Balanced"
        is Reconciliation.Unbalanced ->
            "Off by ${MoneyFormat.symbolised(verdict.delta.minor, currency)}"
        Reconciliation.NotPossible -> null
    }

/** Enough to recognise your own receipt, short enough not to be a wall. */
private const val PREVIEW_RUNS = 24
