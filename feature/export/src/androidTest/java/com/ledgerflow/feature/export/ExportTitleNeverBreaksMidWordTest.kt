package com.ledgerflow.feature.export

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertWithMessage
import com.ledgerflow.core.designsystem.theme.LfTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * BUG17's shape, checked on the other screen that still uses it.
 *
 * `ExportScreen` keeps the shared header — `Row(LfScreenTitle(weight = 1f),
 * LfButton)` — that broke the notification explainer's title mid-word. Nothing
 * has been reported here and this suite is expected to be **green**, because
 * "Export" is one short word where "Notification capture" is twenty characters.
 *
 * **A green guard over an unbroken screen is the point, not a waste.** BUG17's
 * cause was not a bad title, it was a header whose title width is a function of
 * the *button* beside it — so this screen is one label change away from the same
 * defect, and the change that causes it would look entirely innocent. §9.6
 * requires font scale 2.0, and nothing checked these titles at it.
 *
 * The technique is `Bug17_ScreenTitleNeverBreaksMidWordTest`'s: read the real
 * `TextLayoutResult` and assert every line break lands on whitespace. It is
 * duplicated rather than shared because features may not depend on features
 * (`CLAUDE.md` §3), and lifting fifteen lines of test helper into
 * `:core:designsystem` to avoid that is the more expensive mistake.
 */
@RunWith(AndroidJUnit4::class)
class ExportTitleNeverBreaksMidWordTest {

    @get:Rule
    val composeRule = createComposeRule()

    private fun setScreen(fontScale: Float) {
        composeRule.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(
                LocalDensity provides Density(density.density, fontScale),
            ) {
                LfTheme {
                    ExportScreen(
                        state = ExportUiState(),
                        suggestedFileName = "ledgerflow-export.zip",
                        onEvent = {},
                        onBack = {},
                    )
                }
            }
        }
    }

    private fun assertNoMidWordBreak(text: String) {
        val results = mutableListOf<TextLayoutResult>()
        composeRule.onNodeWithText(text).fetchSemanticsNode()
            .config[SemanticsActions.GetTextLayoutResult]
            .action
            ?.invoke(results)
        val layout = results.first()
        val laidOut = layout.layoutInput.text.text
        for (line in 0 until layout.lineCount - 1) {
            val end = layout.getLineEnd(line, visibleEnd = false)
            if (end <= 0 || end >= laidOut.length) continue
            assertWithMessage(
                "\"%s\" breaks mid-word at line %s, between '%s' and '%s'",
                laidOut,
                line,
                laidOut[end - 1],
                laidOut[end],
            ).that(laidOut[end - 1].isWhitespace() || laidOut[end].isWhitespace()).isTrue()
        }
    }

    @Test
    fun title_atDefaultFontScale_keepsItsWordsWhole() {
        setScreen(fontScale = 1f)

        assertNoMidWordBreak("Export")
    }

    /** The owner's device setting, where BUG17 was reported. */
    @Test
    fun title_atTheReportedFontScale_keepsItsWordsWhole() {
        setScreen(fontScale = 1.15f)

        assertNoMidWordBreak("Export")
    }

    /** §9.6's requirement, and the scale nothing had checked this title at. */
    @Test
    fun title_atLargestSupportedFontScale_keepsItsWordsWhole() {
        setScreen(fontScale = 2f)

        assertNoMidWordBreak("Export")
    }
}
