package com.ledgerflow.core.designsystem

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertWithMessage
import com.ledgerflow.core.designsystem.component.LfNavRow
import com.ledgerflow.core.designsystem.theme.LfTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * An `LfNavRow` title never breaks inside a word.
 *
 * Found 2026-10-27 reviewing design-system step D4's first goldens, before it
 * shipped: on the Settings index at font scale 2.0, "Security & privacy" beside
 * its value "Phone's lock" read "Secur / ity & / priva / cy". The value was
 * measured first and took its whole width, leaving the title less than its
 * longest word. The value is now capped at half the row.
 *
 * Measured at **312 dp**, the width the index panel has on a 360 dp phone (the
 * 24 dp edge each side), and read off the title's own layout: a line that ends
 * mid-word is a fact there, not a judgement about pixels.
 */
@RunWith(AndroidJUnit4::class)
// NATIVE is load-bearing: without it text measures zero wide and nothing wraps.
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w360dp-h1600dp")
class LfNavRowTitleBreaksBetweenWordsTest {

    @get:Rule
    val rule = createComposeRule()

    private fun titleLayout(title: String, value: String, fontScale: Float): TextLayoutResult {
        rule.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale)) {
                LfTheme {
                    Box(Modifier.width(PANEL_WIDTH.dp)) { LfNavRow(title = title, value = value, onClick = {}) }
                }
            }
        }
        rule.waitForIdle()
        val results = mutableListOf<TextLayoutResult>()
        rule.onNodeWithText(title, useUnmergedTree = true).fetchSemanticsNode()
            .config[SemanticsActions.GetTextLayoutResult].action?.invoke(results)
        return results.single()
    }

    private fun assertBreaksBetweenWords(title: String, value: String) {
        val layout = titleLayout(title, value, fontScale = 2f)
        for (line in 0 until layout.lineCount - 1) {
            val end = layout.getLineEnd(line)
            assertWithMessage("\"$title\" line ${line + 1} ends at \"${title.take(end)}\"")
                .that(title[end - 1] == ' ' || title[end] == ' ')
                .isTrue()
        }
    }

    @Test fun securityAndPrivacy_besidePhonesLock() = assertBreaksBetweenWords("Security & privacy", "Phone's lock")

    @Test fun currencyAndRegion_besideACode() = assertBreaksBetweenWords("Currency & region", "INR")

    @Test fun notifications_besideAShortValue() = assertBreaksBetweenWords("Notifications", "Stopped")

    private companion object {
        const val PANEL_WIDTH = 312
    }
}
