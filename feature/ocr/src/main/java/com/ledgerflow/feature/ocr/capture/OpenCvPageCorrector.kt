package com.ledgerflow.feature.ocr.capture

import android.graphics.Bitmap
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.roundToInt
import org.opencv.android.OpenCVLoader
import org.opencv.android.Utils
import org.opencv.core.Mat
import org.opencv.core.MatOfPoint
import org.opencv.core.MatOfPoint2f
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc

/**
 * Un-photographs a receipt: finds the page's four corners and warps it square
 * before recognition (ADR-0024).
 *
 * **The gap it closes.** Section B of `docs/OCR-PIPELINE.md` recorded
 * perspective as open: a page shot at an angle has rows that *converge*, and
 * `ReceiptGeometry`'s skew correction can only remove a single slope. Undoing
 * convergence needs the page's corners and a four-point warp — image
 * processing, which is what this dependency is for and the only thing it is for.
 *
 * **Refuse rather than guess.** Every step that fails — the native library not
 * loading, no four-cornered outline, an outline [PageQuad.isPlausible] rejects —
 * returns the input bitmap untouched. The rest of the pipeline already reads an
 * uncorrected photo; a wrong warp would make it read worse than doing nothing.
 *
 * Detection runs on a [DETECTION_EDGE] copy — edges and contours need structure,
 * not resolution — and the warp runs on the full-resolution input, so the text
 * the recogniser reads is not downsampled by the correction.
 */
@Singleton
public class OpenCvPageCorrector @Inject constructor() : ReceiptImageCorrector {

    private val ready: Boolean by lazy { runCatching { OpenCVLoader.initLocal() }.getOrDefault(false) }

    override fun correct(bitmap: Bitmap): Bitmap {
        if (!ready) return bitmap
        return runCatching { warpIfPage(bitmap) }.getOrNull() ?: bitmap
    }

    private fun warpIfPage(bitmap: Bitmap): Bitmap? {
        val scale = DETECTION_EDGE.toFloat() / maxOf(bitmap.width, bitmap.height)
        val small = Bitmap.createScaledBitmap(
            bitmap,
            (bitmap.width * scale).roundToInt().coerceAtLeast(1),
            (bitmap.height * scale).roundToInt().coerceAtLeast(1),
            true,
        )
        val corners = findPage(small) ?: return null
        val full = PageQuad.Corners(
            corners.topLeft.scaled(1f / scale),
            corners.topRight.scaled(1f / scale),
            corners.bottomRight.scaled(1f / scale),
            corners.bottomLeft.scaled(1f / scale),
        )
        return warp(bitmap, full)
    }

    private fun findPage(small: Bitmap): PageQuad.Corners? {
        val gray = Mat()
        val edges = Mat()
        val contours = mutableListOf<MatOfPoint>()
        try {
            val rgba = Mat().also { Utils.bitmapToMat(small, it) }
            Imgproc.cvtColor(rgba, gray, Imgproc.COLOR_RGBA2GRAY)
            rgba.release()
            Imgproc.GaussianBlur(gray, gray, Size(BLUR, BLUR), 0.0)
            Imgproc.Canny(gray, edges, CANNY_LOW, CANNY_HIGH)
            Imgproc.dilate(edges, edges, Mat.ones(DILATE, DILATE, org.opencv.core.CvType.CV_8U))
            Imgproc.findContours(edges, contours, Mat(), Imgproc.RETR_EXTERNAL, Imgproc.CHAIN_APPROX_SIMPLE)

            return contours
                .sortedByDescending { Imgproc.contourArea(it) }
                .take(CANDIDATES)
                .firstNotNullOfOrNull { contour -> quadOf(contour, small.width, small.height) }
        } finally {
            gray.release()
            edges.release()
            contours.forEach { it.release() }
        }
    }

    private fun quadOf(contour: MatOfPoint, width: Int, height: Int): PageQuad.Corners? {
        val curve = MatOfPoint2f().also { it.fromList(contour.toList()) }
        val approx = MatOfPoint2f()
        try {
            Imgproc.approxPolyDP(curve, approx, APPROX_EPSILON * Imgproc.arcLength(curve, true), true)
            val points = approx.toArray()
            if (points.size != CORNERS) return null
            val corners = PageQuad.order(points.map { PageQuad.Point(it.x.toFloat(), it.y.toFloat()) })
            return corners.takeIf { PageQuad.isPlausible(it, width, height) }
        } finally {
            curve.release()
            approx.release()
        }
    }

    private fun warp(bitmap: Bitmap, corners: PageQuad.Corners): Bitmap {
        val (width, height) = PageQuad.targetSize(corners)
        val source = MatOfPoint2f().also { mat ->
            mat.fromList(corners.all.map { org.opencv.core.Point(it.x.toDouble(), it.y.toDouble()) })
        }
        val target = MatOfPoint2f(
            org.opencv.core.Point(0.0, 0.0),
            org.opencv.core.Point(width - 1.0, 0.0),
            org.opencv.core.Point(width - 1.0, height - 1.0),
            org.opencv.core.Point(0.0, height - 1.0),
        )
        val input = Mat()
        val output = Mat()
        val transform = Imgproc.getPerspectiveTransform(source, target)
        try {
            Utils.bitmapToMat(bitmap, input)
            Imgproc.warpPerspective(input, output, transform, Size(width.toDouble(), height.toDouble()))
            return Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).also { Utils.matToBitmap(output, it) }
        } finally {
            listOf(source, target, input, output, transform).forEach { it.release() }
        }
    }

    private fun PageQuad.Point.scaled(factor: Float) = PageQuad.Point(x * factor, y * factor)

    private companion object {
        const val DETECTION_EDGE = 800
        const val BLUR = 5.0
        const val CANNY_LOW = 50.0
        const val CANNY_HIGH = 150.0
        const val DILATE = 3
        const val CANDIDATES = 5
        const val CORNERS = 4

        /** approxPolyDP tolerance as a share of the outline's perimeter. */
        const val APPROX_EPSILON = 0.02
    }
}
