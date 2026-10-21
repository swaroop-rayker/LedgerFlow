package com.ledgerflow.core.designsystem.component

import android.content.Context
import android.content.res.Configuration
import android.util.DisplayMetrics
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import com.ledgerflow.core.designsystem.icon.LfIcons
import com.ledgerflow.core.designsystem.theme.LfTheme
import com.ledgerflow.core.testing.screenshot.inkDifferences
import kotlin.math.roundToInt
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * **The one-node icon button draws its glyph where the three nodes did**
 * (PERF-120HZ-PLAN §2.3), and is still a labelled button.
 *
 * It was a 48dp `Box` around Material's `IconButton` around an `Icon`. The
 * glyph's position came out of two separate centrings, each rounded: the 40dp
 * state layer in the 48dp target, then the 16dp glyph in the state layer. At
 * a density where those halves are fractional (2.625x: 10.5 px and 31.5 px)
 * centring once lands a pixel away, which the densities below are chosen to
 * catch. The old composition is kept below and both are rendered.
 *
 * NATIVE so the vector actually rasterises.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w360dp-h640dp")
class LfIconButtonTest {

    @get:Rule
    val rule = createComposeRule()

    private data class Case(val density: Float, val direction: LayoutDirection, val dark: Boolean)

    @Test
    fun oneNodeButton_everyDensity_drawsTheGlyphWhereMaterialsDid() {
        var case by mutableStateOf<Case?>(null)
        rule.setContent {
            val current = case ?: return@setContent
            key(current) {
                CompositionLocalProvider(
                    LocalDensity provides platformDensity(LocalContext.current, current.density),
                    LocalLayoutDirection provides current.direction,
                ) {
                    LfTheme(darkTheme = current.dark) {
                        val icon = rememberVectorPainter(LfIcons.Delete)
                        Row {
                            Box(Modifier.testTag(MATERIAL)) { MaterialIconButton(icon) }
                            Box(Modifier.testTag(ONE_NODE)) { LfIconButton(icon, "Delete", onClick = {}) }
                        }
                    }
                }
            }
        }

        for (c in CASES) {
            case = c
            rule.waitForIdle()
            val material = rule.onNodeWithTag(MATERIAL).captureToImage().toPixelMap()
            val oneNode = rule.onNodeWithTag(ONE_NODE).captureToImage().toPixelMap()
            assertWithMessage("size, %s", c).that(oneNode.width to oneNode.height)
                .isEqualTo(material.width to material.height)
            assertWithMessage("pixels differing of %sx%s, %s", material.width, material.height, c)
                .that(inkDifferences(material, oneNode)).isEmpty()
        }
        assertWithMessage("combinations compared").that(CASES.size).isEqualTo(20)
    }

    @Test
    fun oneNodeButton_isALabelledButtonThatClicks() {
        var clicks = 0
        rule.setContent {
            LfTheme { LfIconButton(rememberVectorPainter(LfIcons.Delete), "Delete Big Bazaar", onClick = { clicks++ }) }
        }
        rule.onNodeWithContentDescription("Delete Big Bazaar")
            .assertHasClickAction()
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button))
            .performClick()
        assertThat(clicks).isEqualTo(1)
    }

    private fun platformDensity(context: Context, density: Float): Density {
        val config = Configuration(context.resources.configuration).apply {
            densityDpi = (density * DisplayMetrics.DENSITY_DEFAULT).roundToInt()
        }
        return Density(context.createConfigurationContext(config))
    }

    private companion object {
        const val MATERIAL = "material"
        const val ONE_NODE = "oneNode"

        // 2.625x and 3.5x put the two centrings on half pixels.
        val CASES: List<Case> = listOf(1f, 2f, 2.625f, 3f, 3.5f).flatMap { density ->
            LayoutDirection.entries.flatMap { direction ->
                listOf(false, true).map { dark -> Case(density, direction, dark) }
            }
        }
    }
}

/** `LfIconButton`'s painter overload before PERF-120HZ-PLAN §2.3. Kept as the reference. */
@Composable
private fun MaterialIconButton(icon: Painter) {
    val spacing = LfTheme.spacing
    Box(modifier = Modifier.size(spacing.minTouchTarget), contentAlignment = Alignment.Center) {
        IconButton(
            onClick = {},
            modifier = Modifier.clip(CircleShape),
            colors = IconButtonDefaults.iconButtonColors(contentColor = LfTheme.colors.textSecondary),
        ) {
            Icon(painter = icon, contentDescription = "Delete", modifier = Modifier.size(spacing.md))
        }
    }
}
