package com.ledgerflow.feature.ocr.capture

import android.Manifest
import android.graphics.Bitmap
import android.graphics.Matrix
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.compose.CameraXViewfinder
import androidx.camera.core.ImageCapture
import androidx.camera.core.SurfaceRequest
import androidx.camera.viewfinder.core.ImplementationMode
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.tooling.preview.PreviewFontScale
import androidx.compose.ui.tooling.preview.PreviewLightDark
import androidx.compose.ui.tooling.preview.PreviewScreenSizes
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.ledgerflow.core.designsystem.component.LfActionAlignment
import com.ledgerflow.core.designsystem.component.LfActionRow
import com.ledgerflow.core.designsystem.component.LfButton
import com.ledgerflow.core.designsystem.component.LfButtonStyle
import com.ledgerflow.core.designsystem.component.LfCaptureGuide
import com.ledgerflow.core.designsystem.component.LfCard
import com.ledgerflow.core.designsystem.component.LfScaffold
import com.ledgerflow.core.designsystem.component.LfScreenTitle
import com.ledgerflow.core.designsystem.theme.LfTheme
import kotlinx.coroutines.launch

/**
 * Capture a receipt (SPEC.md §5.3).
 *
 * Stateless: state in, one event lambda out (`CLAUDE.md` §5). The camera's
 * `SurfaceRequest` and `ImageCapture` are the exception and they are hoisted
 * here rather than into the ViewModel — they are Android plumbing tied to this
 * composition's lifecycle, not something a restored state could reconstruct.
 *
 * ## Three inputs, and the two that need no camera come first in the code
 *
 * §5.3 lists camera, gallery and any file via SAF, and
 * `feature/ocr`'s manifest declares the camera feature `required="false"`. So
 * import is not a fallback for a refused permission — it is a first-class way
 * in, and the screen is useful on a device with no camera at all. A layout that
 * put importing behind a "camera unavailable" error would have quietly made the
 * feature camera-only.
 *
 * ## What it does not do yet
 *
 * It reads the image and reports how much text came back. It does not extract
 * line items, because §5.3's pipeline does not exist yet — and a screen showing
 * a plausible bill it had not actually parsed would be the worst placeholder
 * available. The result card says what it is.
 */
@Composable
public fun OcrCaptureScreen(
    state: OcrCaptureUiState,
    onEvent: (OcrCaptureEvent) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted -> onEvent(OcrCaptureEvent.CameraPermissionChanged(granted)) }

    // Gallery and SAF are two contracts because they are two different asks.
    // PickVisualMedia is the photo picker and needs no storage permission at
    // all; OpenDocument is what reaches a PDF, which §5.3 requires.
    val galleryLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia(),
    ) { uri -> onEvent(OcrCaptureEvent.FileChosen(uri)) }

    val documentLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri -> onEvent(OcrCaptureEvent.FileChosen(uri)) }

    LaunchedEffect(Unit) {
        val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED
        onEvent(OcrCaptureEvent.CameraPermissionChanged(granted))
        if (!granted) permissionLauncher.launch(Manifest.permission.CAMERA)
    }

    LfScaffold(modifier = modifier) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(LfTheme.spacing.sm),
        ) {
            LfScreenTitle(title = "Scan a receipt")

            Viewfinder(state = state, onEvent = onEvent)

            state.result?.let {
                ResultCard(
                    summary = it,
                    canSave = state.canSave,
                    saved = state.saved != null,
                    onEvent = onEvent,
                )
            }
            state.failure?.let { FailureCard(it) }
            // An inline card rather than a snackbar, matching FailureCard
            // above: the user is scanning a stack, and "the last one landed"
            // is worth keeping on screen while they line up the next.
            state.saved?.let { SavedCard(it) }

            ImportRow(
                enabled = state.canImport,
                onGallery = {
                    galleryLauncher.launch(
                        androidx.activity.result.PickVisualMediaRequest(
                            ActivityResultContracts.PickVisualMedia.ImageOnly,
                        ),
                    )
                },
                onDocument = { documentLauncher.launch(IMPORT_MIME_TYPES) },
            )

            LfActionRow(alignment = LfActionAlignment.Start) {
                LfButton(text = "Back", style = LfButtonStyle.Text, onClick = onBack)
            }
        }
    }
}

