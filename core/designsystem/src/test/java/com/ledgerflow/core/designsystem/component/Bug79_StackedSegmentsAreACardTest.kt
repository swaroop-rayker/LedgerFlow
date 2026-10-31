package com.ledgerflow.core.designsystem.component

import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.ledgerflow.core.designsystem.theme.LfTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * BUG79, found by the owner on the device (2026-10-30): when a segmented
 * control stacks its options (Lock after, Date order, every one at font scale
 * 2.0), its track kept the one-row pill's fully round corners, and a track
 * four options tall drew a capsule with ends half its width across. Stacked,
 * the track takes the control's corners and each option the tile's, so they
 * nest across the track's padding; one row stays pill inside pill. The
 * goldens (`settings-lock-after-*`, Diagnostics and Export at 2x) show it.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34])
class Bug79_StackedSegmentsAreACardTest {

    @get:Rule
    val rule = createComposeRule()

    @Test
    fun aStackedTrack_isTheControlsShape_andItsOptionsTheTiles() {
        lateinit var track: Shape
        lateinit var option: Shape
        lateinit var row: Shape
        lateinit var control: Shape
        lateinit var tile: Shape
        lateinit var full: Shape
        rule.setContent {
            LfTheme {
                track = LfSegmentedTokens.stackedTrackShape
                option = LfSegmentedTokens.stackedSegmentShape
                row = LfSegmentedTokens.trackShape
                control = LfTheme.shapes.control
                tile = LfTheme.shapes.tile
                full = LfTheme.shapes.full
            }
        }
        assertThat(track).isEqualTo(control)
        assertThat(option).isEqualTo(tile)
        assertThat(row).isEqualTo(full)
    }
}
