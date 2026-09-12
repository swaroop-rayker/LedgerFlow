package com.ledgerflow.core.designsystem.component

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import com.ledgerflow.core.designsystem.theme.LfDarkColors

/**
 * A receipt-shaped framing guide, drawn over a camera viewfinder (SPEC.md §5.3).
 *
 * ## Why it is in `:core:designsystem`
 *
 * `CLAUDE.md`'s charts rule, applied to the same kind of object: hand-rolled
 * `Lf*` Canvas primitives live here "because that is where the Roborazzi
 * harness lives and where a chart therefore inherits the fontScale-2.0 gate
 * mechanically rather than by inspection". This is a Canvas primitive with a
 * light/dark failure mode, so it belongs where the screenshot gate can see it.
 *
 * That is not theoretical. Written inside `:feature:ocr` it shipped two
 * theme bugs at once, and only one was visible on the device being tested:
 * a scrim built from the *active* palette **lightens** the surround in light
 * theme (`surfaceBase` is `#F7F8FA`), and corner marks built from `onAccent`
 * come out near-black on a dark preview in dark theme (`#0B1020`) — invisible
 * exactly where they are needed.
 *
 * ## The colours are the dark palette's, whatever theme is active
 *
 * This paints over a **camera image**, and a camera image has no theme. So it
 * takes the dark surface's colours in both — still tokens rather than
 * literals (§9.1), because the viewfinder simply *is* a dark surface.
 *
 * ## Why it guides rather than gates
 *
 * Two of the extractor's limits are properties of the photograph, and both are
 * cheaper to prevent than to undo. **Skew:** `ReceiptGeometry` recovers page
 * rotation from the recognised boxes to about 10°, measured; past that no pair
 * of runs on one line still overlaps and the estimate honestly gives up.
 * Undoing more needs a four-point warp, which needs the page's corners, which
 * needs image processing this app has avoided — OpenCV is megabytes and ML
 * Kit's document scanner requires Play Services, the disqualification that
 * already ruled out unbundled OCR (ADR-0021). **Resolution:** a bill occupying
 * a third of the frame gets a third of the pixels, and glyph height decides
 * whether a thermal line is readable at all.
 *
 * It deliberately does **not** gate the shutter. Judging "is the receipt
 * inside the frame" needs a second `ImageAnalysis` stream and a detector, and
 * a shutter that refuses without being able to say precisely why is worse than
 * a guide the user can ignore.
 */
@Composable
public fun LfCaptureGuide(modifier: Modifier = Modifier) {
    val marks = LfDarkColors.textPrimary
    val scrim = LfDarkColors.surfaceBase.copy(alpha = SCRIM_ALPHA)

    Canvas(modifier = modifier) {
        val guideWidth = size.width * WIDTH_FRACTION
        val guideHeight = size.height * HEIGHT_FRACTION
        val left = (size.width - guideWidth) / 2f
        val top = (size.height - guideHeight) / 2f
        val right = left + guideWidth
        val bottom = top + guideHeight

        // Four bands rather than a punched-out path: `clipPath` costs a layer
        // every frame on a preview that redraws continuously, and four
        // rectangles do not.
        drawRect(scrim, size = Size(size.width, top))
        drawRect(scrim, topLeft = Offset(0f, bottom), size = Size(size.width, size.height - bottom))
        drawRect(scrim, topLeft = Offset(0f, top), size = Size(left, guideHeight))
        drawRect(scrim, topLeft = Offset(right, top), size = Size(size.width - right, guideHeight))

        // Corner marks, not a closed rectangle: an unbroken outline reads as a
        // crop boundary the photograph will be cut to, and it will not be —
        // the whole frame is still captured and stored.
        val arm = minOf(guideWidth, guideHeight) * CORNER_FRACTION
        listOf(
            Offset(left, top) to listOf(Offset(left + arm, top), Offset(left, top + arm)),
            Offset(right, top) to listOf(Offset(right - arm, top), Offset(right, top + arm)),
            Offset(left, bottom) to listOf(Offset(left + arm, bottom), Offset(left, bottom - arm)),
            Offset(right, bottom) to
                listOf(Offset(right - arm, bottom), Offset(right, bottom - arm)),
        ).forEach { (corner, arms) ->
            arms.forEach { end -> drawLine(marks, corner, end, strokeWidth = STROKE_PX) }
        }
    }
}

/** A till roll is narrow; the guide says so rather than echoing the preview. */
private const val WIDTH_FRACTION = 0.58f
private const val HEIGHT_FRACTION = 0.92f

/** How far each corner mark reaches along its two edges. */
private const val CORNER_FRACTION = 0.12f

/** Enough to read the guide against a bright bill, not enough to hide the scene. */
private const val SCRIM_ALPHA = 0.45f

/** In pixels: this is a `Canvas`, so a `dp` would need the density. */
private const val STROKE_PX = 6f
