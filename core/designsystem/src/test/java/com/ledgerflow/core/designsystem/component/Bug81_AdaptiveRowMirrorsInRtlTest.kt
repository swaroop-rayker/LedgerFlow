package com.ledgerflow.core.designsystem.component

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.ledgerflow.core.designsystem.theme.LfTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * BUG81: `LfAdaptiveRow` did not mirror under a right-to-left language.
 *
 * Both children were placed with `place`, so on an Arabic or Hebrew phone
 * Home's "Spent" stayed on the left with its figure on the right, and every
 * Ledger, Inbox and Analytics row kept its date left and its amount right,
 * while everything around them mirrored. Found reviewing V2-3's RTL goldens
 * (2026-10-31); `home-month-rtl` had shown it since P5.
 *
 * Fixed-size boxes rather than text, so the assertion is about placement and
 * nothing else.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34])
class Bug81_AdaptiveRowMirrorsInRtlTest {

    @get:Rule
    val rule = createComposeRule()

    private fun show(direction: LayoutDirection, leadingWidth: Dp) {
        rule.setContent {
            LfTheme {
                CompositionLocalProvider(LocalLayoutDirection provides direction) {
                    LfAdaptiveRow(
                        leading = { Box(Modifier.testTag(LEADING).size(leadingWidth, 20.dp)) },
                        trailing = { Box(Modifier.testTag(TRAILING).size(80.dp, 20.dp)) },
                        modifier = Modifier.width(ROW),
                    )
                }
            }
        }
    }

    private fun bounds(tag: String) = rule.onNodeWithTag(tag).getBoundsInRoot()

    @Test
    fun sideBySide_rtl_putsTheLeadingBlockOnTheRight() {
        show(LayoutDirection.Rtl, leadingWidth = 100.dp)

        assertThat(bounds(LEADING).right).isEqualTo(ROW)
        assertThat(bounds(TRAILING).left).isEqualTo(0.dp)
    }

    @Test
    fun sideBySide_ltr_isUnchanged() {
        show(LayoutDirection.Ltr, leadingWidth = 100.dp)

        assertThat(bounds(LEADING).left).isEqualTo(0.dp)
        assertThat(bounds(TRAILING).right).isEqualTo(ROW)
    }

    /** Too wide to share a line: stacked, the leading block at the start and the trailing at the end. */
    @Test
    fun stacked_rtl_keepsEachBlockOnItsOwnEdge() {
        show(LayoutDirection.Rtl, leadingWidth = 250.dp)

        assertThat(bounds(TRAILING).top).isGreaterThan(bounds(LEADING).top)
        assertThat(bounds(LEADING).right).isEqualTo(ROW)
        assertThat(bounds(TRAILING).left).isEqualTo(0.dp)
    }

    private companion object {
        const val LEADING = "leading"
        const val TRAILING = "trailing"
        val ROW = 300.dp
    }
}
