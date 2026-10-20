package com.ledgerflow.core.designsystem.component

import android.content.Context
import android.content.res.Configuration
import android.util.DisplayMetrics
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.TextUnit
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertWithMessage
import com.ledgerflow.core.designsystem.theme.LfTheme
import com.ledgerflow.core.testing.screenshot.inkDifferences
import kotlin.math.roundToInt
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * **The drawn category dot is the composed one, pixel for pixel**
 * (PERF-120HZ-PLAN §2.2).
 *
 * `LfCategoryDot` was a `Box` with a circular background around a `Text`. It is
 * now one node that draws both, so a list row pays for a coloured circle with
 * one leaf instead of three nodes. The old pair is kept below and both are
 * rendered for each initial, font scale, density and direction.
 *
 * The font scales matter because the initial is pinned to the circle rather
 * than to the user's text size, and the density because 24 dp is a fractional
 * radius at most of them. Densities come from the platform's own
 * `Density(context)`, as a phone would hand them over.
 *
 * NATIVE is load-bearing: without it Robolectric measures text zero wide.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w360dp-h640dp")
class LfCategoryDotTest {

    @get:Rule
    val rule = createComposeRule()

    private data class Case(val name: String, val density: Float, val fontScale: Float, val direction: LayoutDirection)

    @Test
    fun drawnDot_everyInitialAndScale_matchesTheComposedDot() {
        var case by mutableStateOf<Case?>(null)
        rule.setContent {
            val current = case ?: return@setContent
            // A fresh composition per case, so no node carries a layout from the
            // previous density or direction into this one.
            key(current) {
                CompositionLocalProvider(
                    LocalDensity provides platformDensity(LocalContext.current, current.density, current.fontScale),
                    LocalLayoutDirection provides current.direction,
                ) {
                    LfTheme(darkTheme = false) {
                        Row {
                            Box(Modifier.testTag(COMPOSED)) { ComposedCategoryDot(current.name, COLOR) }
                            Box(Modifier.testTag(DRAWN)) { LfCategoryDot(current.name, COLOR) }
                        }
                    }
                }
            }
        }

        for (c in CASES) {
            case = c
            rule.waitForIdle()
            val composed = rule.onNodeWithTag(COMPOSED).captureToImage().toPixelMap()
            val drawn = rule.onNodeWithTag(DRAWN).captureToImage().toPixelMap()
            assertWithMessage("size, %s", c).that(drawn.width to drawn.height)
                .isEqualTo(composed.width to composed.height)
            assertWithMessage("pixels differing of %sx%s, %s", composed.width, composed.height, c)
                .that(inkDifferences(composed, drawn)).isEmpty()
        }
        assertWithMessage("combinations compared").that(CASES.size).isEqualTo(NAMES.size * 12)
    }

    @Test
    fun drawnDot_saysNothing_theAdjacentLabelNamesTheCategory() {
        rule.setContent { LfTheme { Box(Modifier.testTag(DRAWN)) { LfCategoryDot("Groceries", COLOR) } } }
        val node = rule.onNodeWithTag(DRAWN, useUnmergedTree = true).fetchSemanticsNode()
        val said = node.children.flatMap { child ->
            child.config.getOrElse(SemanticsProperties.ContentDescription) { emptyList() } +
                child.config.getOrElse(SemanticsProperties.Text) { emptyList() }.map { it.text }
        }
        assertWithMessage("what the swatch announces").that(said).isEmpty()
    }

    /** The density a phone would hand the app, through the platform's own conversion. */
    private fun platformDensity(context: Context, density: Float, fontScale: Float): Density {
        val config = Configuration(context.resources.configuration).apply {
            this.fontScale = fontScale
            densityDpi = (density * DisplayMetrics.DENSITY_DEFAULT).roundToInt()
        }
        val result = Density(context.createConfigurationContext(config))
        check(result.density == density && result.fontScale == fontScale) {
            "asked for $density x $fontScale, got ${result.density} x ${result.fontScale}"
        }
        return result
    }

    private companion object {
        const val COMPOSED = "composed"
        const val DRAWN = "drawn"
        val COLOR = 0xFF2E7D32.toInt()

        val NAMES = listOf(
            "Groceries",
            "fuel",
            "Wellness",
            "Électricité",
            "अनाज",
            "Жильё",
            "1st floor",
            "₹ cash",
            "",
        )

        // 2.625x: 24 dp is a 31.5 px radius, an edge that falls mid-pixel.
        val CASES: List<Case> = NAMES.flatMap { name ->
            listOf(1f, 2.625f).flatMap { density ->
                listOf(1f, 1.3f, 2f).flatMap { fontScale ->
                    LayoutDirection.entries.map { direction -> Case(name, density, fontScale, direction) }
                }
            }
        }
    }
}

/** `LfCategoryDot` before PERF-120HZ-PLAN §2.2. Kept as the reference; do not tidy. */
@Composable
private fun ComposedCategoryDot(name: String, colorArgb: Int) {
    val diameter = LfTheme.spacing.lg
    val initialSize = with(LocalDensity.current) { (diameter * 0.5f).toSp() }
    Box(
        modifier = Modifier.size(diameter).background(Color(colorArgb), CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = name.firstOrNull()?.uppercase().orEmpty(),
            style = LfTheme.typography.label.copy(fontSize = initialSize, lineHeight = TextUnit.Unspecified),
            color = LfCategoryInk,
        )
    }
}