/**
 * The live camera, or the reason there isn't one.
 *
 * Binding runs in a `LaunchedEffect` keyed on the permission, so granting it
 * mid-session starts the camera without the user leaving and coming back.
 */
@Composable
private fun Viewfinder(state: OcrCaptureUiState, onEvent: (OcrCaptureEvent) -> Unit) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var surfaceRequest by remember { mutableStateOf<SurfaceRequest?>(null) }
    var capture by remember { mutableStateOf<ImageCapture?>(null) }

    LaunchedEffect(state.cameraPermission) {
        if (state.cameraPermission != CameraPermission.Granted) return@LaunchedEffect
        runCatching {
            capture = bindCamera(context, lifecycleOwner) { request -> surfaceRequest = request }
        }.onFailure {
            onEvent(OcrCaptureEvent.CaptureFailed("The camera could not be started."))
        }
    }

    LfCard {
        Column(verticalArrangement = Arrangement.spacedBy(LfTheme.spacing.sm)) {
            when (state.cameraPermission) {
                CameraPermission.Unknown -> Text(
                    text = "Checking the camera…",
                    style = LfTheme.typography.bodyM,
                    color = LfTheme.colors.textSecondary,
                )

                CameraPermission.Denied -> Text(
                    // Not an error. Two of three inputs still work, and saying
                    // so is more useful than a permission lecture.
                    text = "No camera access. You can still import a photo or a PDF below.",
                    style = LfTheme.typography.bodyM,
                    color = LfTheme.colors.textSecondary,
                )

                CameraPermission.Granted -> CameraPane(
                    state = state,
                    surfaceRequest = surfaceRequest,
                    capture = capture,
                    onEvent = onEvent,
                )
            }
        }
    }
}

/**
 * The live preview and its shutter.
 *
 * Split out of [Viewfinder] rather than suppressed when it crossed detekt's
 * length threshold: the binding and the permission fork are one concern, and
 * what is drawn once permission exists is another.
 */
@Composable
private fun CameraPane(
    state: OcrCaptureUiState,
    surfaceRequest: SurfaceRequest?,
    capture: ImageCapture?,
    onEvent: (OcrCaptureEvent) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = LfTheme.spacing.xs),
        contentAlignment = Alignment.Center,
    ) {
        surfaceRequest?.let { request ->
            CameraXViewfinder(
                surfaceRequest = request,
                // **A DEFINITE height, and that is the whole point.**
                //
                // This screen is a Column with verticalScroll, so a child is
                // measured with maxHeight = Infinity. The viewfinder derives its
                // scale transform from the size it was measured at, and against
                // an unbounded dimension that transform comes out degenerate --
                // it magnifies a sliver of the texture across the whole view, so
                // the preview reads as one flat colour that changes with what
                // the camera points at. Reported exactly that way: brown near a
                // table, grey face down, "like a colour sensor, not a camera".
                //
                // aspectRatio derives the height from the width, so the
                // measurement is bounded whatever the parent does. 3:4 portrait
                // because a receipt is tall.
                //
                // The stream was never the problem. Logcat throughout:
                // `Preview: StreamSpec{resolution=1440x1080}` and
                // `Camera3-Device: Creating new stream 0: 1440 x 1080`.
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(RECEIPT_PREVIEW_ASPECT),
                // EMBEDDED (TextureView), not the default EXTERNAL (SurfaceView).
                //
                // **This screen scrolls, and a SurfaceView inside a scrolling
                // container renders black.** Its buffer is composited by the
                // system in window coordinates rather than drawn into the view
                // hierarchy, so it does not follow a scroll offset or a parent's
                // clip -- and the failure is silent, because the camera is
                // perfectly healthy underneath it.
                //
                // Diagnosed from logcat rather than guessed: with the preview
                // black on screen the device was logging
                // `[W_9_Preview] ... ServicePREVIEW2 ... 4080x3060 -> 1440x1080`
                // with the frame counter climbing at ~50fps. Frames were
                // flowing the whole time; only the compositing was wrong.
                //
                // EMBEDDED costs a copy per frame against EXTERNAL's zero-copy
                // path. For a viewfinder the user points at a receipt for a
                // second or two that is not a trade worth taking the bug for.
                implementationMode = ImplementationMode.EMBEDDED,
            )
            // Drawn over the preview, at the same measured size.
            LfCaptureGuide(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(RECEIPT_PREVIEW_ASPECT),
            )
        } ?: Text(
            text = "Starting the camera…",
            style = LfTheme.typography.bodyM,
            color = LfTheme.colors.textSecondary,
        )
    }

    if (surfaceRequest != null) CaptureHint()

    LfActionRow(alignment = LfActionAlignment.Start) {
        LfButton(
            text = if (state.reading) "Reading…" else "Capture",
            style = LfButtonStyle.Filled,
            enabled = state.canCapture && capture != null,
            onClick = {
                val handle = capture ?: return@LfButton
                scope.launch {
                    runCatching { handle.takeFrame(context) }.fold(
                        onSuccess = { frame ->
                            // Rotation is applied here rather than in
                            // ReceiptImageLoader: the sensor's orientation is a
                            // fact about THIS capture, and the loader deals in
                            // images that already know which way up they are.
                            onEvent(
                                OcrCaptureEvent.FrameCaptured(
                                    frame.bitmap.uprighted(frame.rotationDegrees),
                                ),
                            )
                        },
                        onFailure = {
                            onEvent(OcrCaptureEvent.CaptureFailed("That photo could not be taken."))
                        },
                    )
                }
            },
        )
    }
}

