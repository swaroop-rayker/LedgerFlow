package com.ledgerflow.core.designsystem.component

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.LayoutDirection
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.ledgerflow.core.designsystem.theme.LfTheme
import com.ledgerflow.core.testing.screenshot.DarkGoldensRule
import com.ledgerflow.core.testing.screenshot.GOLDEN_SDK
import com.ledgerflow.core.testing.screenshot.PHONE_1X
import com.ledgerflow.core.testing.screenshot.PHONE_2X
import com.ledgerflow.core.testing.screenshot.captureScreenGolden
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The filter bar (step F5): a period pill and a filter control on one line.
 *
 * Goldens show it with no filters and with a custom range and two filters, at
 * 1x, 2x (where the filter control may move down a line whole, never clip)
 * and in RTL. The tap tests are what a golden cannot show: each pill is one
 * control a screen reader names with what it does.
 *
 * **Review the diff; never re-record blind** (`CLAUDE.md` §12).
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [GOLDEN_SDK], qualifiers = PHONE_1X)
open class LfFilterBarTest {

    @get:Rule
    val rule = createComposeRule()

    @Composable
    private fun Screen(direction: LayoutDirection = LayoutDirection.Ltr) {
        CompositionLocalProvider(LocalLayoutDirection provides direction) {
            LfTheme {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(LfTheme.colors.surface.canvas)
                        .padding(LfTheme.space.screenEdge),
                    verticalArrangement = Arrangement.spacedBy(LfTheme.space.sectionGap),
                ) {
                    LfFilterBar(period = "Month", onPeriod = {}, activeFilters = 0, onFilters = {})
                    LfFilterBar(period = CUSTOM, onPeriod = {}, activeFilters = 2, onFilters = {})
                }
            }
        }
    }

    @Test fun bar_1x() = rule.captureScreenGolden("filter-bar-1x", 1f) { Screen() }

    @Config(qualifiers = PHONE_2X)
    @Test fun bar_2x() = rule.captureScreenGolden("filter-bar-2x", 2f) { Screen() }

    @Test fun barRtl_1x() = rule.captureScreenGolden("filter-bar-rtl-1x", 1f) { Screen(LayoutDirection.Rtl) }

    /** Each pill is one button, named with what it is and what tapping does. */
    @Test
    fun eachPill_isOneNamedButton() {
        var period = 0
        var filters = 0
        rule.setContent {
            LfTheme {
                LfFilterBar(period = "Month", onPeriod = { period++ }, activeFilters = 2, onFilters = { filters++ })
            }
        }
        rule.onNodeWithContentDescription("Period: Month. Changes the period.").performClick()
        rule.onNodeWithContentDescription("Filters, 2 on").performClick()
        assertThat(period).isEqualTo(1)
        assertThat(filters).isEqualTo(1)
    }

    /** With none on, the control still says so, rather than reading as an unlabelled icon. */
    @Test
    fun noFilters_isSaid() {
        rule.setContent {
            LfTheme { LfFilterBar(period = "Week", onPeriod = {}, activeFilters = 0, onFilters = {}) }
        }
        rule.onNodeWithContentDescription("Filters, none on").assertExists()
    }

    private companion object {
        /** An en dash as a code point (CLAUDE.md 5). */
        val CUSTOM: String = "3 Aug " + Char(0x2013) + " 19 Aug"
    }
}

/** The same goldens with the phone in dark mode, saved with a `-dark` suffix (DESIGN_SYSTEM_PLAN §6.3). */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class LfFilterBarDarkTest : LfFilterBarTest() {
    @get:Rule
    val darkGoldens: DarkGoldensRule = DarkGoldensRule()
}
