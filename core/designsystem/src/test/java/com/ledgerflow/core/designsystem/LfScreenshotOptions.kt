package com.ledgerflow.core.designsystem

import com.dropbox.differ.SimpleImageComparator
import com.github.takahirom.roborazzi.RoborazziOptions

/**
 * How a golden is compared: the same everywhere in this module, and in
 * `:core:ui`'s twin of this file (a module's test sources cannot see another's).
 *
 * **Why not exact.** The goldens are recorded on Windows and verified on a Linux
 * runner, and the two rasterise text differently at the anti-aliasing edge. The
 * first CI run that got as far as screenshots (2026-09-23) failed 17 of 30
 * goldens on nothing else: every image the same size, at most 0.14% of pixels
 * changed, **no channel moved by more than 4/255**. Roborazzi's default
 * comparator (`maxDistance = 0.007`) fails a 2/255 change, so exact comparison
 * meant CI could never be green.
 *
 * **What the tolerance is.** Differ's distance is the Euclidean distance between
 * two pixels' RGBA, each channel scaled to 0–1 (Roborazzi divides by 255 before
 * comparing — read from `DifferBufferedImage`, not assumed). [MAX_DISTANCE] is
 * 8/255 on R, G and B at once, twice the worst change seen. The result
 * validator is left at Roborazzi's default, `ThresholdValidator(0f)`: one pixel
 * past the tolerance still fails the golden.
 *
 * **What it cannot hide.** A layout shift, a clipped label (BUG9) or a wrapped
 * word moves pixels between background and ink, which is a distance near 1. A
 * palette token changing is typically 0.07 or more. `LfScreenshotOptionsTest`
 * holds both, and holds the CI noise passing, against these exact options.
 */
internal val LfScreenshotOptions: RoborazziOptions = RoborazziOptions(
    compareOptions = RoborazziOptions.CompareOptions(
        imageComparator = SimpleImageComparator(maxDistance = MAX_DISTANCE),
    ),
)

/** 8/255 on each of R, G and B together: `sqrt(3) * 8 / 255` ≈ 0.0543. */
internal const val MAX_DISTANCE: Float = 0.055f