/**
 * What the guide is asking for, in words.
 *
 * Both halves earn their place. **"Fill the guide"** is about resolution: a
 * bill occupying a third of the frame gets a third of the pixels, and glyph
 * height is what decides whether a thermal line is readable at all.
 * **"Square to the bill"** is about skew, which the extractor can undo to
 * about 10° and no further.
 *
 * One sentence, because a viewfinder is not where anybody reads instructions.
 */
@Composable
private fun CaptureHint() {
    Text(
        text = "Fill the guide. Hold the phone square to the bill.",
        style = LfTheme.typography.label,
        color = LfTheme.colors.textSecondary,
    )
}

/** Turns a sensor frame the right way up. A no-op at 0°, which is the common case. */
private fun Bitmap.uprighted(degrees: Int): Bitmap {
    if (degrees == 0) return this
    val matrix = Matrix().apply { postRotate(degrees.toFloat()) }
    return Bitmap.createBitmap(this, 0, 0, width, height, matrix, true)
}

/**
 * What was read, and what §5.3's pipeline made of it.
 *
 * **The caption stays honest.** Steps 13 to 15 do not exist, so nothing here
 * has been saved and the card says so rather than letting a bill on screen
 * imply a candidate in the Inbox.
 *
 * The raw run preview is kept for the case where no bill was found, because
 * "90 runs and no items" and "the image was unreadable" are different reports
 * and the raw text is the only thing that tells them apart on a real device.
 */
@Composable
private fun ResultCard(
    summary: RecognitionSummary,
    canSave: Boolean,
    saved: Boolean,
    onEvent: (OcrCaptureEvent) -> Unit,
) {
    LfCard {
        Column(verticalArrangement = Arrangement.spacedBy(LfTheme.spacing.xs)) {
            Text(
                text = summary.merchant
                    ?: "${summary.elementCount} text runs · ${summary.sourceLabel}",
                style = LfTheme.typography.bodyL,
                color = LfTheme.colors.textPrimary,
            )
            Text(
                text = buildString {
                    if (summary.isBill) {
                        append("${summary.items.size} items")
                        summary.totalText?.let { append(" · $it") }
                        summary.balance?.let { append(" · $it") }
                        append(" · ")
                    }
                    append("${summary.elementCount} runs")
                    // **Only while it is still true.** Saying "nothing saved
                    // yet" underneath a card that reads "Saved to your Inbox"
                    // is the screen contradicting itself, and the user has no
                    // way to tell which half to believe. Caught on the device
                    // rather than in a preview, because a preview has no
                    // "after" state to render.
                    if (!saved) append(" · nothing saved yet")
                },
                style = LfTheme.typography.label,
                color = LfTheme.colors.textSecondary,
            )

            if (summary.isBill) {
                summary.items.forEach { item -> ExtractedItemLine(item) }
            } else {
                Text(
                    text = summary.rawPreview,
                    style = LfTheme.typography.bodyM,
                    color = LfTheme.colors.textSecondary,
                )
            }

            // Two actions, so LfActionRow rather than a Row (BUG9): the
            // container wraps whole controls at font scale 2.0 and the labels
            // never do. Inline, because these sit inside a card.
            LfActionRow(alignment = LfActionAlignment.End) {
                LfButton(
                    text = "Clear",
                    style = LfButtonStyle.Inline,
                    onClick = { onEvent(OcrCaptureEvent.Dismissed) },
                )
                if (summary.isBill && !saved) {
                    LfButton(
                        text = "Save to Inbox",
                        style = LfButtonStyle.Inline,
                        enabled = canSave,
                        onClick = { onEvent(OcrCaptureEvent.SaveRequested) },
                    )
                }
            }
        }
    }
}

