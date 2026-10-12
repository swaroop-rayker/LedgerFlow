package com.ledgerflow.feature.ledger

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
import com.ledgerflow.core.designsystem.theme.LfTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * **BUG47: at font scale 2.0 an Unsaved row lost its `Draft` / `To review` marker.**
 *
 * The detail line was `maxLines = 1` with the marker last, after the stamp
 * (the stamp leads by design, `960b5ee`), so at large text the ellipsis landed
 * on the marker: P5 step 4's 2.0 golden showed a draft as
 * "19 Aug, 12:00 PM · …". The marker is what tells two look-alike rows apart
 * when they open different screens and discard differently, so losing it is
 * the defect, not a cosmetic one. A candidate row was worse: its amount sat
 * beside the line and left it about 150 dp.
 *
 * The line now wraps to [UNSAVED_DETAIL_MAX_LINES] and both rows use the
 * adaptive body, so the amount moves below before the line is squeezed.
 *
 * **Measured at the width a row really gives the line**, the lesson of BUG42:
 * a draft row on a 360 dp phone is a 328 dp card, less 16 + 4 dp of padding,
 * the 48 dp discard button and an 8 dp gap, so 252 dp. A candidate row (no
 * button) gives more, so 252 dp covers both. The stamp is the longest the app
 * prints: a four-letter month and a two-digit hour.
 */
@RunWith(AndroidJUnit4::class)
// NATIVE is load-bearing: without it text measures zero wide and nothing ellipsises.
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w360dp-h1600dp")
class Bug47_UnsavedMarkerSurvivesLargeTextTest {

    @get:Rule
    val rule = createComposeRule()

    private fun layout(text: String, fontScale: Float): TextLayoutResult {
        var result: TextLayoutResult? = null
        rule.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale)) {
                LfTheme {
                    Box(Modifier.width(ROW_DETAIL_WIDTH.dp)) {
                        UnsavedDetail(text = text, color = Color.Unspecified, onTextLayout = { result = it })
                    }
                }
            }
        }
        rule.waitForIdle()
        return requireNotNull(result) { "the detail line was never laid out" }
    }

    private fun assertWhole(text: String, fontScale: Float) {
        val result = layout(text, fontScale)
        val ellipsised = (0 until result.lineCount).filter { result.isLineEllipsized(it) }
        assertWithMessage("'%s' at %sx: ellipsised lines", text, fontScale).that(ellipsised).isEmpty()
        assertWithMessage("'%s' at %sx: overflows", text, fontScale).that(result.hasVisualOverflow).isFalse()
    }

    @Test
    fun Bug47_draftMarkerAtFontScale2_isNotCut() = assertWhole(pendingDetail(LONGEST_STAMP), 2f)

    @Test
    fun Bug47_toReviewMarkerAtFontScale2_isNotCut() = assertWhole(candidateDetail(LONGEST_STAMP), 2f)

    @Test
    fun atFontScale1_theLineStaysOneLine_soTheFixCostsNoHeight() {
        val result = layout(candidateDetail(LONGEST_STAMP), 1f)
        assertWithMessage("lines at 1.0").that(result.lineCount).isEqualTo(1)
    }

    private companion object {
        const val ROW_DETAIL_WIDTH = 252
        const val LONGEST_STAMP = "28 Sept, 11:43 PM"
    }
}
