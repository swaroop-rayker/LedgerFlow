package com.ledgerflow.core.testing.screenshot

import com.dropbox.differ.SimpleImageComparator
import com.github.takahirom.roborazzi.RoborazziOptions

/**
 * How every golden in the project is compared (SPEC.md §8 BUG33). **The one
 * copy**: `:core:designsystem`, `:core:ui` and each feature module read it from
 * here, where until P5 step 4 three modules each kept a twin because a
 * module's test sources cannot see another's. `LfScreenshotOptionsTest`
 * (in this module) holds the behaviour.
 *
 * Goldens are recorded on Windows and verified on Linux CI, whose font
 * rendering moves anti-aliased glyph edges by up to 4/255 per channel (BUG33,
 * measured on run 35858631887). Roborazzi's default comparator,
 * `maxDistance = 0.007` on channels scaled to 0..1, fails a 2/255 change, so an
 * exact comparison cannot pass there.
 *
 * [MAX_DISTANCE] allows 8/255 on R, G and B **at once** — twice the worst
 * noise seen — and nothing more. Roborazzi's default *validator* is kept: a
 * single pixel past the tolerance still fails the golden, so a one-pixel
 * layout shift or a palette change (#3B5BDB to #4263EB) is caught. **Never
 * raise this to make a diff pass**; a real change is a distance near 1.
 */
public val LfScreenshotOptions: RoborazziOptions = RoborazziOptions(
    compareOptions = RoborazziOptions.CompareOptions(
        imageComparator = SimpleImageComparator(maxDistance = MAX_DISTANCE),
    ),
)

/** 8/255 on each of R, G and B together: `sqrt(3) * 8 / 255` ≈ 0.0543. */
public const val MAX_DISTANCE: Float = 0.055f