/**
 * One extracted line: name on the left, amount on the right.
 *
 * The name takes the slack and wraps; the amount never does. At font scale 2.0
 * a long item name costs a second line, which is the BUG9 rule — degrade by
 * wrapping, never by clipping — and an amount that ellipsised would be the
 * one value on the row nobody can reconstruct.
 */
@Composable
private fun ExtractedItemLine(item: ExtractedItemRow) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(LfTheme.spacing.sm),
        verticalAlignment = Alignment.Top,
    ) {
        Text(
            text = item.name,
            modifier = Modifier.weight(1f),
            style = LfTheme.typography.bodyM,
            color = LfTheme.colors.textPrimary,
        )
        Text(
            text = item.amountText,
            style = LfTheme.typography.bodyM,
            color = LfTheme.colors.textSecondary,
        )
    }
}

@Composable
private fun SavedCard(message: String) {
    LfCard {
        Text(
            text = message,
            style = LfTheme.typography.bodyM,
            color = LfTheme.colors.textSecondary,
        )
    }
}

@Composable
private fun FailureCard(message: String) {
    LfCard {
        Text(
            text = message,
            style = LfTheme.typography.bodyM,
            color = LfTheme.colors.warn,
        )
    }
}

@Composable
private fun ImportRow(enabled: Boolean, onGallery: () -> Unit, onDocument: () -> Unit) {
    LfCard {
        Column(verticalArrangement = Arrangement.spacedBy(LfTheme.spacing.xs)) {
            Text(
                text = "Or import one",
                style = LfTheme.typography.label,
                color = LfTheme.colors.textSecondary,
                maxLines = 1,
                softWrap = false,
            )
            LfActionRow(alignment = LfActionAlignment.Start) {
                LfButton(
                    text = "Photo",
                    style = LfButtonStyle.Inline,
                    enabled = enabled,
                    onClick = onGallery,
                )
                LfButton(
                    text = "File or PDF",
                    style = LfButtonStyle.Inline,
                    enabled = enabled,
                    onClick = onDocument,
                )
            }
        }
    }
}

/** Portrait, because a receipt is taller than it is wide. */
private const val RECEIPT_PREVIEW_ASPECT = 3f / 4f

/** Images and PDFs. §5.3 names both as first-class inputs. */
private val IMPORT_MIME_TYPES = arrayOf("image/*", "application/pdf")

@PreviewScreenSizes
@PreviewFontScale
@PreviewLightDark
@Composable
private fun OcrCaptureDeniedPreview() {
    LfTheme {
        OcrCaptureScreen(
            state = OcrCaptureUiState(cameraPermission = CameraPermission.Denied),
            onEvent = {},
            onBack = {},
        )
    }
}

@PreviewScreenSizes
@PreviewFontScale
@PreviewLightDark
@Composable
private fun OcrCaptureResultPreview() {
    LfTheme {
        OcrCaptureScreen(
            state = OcrCaptureUiState(
                cameraPermission = CameraPermission.Denied,
                result = RecognitionSummary(
                    elementCount = 47,
                    rawPreview = "LOCAL KIRANA RICE 5KG 420.00 TOMATO 20.00 TOTAL 473.00",
                    sourceLabel = "Imported",
                    merchant = "LOCAL KIRANA",
                    totalText = "₹473.00",
                    items = listOf(
                        ExtractedItemRow("RICE 5KG", "₹420.00"),
                        // A long name, deliberately: this is the row that has
                        // to wrap rather than clip at font scale 2.0 (BUG9).
                        ExtractedItemRow("TOMATO LOCAL GRADE A 1KG", "₹20.00"),
                        ExtractedItemRow("TOOR DAL 1KG", "₹33.00"),
                    ),
                    balance = "Balanced",
                ),
            ),
            onEvent = {},
            onBack = {},
        )
    }
}
