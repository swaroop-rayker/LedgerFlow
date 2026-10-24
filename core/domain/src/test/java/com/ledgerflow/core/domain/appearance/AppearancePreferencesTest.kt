package com.ledgerflow.core.domain.appearance

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** How each Appearance choice resolves against the phone's own settings (V2-PLAN §3.2). */
class AppearancePreferencesTest {

    @Test
    fun theme_systemFollowsThePhone_lightAndDarkOverrideIt() {
        assertThat(ThemePreference.SYSTEM.isDark(phoneIsDark = true)).isTrue()
        assertThat(ThemePreference.SYSTEM.isDark(phoneIsDark = false)).isFalse()
        listOf(true, false).forEach { phone ->
            assertThat(ThemePreference.LIGHT.isDark(phone)).isFalse()
            assertThat(ThemePreference.DARK.isDark(phone)).isTrue()
        }
    }

    @Test
    fun animations_mapOneToOne_whileThePhoneAnimates() {
        assertThat(AnimationPreference.FULL.motionLevel(phoneAnimationsOn = true)).isEqualTo(MotionLevel.FULL)
        assertThat(AnimationPreference.REDUCED.motionLevel(phoneAnimationsOn = true)).isEqualTo(MotionLevel.REDUCED)
        assertThat(AnimationPreference.OFF.motionLevel(phoneAnimationsOn = true)).isEqualTo(MotionLevel.OFF)
    }

    @Test
    fun thePhonesRemoveAnimations_alwaysWins() {
        // An accessibility setting (SPEC §9.5): no app choice may put motion
        // back on a phone that has removed it.
        AnimationPreference.entries.forEach { choice ->
            assertThat(choice.motionLevel(phoneAnimationsOn = false)).isEqualTo(MotionLevel.OFF)
        }
    }

    @Test
    fun defaults_areWhatTheAppDidBefore() {
        assertThat(AppearancePreferences()).isEqualTo(
            AppearancePreferences(ThemePreference.SYSTEM, AnimationPreference.FULL, StartScreen.HOME),
        )
    }
}
