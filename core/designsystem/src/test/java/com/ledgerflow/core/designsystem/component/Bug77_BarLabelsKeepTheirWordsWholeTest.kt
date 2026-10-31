package com.ledgerflow.core.designsystem.component

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertWithMessage
import com.ledgerflow.core.designsystem.icon.LfIcons
import com.ledgerflow.core.designsystem.theme.LfTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * BUG77: at font scale 2.0 the bottom bar's labels broke mid-word in their
 * 72 dp cells -- "Ledge / r", "Analy / tics", "Profil / e" -- found by the
 * bar's first golden (design-system step F3). A control's label never wraps
 * (BUG9). Now each is one line that steps its size down only when the word
 * would not otherwise fit.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w360dp-h800dp")
class Bug77_BarLabelsKeepTheirWordsWholeTest {

    @get:Rule
    val rule = createComposeRule()

    @Test
    fun atFontScale2_everyLabelIsOneWholeLine() = assertOneLineEach(fontScale = 2f)

    @Test
    fun atFontScale1_everyLabelIsOneWholeLine() = assertOneLineEach(fontScale = 1f)

    private fun assertOneLineEach(fontScale: Float) {
        rule.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale)) {
                LfTheme {
                    LfBottomBar(
                        items = LABELS.mapIndexed { i, label ->
                            LfNavItem(label, LfIcons.Dashboard, selected = i == 1, onClick = {})
                        },
                        onAddClick = {},
                    )
                }
            }
        }
        val layouts = LABELS.associateWith { label ->
            val out = mutableListOf<TextLayoutResult>()
            rule.onNodeWithText(label).fetchSemanticsNode().config[SemanticsActions.GetTextLayoutResult]
                .action?.invoke(out)
            out.single()
        }
        // Lines first: the defect was a word broken across two.
        assertWithMessage("lines at %sx", fontScale)
            .that(layouts.mapValues { it.value.lineCount })
            .isEqualTo(LABELS.associateWith { 1 })
        // And kept on one line by shrinking, never by clipping.
        assertWithMessage("overflowing at %sx", fontScale)
            .that(layouts.filterValues { it.hasVisualOverflow }.keys)
            .isEmpty()
        // Shrinking only when needed: at 1.0 every label is the scale's own size.
        if (fontScale == 1f) {
            assertWithMessage("sizes at 1x")
                .that(layouts.mapValues { it.value.layoutInput.style.fontSize.value })
                .isEqualTo(LABELS.associateWith { LABEL_SP })
        }
    }

    private companion object {
        val LABELS = listOf("Home", "Ledger", "Analytics", "Profile")
        const val LABEL_SP = 12f // `label`, LfTypeScale.Size12
    }
}
