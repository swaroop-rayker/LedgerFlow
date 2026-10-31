package com.ledgerflow.core.designsystem.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * BUG63: a switch in the off position all but vanished in the dark theme.
 *
 * Found 2026-10-25 by the first dark goldens (DESIGN_SYSTEM_PLAN step A1) on
 * Settings' App lock and Block screenshots rows and Payment apps' Paytm row. An
 * unchecked Material `Switch` draws its thumb and border in the colour scheme's
 * `outline` and its track in `surfaceContainerHighest`. `LfTheme` mapped
 * `outline` to the hairline (#333846) and left the track at Material's own
 * #36343B: a dark grey thumb on a dark grey track, about 1.1:1.
 *
 * The off state is the state a user has to find to turn something on, so it
 * is held to WCAG's 3:1 for the parts of a control (1.4.11): thumb against
 * track, and the control's edge against the row it sits on. Red before A2's
 * complete mapping, green after.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class Bug63_OffSwitchIsVisibleTest {

    @get:Rule
    val rule = createComposeRule()

    @Test
    fun theOffThumbStandsOutFromItsTrack_dark() = assertOffSwitchVisible("dark", LfDarkColorScheme, LfDarkColors)

    @Test
    fun theOffThumbStandsOutFromItsTrack_light() = assertOffSwitchVisible("light", LfLightColorScheme, LfLightColors)

    private fun assertOffSwitchVisible(theme: String, scheme: ColorScheme, tokens: LfColors) {
        assertWithMessage("$theme: off thumb against its track")
            .that(LfContrast.ratio(scheme.outline, scheme.surfaceContainerHighest))
            .isAtLeast(LfContrast.AA_LARGE)
        // LfSwitchRow sits on an LfCard, which is surfaceRaised.
        assertWithMessage("$theme: the switch's edge against the row")
            .that(LfContrast.ratio(scheme.outline, tokens.surface.raised))
            .isAtLeast(LfContrast.AA_LARGE)
    }

    /** The colours above are the ones a real `Switch` gets: LfTheme provides this scheme. */
    @Test
    fun lfThemeProvidesTheMappedScheme() {
        var dark: ColorScheme? = null
        var light: ColorScheme? = null
        rule.setContent {
            LfTheme(darkTheme = true) { dark = MaterialTheme.colorScheme }
            LfTheme(darkTheme = false) { light = MaterialTheme.colorScheme }
        }
        rule.waitForIdle()
        assertThat(dark).isSameInstanceAs(LfDarkColorScheme)
        assertThat(light).isSameInstanceAs(LfLightColorScheme)
    }
}
