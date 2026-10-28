package com.ledgerflow.core.designsystem.component

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performTouchInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.ledgerflow.core.designsystem.icon.LfIcons
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
 * The bottom bar as the reference samples draw it (step F3): four labelled
 * destinations and the + raised half above the bar, ringed in the page colour.
 *
 * The goldens are the first of the bar; it had none. The tap test is the one a
 * golden cannot be: the raised half of the + sits outside the bar's bounds, and
 * a finger on it must still reach the +.
 *
 * **Review the diff; never re-record blind** (`CLAUDE.md` §12).
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [GOLDEN_SDK], qualifiers = PHONE_1X)
open class LfBottomBarTest {

    @get:Rule
    val rule = createComposeRule()

    @Composable
    private fun Screen(onAdd: () -> Unit = {}) {
        LfTheme {
            Column(modifier = Modifier.fillMaxSize().background(LfTheme.colors.surface.canvas)) {
                Spacer(modifier = Modifier.weight(1f))
                LfBottomBar(
                    items = listOf(
                        LfNavItem("Home", LfIcons.Dashboard, selected = false, onClick = {}),
                        LfNavItem("Ledger", LfIcons.Ledger, selected = true, onClick = {}),
                        LfNavItem("Analytics", LfIcons.Analytics, selected = false, onClick = {}),
                        LfNavItem("Profile", LfIcons.Profile, selected = false, onClick = {}),
                    ),
                    onAddClick = onAdd,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }

    @Test fun bar_1x() = rule.captureScreenGolden("bottom-bar-1x", 1f) { Screen() }

    @Config(qualifiers = PHONE_2X)
    @Test fun bar_2x() = rule.captureScreenGolden("bottom-bar-2x", 2f) { Screen() }

    /** A tap near the top of the +, on the half raised above the bar, reaches it. */
    @Test
    fun theRaisedHalf_isTappable() {
        var added = 0
        rule.setContent { Screen(onAdd = { added++ }) }
        rule.onNodeWithContentDescription("Add an entry").performTouchInput {
            click(Offset(centerX, height * TOP_FIFTH))
        }
        assertThat(added).isEqualTo(1)
    }

    private companion object {
        const val TOP_FIFTH = 0.2f
    }
}

/** The same goldens with the phone in dark mode, saved with a `-dark` suffix (DESIGN_SYSTEM_PLAN §6.3). */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class LfBottomBarDarkTest : LfBottomBarTest() {
    @get:Rule
    val darkGoldens: DarkGoldensRule = DarkGoldensRule()
}
