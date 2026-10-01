package com.ledgerflow.feature.settings.diagnostics

import com.dropbox.differ.SimpleImageComparator
import com.github.takahirom.roborazzi.RoborazziOptions

/**
 * How this module's goldens are compared — **the third twin of
 * `:core:designsystem`'s `LfScreenshotOptions`** (`:core:ui` holds the second),
 * whose KDoc gives the reasoning and whose `LfScreenshotOptionsTest` holds the
 * behaviour. A copy only because a module's test sources cannot see another's;
 * change all three or none. P5 step 4 (goldens for every screen) is the point
 * to lift it into `:core:testing` rather than make a fourth.
 *
 * In short: goldens are recorded on Windows and verified on Linux, which moves
 * anti-aliased edge pixels by up to 4/255. [MAX_DISTANCE] tolerates 8/255 on R,
 * G and B at once, and one pixel past it still fails the golden.
 */
internal val LfScreenshotOptions: RoborazziOptions = RoborazziOptions(
    compareOptions = RoborazziOptions.CompareOptions(
        imageComparator = SimpleImageComparator(maxDistance = MAX_DISTANCE),
    ),
)

/** 8/255 on each of R, G and B together: `sqrt(3) * 8 / 255` ≈ 0.0543. */
internal const val MAX_DISTANCE: Float = 0.055f
