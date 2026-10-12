package com.ledgerflow.feature.export

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertWithMessage
import com.ledgerflow.core.designsystem.theme.LfTheme
import com.ledgerflow.core.testing.screenshot.GOLDEN_SDK
import com.ledgerflow.core.testing.screenshot.PHONE_2X
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * **BUG48: at font scale 2.0 Export's file name broke inside the year.**
 *
 * The 2.0 golden showed "LedgerFlow-export-202" over "6-08-21.xlsx". A file
 * name has no spaces, and the line breaker does not break at a hyphen followed
 * by a digit, so the only break left was mid-number. The date is the part of
 * the name a person reads, so that is the one place it must not be cut.
 *
 * Rendered on the real Export screen at a 360 dp phone's width, reading the
 * file name's own text layout through its semantics node.
 */
@RunWith(AndroidJUnit4::class)
// NATIVE is load-bearing: without it text measures zero wide and nothing breaks.
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [GOLDEN_SDK], qualifiers = PHONE_2X)
class Bug48_ExportFileNameNeverBreaksInsideTheDateTest {

    @get:Rule
    val rule = createComposeRule()

    private fun fileNameLayout(fontScale: Float): TextLayoutResult {
        rule.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale)) {
                LfTheme {
                    ExportScreen(state = ExportUiState(), suggestedFileName = NAME, onEvent = {}, onBack = {})
                }
            }
        }
        val node = rule.onNode(hasText("export-", substring = true)).fetchSemanticsNode()
        val results = mutableListOf<TextLayoutResult>()
        requireNotNull(node.config.getOrNull(SemanticsActions.GetTextLayoutResult)?.action) {
            "the file name exposes no text layout"
        }.invoke(results)
        return results.single()
    }

    @Test
    fun Bug48_atFontScale2_noLineBreaksInsideTheDate() {
        val layout = fileNameLayout(2f)
        val text = layout.layoutInput.text.text
        val dateStart = text.indexOf("2026")
        val breaks = (0 until layout.lineCount - 1).map { layout.getLineEnd(it) }
        assertWithMessage("line breaks in '%s' (the date starts at %s)", text, dateStart)
            .that(breaks.filter { it > dateStart })
            .isEmpty()
    }

    private companion object {
        const val NAME = "LedgerFlow-export-2026-08-21.xlsx"
    }
}
