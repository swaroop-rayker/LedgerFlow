package com.ledgerflow.feature.ocr.capture

import androidx.compose.runtime.Immutable

/**
 * The capture screen's state (SPEC.md §5.3).
 *
 * One `@Immutable` data class, one `StateFlow`, events up as a single lambda
 * (`CLAUDE.md` §5).
 *
 * **Camera permission is state, not a branch the screen computes.** §5.3 lists
 * three inputs — camera, gallery, any file via SAF — and `feature/ocr`'s
 * manifest declares the camera feature `required="false"` precisely so a device
 * without one still installs. So a refused permission is an ordinary state in
 * which two of the three inputs still work, not an error screen.
 */
@Immutable
public data class OcrCaptureUiState(
    val cameraPermission: CameraPermission = CameraPermission.Unknown,
    /** True between the shutter and a result. The shutter is disabled meanwhile. */
    val reading: Boolean = false,
    /** True between "Save to Inbox" and the row landing. */
    val saving: Boolean = false,
    /**
     * What happened to the last save, as a sentence for the user.
     *
     * Held rather than fired as an event because the capture screen stays
     * open: a user scanning a stack of receipts wants to see that the last one
     * landed while pointing the camera at the next.
     */
    val saved: String? = null,
    val result: RecognitionSummary? = null,
    /**
     * Why the last attempt produced nothing.
     *
     * A sentence for the user, never an exception's message: a revoked SAF
     * grant, an unreadable file and an out-of-memory decode are the same
     * sentence to the person holding the phone.
     */
    val failure: String? = null,
) {
    /** The shutter is live only with permission and nothing already in flight. */
    public val canCapture: Boolean
        get() = cameraPermission == CameraPermission.Granted && !reading

    /** Importing never needs the camera, which is the point of offering it. */
    public val canImport: Boolean get() = !reading && !saving

    /**
     * A bill can be filed; a page of text cannot.
     *
     * Gated on [RecognitionSummary.isBill] rather than on "something was
     * recognised", so a photograph of a menu does not offer to become a
     * candidate. §5.1's never-drop rule is about *financial messages the app
     * was given*; a picture the user took of the wrong thing is not one, and
     * an Inbox row for it is work rather than safety.
     */
    public val canSave: Boolean get() = result?.isBill == true && !saving && saved == null
}

/**
 * What the system says about `CAMERA`, as three states rather than a boolean.
 *
 * [Unknown] is the first frames of every launch, before the check returns.
 * Collapsing it into [Denied] would flash "camera unavailable" on a healthy
 * install — the same mistake §5.2's listener banner already records for its
 * third, silent state.
 */
public enum class CameraPermission {
    Unknown,
    Granted,
    Denied,
}

/**
 * What the recogniser read and what the extractor made of it (§5.3).
 *
 * **Read-only, and nothing behind it is saved.** Steps 13 to 15 — encrypting
 * the image, cross-source dedupe, writing `pending_transaction` — do not exist
 * yet, so a capture is still a read that leaves no trace and Law 1 is not in
 * reach. The screen says so in as many words rather than implying a candidate
 * was filed.
 *
 * The extraction is here because it is the only way to check steps 6–12
 * against real paper. Every one of them is unit-tested off-device against
 * hand-laid geometry, which proves the arithmetic and proves nothing about
 * whether a thermal printer agrees with it.
 *
 * @param elementCount recognised runs, the unit `RecognizedPage` deals in.
 * @param rawPreview the first few runs joined. Kept, and shown when the
 *   extractor found no bill, because "90 runs and no items" is a different
 *   report from "the image was unreadable" and the raw text is what tells them
 *   apart.
 * @param balance §5.3's reconciliation as a sentence, or null when there was
 *   no total to reconcile against — which is not the same as failing to.
 */
@Immutable
public data class RecognitionSummary(
    val elementCount: Int,
    val rawPreview: String,
    val sourceLabel: String,
    val merchant: String? = null,
    val totalText: String? = null,
    val items: List<ExtractedItemRow> = emptyList(),
    val balance: String? = null,
) {
    /** True when the pipeline produced something that resembles a bill. */
    public val isBill: Boolean get() = items.isNotEmpty() || totalText != null
}

/** One extracted line, already formatted. The screen does no arithmetic. */
@Immutable
public data class ExtractedItemRow(
    val name: String,
    val amountText: String,
)

/** Everything the screen asks the ViewModel to do. */
public sealed interface OcrCaptureEvent {

    /** The permission result came back, or the launch-time check did. */
    public data class CameraPermissionChanged(val granted: Boolean) : OcrCaptureEvent

    /** A frame arrived from the viewfinder. */
    public data class FrameCaptured(val bitmap: android.graphics.Bitmap) : OcrCaptureEvent

    /** A gallery pick or a SAF document. Null when the picker was cancelled. */
    public data class FileChosen(val uri: android.net.Uri?) : OcrCaptureEvent

    /** Capture or decode failed before a bitmap existed. */
    public data class CaptureFailed(val reason: String) : OcrCaptureEvent

    /** Clears the last result so the viewfinder is usable again. */
    public data object Dismissed : OcrCaptureEvent

    /**
     * Files the bill as a candidate (§5.3, steps 13–15).
     *
     * **Not an approval.** It seals the image, writes the `attachment` row and
     * inserts a `pending_transaction` at `PENDING`; Law 1's single writer is
     * untouched and nothing reaches `ledger_entry` until the user taps approve
     * in the Inbox.
     */
    public data object SaveRequested : OcrCaptureEvent
}
