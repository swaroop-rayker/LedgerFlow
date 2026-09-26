package com.ledgerflow.core.ui.phrase

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.compose.CameraXViewfinder
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.SurfaceRequest
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.core.content.ContextCompat
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.MultiFormatReader
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.ledgerflow.core.designsystem.component.LfButton
import com.ledgerflow.core.designsystem.component.LfButtonStyle
import com.ledgerflow.core.designsystem.component.LfCard
import com.ledgerflow.core.designsystem.theme.LfTheme
import java.util.concurrent.Executors
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine

/**
 * Reads a Recovery Kit's QR code instead of typing 24 words (ADR-0028).
 *
 * **Typing is never taken away.** This is an alternative route into the same
 * field, offered beside it; a camera that will not focus, a kit that was only
 * saved as text, and a screen reader all need the words, and §7.4's rule that a
 * gate's only way forward may not be a secondary action applies here too.
 *
 * **Nothing is kept.** No frame reaches disk, nothing decoded is logged, and
 * the analyser reads the in-memory luminance plane the camera hands it. The
 * scanner stops at the first LedgerFlow code, so the camera is live for as long
 * as it takes to point it at a page.
 */
@Composable
public fun LfPhraseScanner(
    onScanned: (String) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    var granted by remember { mutableStateOf(context.hasCameraPermission()) }
    val request = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        granted = it
    }

    LaunchedEffect(Unit) {
        if (!granted) request.launch(Manifest.permission.CAMERA)
    }

    // The viewfinder is full-bleed; the controls are not. This screen replaces
    // the host's LfScaffold, so nothing else insets it, and without this the
    // one way out sat under the navigation bar (§8 BUG35). systemBars plus the
    // cutout, never safeDrawing -- CLAUDE.md §5; there is no keyboard here.
    val insets = WindowInsets.systemBars.union(WindowInsets.displayCutout)
    Box(modifier = modifier.fillMaxSize()) {
        if (granted) ScannerViewfinder(onScanned)
        Column(
            modifier = Modifier
                .fillMaxSize()
                .windowInsetsPadding(insets)
                .padding(LfTheme.spacing.sm),
            verticalArrangement = Arrangement.Bottom,
        ) {
            ScannerControls(granted = granted, onDismiss = onDismiss)
        }
    }
}

/**
 * The card over the viewfinder: what to do, and the way back to typing.
 *
 * Its own composable so the screenshot harness can hold it at font scale 1.0
 * and 2.0 without a camera (`LfPhraseScannerScreenshotTest`) — the first
 * version drew the button over the instruction, because [LfCard] stacks its
 * children and nothing laid them out in a column.
 */
@Composable
internal fun ScannerControls(granted: Boolean, onDismiss: () -> Unit, modifier: Modifier = Modifier) {
    LfCard(modifier = modifier.fillMaxWidth()) {
        Column(verticalArrangement = Arrangement.spacedBy(LfTheme.spacing.sm)) {
            Text(
                text = if (granted) {
                    "Point the camera at the QR code on your Recovery Kit PDF."
                } else {
                    "Scanning needs the camera. Without it, type the 24 words — it is the " +
                        "same phrase either way."
                },
                style = LfTheme.typography.bodyM,
                color = LfTheme.colors.textPrimary,
            )
            LfButton(
                text = "Type the words",
                onClick = onDismiss,
                modifier = Modifier.fillMaxWidth(),
                style = LfButtonStyle.Tonal,
            )
        }
    }
}

/**
 * The camera itself, bound to this composition's lifecycle.
 *
 * `onScanned` is held through [rememberUpdatedState] so a recomposition cannot
 * strand the analyser calling yesterday's callback, and the analyser runs on a
 * single background thread that is shut down with the composition.
 */
@Composable
private fun ScannerViewfinder(onScanned: (String) -> Unit) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var surfaceRequest by remember { mutableStateOf<SurfaceRequest?>(null) }
    val currentOnScanned by rememberUpdatedState(onScanned)
    val deliveries = remember { ScanDeliveries() }

    LaunchedEffect(Unit) {
        val executor = Executors.newSingleThreadExecutor()
        val mainExecutor = ContextCompat.getMainExecutor(context)
        val provider = runCatching { awaitCameraProvider(context) }.getOrNull() ?: return@LaunchedEffect
        val preview = Preview.Builder().build().apply {
            setSurfaceProvider { request -> surfaceRequest = request }
        }
        val analysis = ImageAnalysis.Builder()
            // The latest frame is the one the user is pointing at; a backlog of
            // stale frames would decode a page that has already moved.
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .build()
            .apply {
                setAnalyzer(executor) { image ->
                    val text = image.decodeQr()
                    // Every distinct code once (BUG34), handed over on the main
                    // thread: the screen's ViewModel is not the analyser's.
                    if (text != null && deliveries.shouldDeliver(text)) {
                        mainExecutor.execute { currentOnScanned(text) }
                    }
                }
            }
        runCatching {
            provider.unbindAll()
            provider.bindToLifecycle(lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis)
        }
    }

    surfaceRequest?.let { CameraXViewfinder(surfaceRequest = it, modifier = Modifier.fillMaxSize()) }
}

/** One frame, decoded in memory. Never written anywhere, never logged. */
private fun ImageProxy.decodeQr(): String? = try {
    // Everything inside the guard, the source's constructor included: an
    // exception here would otherwise escape on the analyser thread. (A padded
    // plane with a short last row is fine as-is -- ZXing reads each row only to
    // the width -- which ScanFramesTest pins.)
    runCatching {
        val plane = planes[0]
        val bytes = ByteArray(plane.buffer.remaining()).also { plane.buffer.get(it) }
        val source = PlanarYUVLuminanceSource(
            bytes,
            plane.rowStride,
            height,
            0,
            0,
            width,
            height,
            false,
        )
        qrReader.decodeWithState(BinaryBitmap(HybridBinarizer(source))).text
    }.getOrNull()
} finally {
    close()
}

/** QR only: the kit writes one, and the other formats are noise a camera would chase. */
private val qrReader = MultiFormatReader().apply {
    setHints(mapOf(DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE)))
}

private fun Context.hasCameraPermission(): Boolean =
    ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED

private suspend fun awaitCameraProvider(context: Context): ProcessCameraProvider =
    suspendCancellableCoroutine { continuation ->
        val future = ProcessCameraProvider.getInstance(context)
        future.addListener(
            {
                runCatching { future.get() }.fold(
                    onSuccess = { continuation.resume(it) },
                    onFailure = { continuation.resumeWithException(it) },
                )
            },
            ContextCompat.getMainExecutor(context),
        )
        continuation.invokeOnCancellation { future.cancel(false) }
    }
