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
    public val canImport: Boolean get() = !reading
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
 * What the recogniser found, in the vocabulary this screen shows.
 *
 * **Deliberately not the extraction.** §5.3's pipeline — line reconstruction,
 * column inference, classification, reconciliation — does not exist yet, and
 * this screen must not pretend it does. What it can honestly report is that the
 * image was read and how much text came back, which is exactly what proves the
 * capture path works end to end on a real device.
 *
 * @param elementCount recognised runs, the unit `RecognizedPage` deals in.
 * @param preview the first few runs joined, so the user can see it read *their*
 *   receipt rather than trusting a number.
 */
@Immutable
public data class RecognitionSummary(
    val elementCount: Int,
    val preview: String,
    val sourceLabel: String,
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
}
