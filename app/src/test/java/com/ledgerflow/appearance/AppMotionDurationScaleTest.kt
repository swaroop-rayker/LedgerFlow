package com.ledgerflow.appearance

import com.google.common.truth.Truth.assertThat
import com.ledgerflow.core.domain.appearance.MotionLevel
import org.junit.Test

/**
 * The scale every Compose animation plays at (V2-PLAN §3.2).
 *
 * Off must be zero -- what the phone's "Remove animations" does -- and every
 * other level must leave the phone's own scale alone, developer-options 0.5x
 * included.
 */
class AppMotionDurationScaleTest {

    @Test
    fun off_isZero_whateverThePhoneSays() {
        listOf(1f, 0.5f, 2f).forEach { phone ->
            assertThat(AppMotionDurationScale.scaleFor(MotionLevel.OFF, phone)).isEqualTo(0f)
        }
    }

    @Test
    fun fullAndReduced_playAtThePhonesScale() {
        listOf(1f, 0.5f, 2f, 0f).forEach { phone ->
            assertThat(AppMotionDurationScale.scaleFor(MotionLevel.FULL, phone)).isEqualTo(phone)
            assertThat(AppMotionDurationScale.scaleFor(MotionLevel.REDUCED, phone)).isEqualTo(phone)
        }
    }

    @Test
    fun update_isWhatAnimationsRead() {
        val scale = AppMotionDurationScale()
        assertThat(scale.scaleFactor).isEqualTo(1f)
        scale.update(MotionLevel.OFF, phoneScale = 1f)
        assertThat(scale.scaleFactor).isEqualTo(0f)
        scale.update(MotionLevel.FULL, phoneScale = 0.5f)
        assertThat(scale.scaleFactor).isEqualTo(0.5f)
    }
}
