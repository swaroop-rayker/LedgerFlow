package com.ledgerflow.core.designsystem.component

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertWithMessage
import com.ledgerflow.core.designsystem.theme.LfContrast
import com.ledgerflow.core.designsystem.theme.LfTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * BUG64: a custom date range showed only its two ends.
 *
 * Found 2026-10-25 reviewing the first `LfDateRangePickerDialog` goldens
 * (design-system step C2): Analytics' custom range picked 10 and 19 August and
 * filled those two days, but nothing marked 11 to 18, so the picker did not
 * show what range it held. Material draws that band in `secondaryContainer`,
 * and step A2 had mapped `secondaryContainer` to `surface.overlay`, the
 * dialog's own colour: the band was drawn, in the colour of what it sat on.
 * Introduced by A2 on this branch; it never reached a release.
 *
 * The band now reads `action.subtle`. Held to two things in both themes: it is
 * as distinct from the dialog as Material's own band was over its surface
 * (about 1.3:1, so at least [BAND_MIN]), and a day drawn on it stays readable
 * text (4.5:1).
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class Bug64_RangePickerShowsDaysBetweenTest {

    @get:Rule
    val rule = createComposeRule()

    @Test
    fun theBandStandsOutFromTheDialog_dark() = assertBandVisible(dark = true)

    @Test
    fun theBandStandsOutFromTheDialog_light() = assertBandVisible(dark = false)

    /** The cause, pinned: the colour Material would use is the dialog's own. */
    @Test
    fun materialsDefaultBandIsTheDialogColour() {
        var band = Color.Unspecified
        var dialog = Color.Unspecified
        rule.setContent {
            LfTheme(darkTheme = true) {
                band = MaterialTheme.colorScheme.secondaryContainer
                dialog = LfDatePickerTokens.container
            }
        }
        rule.waitForIdle()
        assertWithMessage("if this changes, BUG64's override may no longer be needed")
            .that(band)
            .isEqualTo(dialog)
    }

    private fun assertBandVisible(dark: Boolean) {
        var band = Color.Unspecified
        var dialog = Color.Unspecified
        var day = Color.Unspecified
        rule.setContent {
            LfTheme(darkTheme = dark) {
                band = LfDatePickerTokens.rangeFill
                dialog = LfDatePickerTokens.container
                day = LfDatePickerTokens.rangeDay
            }
        }
        rule.waitForIdle()
        val theme = if (dark) "dark" else "light"
        assertWithMessage("$theme: range band against the dialog")
            .that(LfContrast.ratio(band, dialog))
            .isAtLeast(BAND_MIN)
        assertWithMessage("$theme: a day inside the range, on the band")
            .that(LfContrast.ratio(day, band))
            .isAtLeast(LfContrast.AA_NORMAL)
    }
}

/** Material's own band over its own surface measures about 1.3:1; below 1.2 it stops reading as a band. */
private const val BAND_MIN = 1.2
