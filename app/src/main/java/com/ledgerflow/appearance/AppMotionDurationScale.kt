package com.ledgerflow.appearance

import androidx.compose.ui.MotionDurationScale
import com.ledgerflow.core.domain.appearance.MotionLevel

/**
 * The scale every Compose animation in the app is played at (V2-PLAN §3.2).
 *
 * Compose normally makes one of these itself, from the phone's animator
 * duration scale, for the window's recomposer. `MainActivity` supplies this one
 * instead, so that Animations > Off is **exactly** what the phone's "Remove
 * animations" does -- every animation, Material's own included (a sheet, a
 * switch's thumb, a ripple), runs at zero duration -- rather than only the
 * transitions the app draws itself.
 *
 * Otherwise it is the phone's scale, unchanged: a developer-options 0.5x still
 * halves everything, and a removed-animations phone still gets none whatever
 * the app is set to ([MotionLevel] resolves that before it reaches here).
 *
 * Updated on resume, the moment the phone's setting can have changed, and when
 * the user changes the app's choice. An animation already running when it
 * changes finishes at the scale it started with; the next one picks it up.
 */
internal class AppMotionDurationScale : MotionDurationScale {

    @Volatile
    private var factor: Float = 1f

    override val scaleFactor: Float
        get() = factor

    fun update(level: MotionLevel, phoneScale: Float) {
        factor = scaleFor(level, phoneScale)
    }

    internal companion object {
        /** Off is zero; anything else plays at the phone's own scale. */
        internal fun scaleFor(level: MotionLevel, phoneScale: Float): Float = when (level) {
            MotionLevel.OFF -> 0f
            MotionLevel.FULL, MotionLevel.REDUCED -> phoneScale
        }
    }
}
