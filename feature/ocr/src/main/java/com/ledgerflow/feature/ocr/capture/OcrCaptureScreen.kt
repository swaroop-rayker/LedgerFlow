package com.ledgerflow.feature.ocr.capture

import android.Manifest
import android.graphics.Bitmap
import android.graphics.Matrix
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.compose.CameraXViewfinder
import androidx.camera.core.ImageCapture
import androidx.camera.core.SurfaceRequest
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
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

            state.result?.let { ResultCard(it, onEvent) }
            state.failure?.let { FailureCard(it) }

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
            CameraXViewfinder(surfaceRequest = request, modifier = Modifier.fillMaxWidth())
        } ?: Text(
            text = "Starting the camera…",
            style = LfTheme.typography.bodyM,
            color = LfTheme.colors.textSecondary,
        )
    }

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

/** Turns a sensor frame the right way up. A no-op at 0°, which is the common case. */
private fun Bitmap.uprighted(degrees: Int): Bitmap {
    if (degrees == 0) return this
    val matrix = Matrix().apply { postRotate(degrees.toFloat()) }
    return Bitmap.createBitmap(this, 0, 0, width, height, matrix, true)
}

@Composable
private fun ResultCard(summary: RecognitionSummary, onEvent: (OcrCaptureEvent) -> Unit) {
    LfCard {
        Column(verticalArrangement = Arrangement.spacedBy(LfTheme.spacing.xs)) {
            Text(
                text = "${summary.elementCount} text runs · ${summary.sourceLabel}",
                style = LfTheme.typography.bodyL,
                color = LfTheme.colors.textPrimary,
            )
            Text(
                // The honest caption. Nothing has been parsed into a bill yet.
                text = "Read on this device. Line items are not extracted yet.",
                style = LfTheme.typography.label,
                color = LfTheme.colors.textSecondary,
            )
            Text(
                text = summary.preview,
                style = LfTheme.typography.bodyM,
                color = LfTheme.colors.textSecondary,
            )
            LfActionRow(alignment = LfActionAlignment.End) {
                LfButton(
                    text = "Clear",
                    style = LfButtonStyle.Inline,
                    onClick = { onEvent(OcrCaptureEvent.Dismissed) },
                )
            }
        }
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
                    preview = "LOCAL KIRANA RICE 5KG 420.00 TOMATO 20.00 TOTAL 473.00",
                    sourceLabel = "Imported",
                ),
            ),
            onEvent = {},
            onBack = {},
        )
    }
}
