package com.ledgerflow.core.designsystem

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertWithMessage
import com.ledgerflow.core.designsystem.chart.BudgetAmounts
import com.ledgerflow.core.designsystem.theme.LfTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * **BUG55 — at font scale 2.0 a budget's amounts were cut off.**
 *
 * Found 2026-10-16 reviewing Home v1's first goldens: "₹8,200.00 of
 * ₹10,000.0" in Home's Budgets card, the last digit of the budget gone. The pair
 * was `softWrap = false`; the `FlowRow` around it moves it under the name when
 * the two will not share a line, but nothing handled the pair alone being wider
 * than the row. The Budgets screen's own golden escaped by luck ("₹7,200.00"
 * is narrower), and the goldens' clipping check could not see it, because the
 * bar clears its texts' semantics in favour of one spoken label.
 *
 * Measured at **280 dp**, the width Home's card gives a bar on a 360 dp phone
 * (24 dp inset and 16 dp card padding each side), which is the width it was seen
 * at. Read off the text's own layout, which is where clipping is a fact rather
 * than a judgement about pixels.
 */
@RunWith(AndroidJUnit4::class)
// NATIVE is load-bearing: without it text measures zero wide and nothing overflows.
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w360dp-h1600dp")
class Bug55_BudgetAmountsNeverClipTest {

    @get:Rule
    val rule = createComposeRule()

    private fun layout(text: String, fontScale: Float): TextLayoutResult {
        var result: TextLayoutResult? = null
        rule.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale)) {
                LfTheme {
                    Box(Modifier.width(BAR_WIDTH.dp)) {
                        BudgetAmounts(text = text, color = Color.Unspecified, onTextLayout = { result = it })
                    }
                }
            }
        }
        rule.waitForIdle()
        return requireNotNull(result) { "the amounts were never laid out" }
    }

    @Test
    fun atLargeText_theWholeBudgetIsShown_onASecondLine() {
        val layout = layout(PAIR, fontScale = 2f)

        assertWithMessage("clipped: \"$PAIR\" needs more than $BAR_WIDTH dp on one line")
            .that(layout.hasVisualOverflow).isFalse()
        assertWithMessage("lines").that(layout.lineCount).isEqualTo(2)
        // Between the amounts, never inside one.
        assertWithMessage("second line")
            .that(PAIR.substring(layout.getLineStart(1)))
            .isEqualTo("₹10,000.00")
    }

    /** Where the pair fits, it stays one line: nothing changes at ordinary sizes. */
    @Test
    fun atOrdinaryText_thePairIsOneLine() {
        val layout = layout(PAIR, fontScale = 1f)

        assertWithMessage("lines").that(layout.lineCount).isEqualTo(1)
        assertWithMessage("overflow").that(layout.hasVisualOverflow).isFalse()
    }

    private companion object {
        const val BAR_WIDTH = 280
        const val PAIR = "₹8,200.00 of ₹10,000.00"
    }
}
