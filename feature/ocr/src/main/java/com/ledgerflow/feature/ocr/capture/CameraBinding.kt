package com.ledgerflow.feature.ocr.capture

import android.content.Context
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.SurfaceRequest
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine

/**
 * The CameraX plumbing, kept in one file so the screen stays declarative.
 *
 * ## Why the `SurfaceRequest` is hoisted rather than remembered
 *
 * `CameraXViewfinder` renders whatever `SurfaceRequest` the `Preview` use case
 * hands out, and that request is replaced whenever the camera rebinds. Holding
 * it in a `remember` inside the screen would tie a piece of live camera state
 * to a composition that a rotation throws away — the viewfinder would go black
 * until something else triggered a rebind. It is state, so it hoists
 * (`CLAUDE.md` §5).
 *
 * ## No new dependency for one future
 *
 * `ProcessCameraProvider.getInstance` returns a `ListenableFuture` and CameraX
 * ships no `suspend` accessor. `androidx.concurrent:concurrent-futures-ktx`
 * exists to bridge exactly this and is not worth a §10 dependency proposal for
 * eight lines, so [awaitCameraProvider] wraps it directly — the same reasoning
 * ADR-0010 applied to HKDF.
 */
internal suspend fun awaitCameraProvider(context: Context): ProcessCameraProvider =
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
        // A cancelled composition must not leave the listener holding a
        // continuation that can never resume.
        continuation.invokeOnCancellation { future.cancel(false) }
    }

/**
 * Binds preview + capture to [lifecycleOwner] and returns the capture handle.
 *
 * `unbindAll` first, deliberately: rebinding without it throws once the same
 * use cases are already attached, and a rebind is the ordinary consequence of
 * a rotation.
 */
internal suspend fun bindCamera(
    context: Context,
    lifecycleOwner: LifecycleOwner,
    onSurfaceRequest: (SurfaceRequest) -> Unit,
): ImageCapture {
    val provider = awaitCameraProvider(context)

    val preview = Preview.Builder().build().apply {
        setSurfaceProvider { request -> onSurfaceRequest(request) }
    }
    val capture = ImageCapture.Builder()
        // A receipt is read for its text, not looked at. Minimising latency
        // over quality keeps the shutter responsive on the §11 budget, and the
        // recogniser is fed a downscaled frame either way.
        .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
        .build()

    provider.unbindAll()
    provider.bindToLifecycle(
        lifecycleOwner,
        CameraSelector.DEFAULT_BACK_CAMERA,
        preview,
        capture,
    )
    return capture
}

/**
 * Takes one frame, as a suspend call.
 *
 * **The `ImageProxy` is closed in every path.** It holds a buffer from a fixed
 * pool, and leaking one stalls the camera after a handful of shots — a bug that
 * looks like the shutter simply stopping rather than like a leak.
 *
 * `toBitmap()` gives a bitmap in the sensor's orientation; the rotation degrees
 * come back separately and the caller applies them, because rotating here would
 * mean this function knew what the image was for.
 */
internal suspend fun ImageCapture.takeFrame(context: Context): CapturedFrame =
    suspendCancellableCoroutine { continuation ->
        takePicture(
            ContextCompat.getMainExecutor(context),
            object : ImageCapture.OnImageCapturedCallback() {
                override fun onCaptureSuccess(image: ImageProxy) {
                    val frame = runCatching {
                        CapturedFrame(image.toBitmap(), image.imageInfo.rotationDegrees)
                    }
                    image.close()
                    frame.fold(
                        onSuccess = { continuation.resume(it) },
                        onFailure = { continuation.resumeWithException(it) },
                    )
                }

                override fun onError(exception: ImageCaptureException) {
                    continuation.resumeWithException(exception)
                }
            },
        )
    }

/** One frame, plus how far it needs turning to be upright. */
internal data class CapturedFrame(
    val bitmap: android.graphics.Bitmap,
    val rotationDegrees: Int,
)
