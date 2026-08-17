package com.ledgerflow.core.designsystem.theme

import androidx.compose.ui.graphics.Color
import com.google.common.truth.Truth.assertThat
import com.ledgerflow.core.designsystem.component.LfCategoryInk
import com.ledgerflow.core.model.CategoryPalette
import org.junit.Test

/**
 * The 16 category swatches, held to §9.6 like every other colour.
 *
 * §9.1 says category colours must be "all WCAG AA against both surfaces", which
 * had no test behind it. Every swatch carries the category's initial in
 * `onAccent`, so that pairing is the one that has to clear AA -- a swatch that
 * fails it produces an unreadable letter inside `LfCategoryDot`, which is the
 * only thing distinguishing two categories for a colour-blind user.
 *
 * The swatches are also checked against both surfaces at the large-text
 * threshold, because a dot that vanishes into the card behind it is no better
 * than one whose label cannot be read.
 */
class CategoryPaletteContrastTest {

    private fun swatch(argb: Int) = Color(argb)

    /**
     * The pairing that actually ships: white, fixed, on every swatch.
     *
     * The first version of this test used `LfDarkColors.onAccent` because that
     * is what `LfCategoryDot` used, and it failed on all sixteen. It could only
     * ever fail: on-accent is near-black in dark mode and near-white in light,
     * and `contrast(white, c) x contrast(c, black)` is about 21 for any colour,
     * so 4.5 against both is arithmetically out of reach. The dot now pins its
     * ink instead of taking it from the theme.
     */
    @Test
    fun everySwatchCarriesItsInitialAtAaNormal() {
        CategoryPalette.swatches.forEach { argb ->
            assertThat(LfContrast.ratio(LfCategoryInk, swatch(argb)))
                .isAtLeast(LfContrast.AA_NORMAL)
        }
    }

    @Test
    fun everySwatchIsVisibleAgainstTheDarkSurfaces() {
        CategoryPalette.swatches.forEach { argb ->
            assertThat(LfContrast.ratio(swatch(argb), LfDarkColors.surfaceBase))
                .isAtLeast(LfContrast.AA_LARGE)
            // surfaceRaised is the tighter of the two and the one that sets the
            // palette's lower luminance bound.
            assertThat(LfContrast.ratio(swatch(argb), LfDarkColors.surfaceRaised))
                .isAtLeast(LfContrast.AA_LARGE)
        }
    }

    @Test
    fun everySwatchIsVisibleAgainstTheLightSurfaces() {
        CategoryPalette.swatches.forEach { argb ->
            assertThat(LfContrast.ratio(swatch(argb), LfLightColors.surfaceBase))
                .isAtLeast(LfContrast.AA_LARGE)
            assertThat(LfContrast.ratio(swatch(argb), LfLightColors.surfaceRaised))
                .isAtLeast(LfContrast.AA_LARGE)
        }
    }

    /**
     * Two categories that render the same colour are indistinguishable, and the
     * seed set alone uses twenty of them -- so the wrap-around in
     * [CategoryPalette.forIndex] must at least not collide inside one cycle.
     */
    @Test
    fun swatchesAreDistinct() {
        assertThat(CategoryPalette.swatches.toSet()).hasSize(CategoryPalette.swatches.size)
    }

    @Test
    fun forIndex_wrapsRatherThanThrowing() {
        val size = CategoryPalette.swatches.size
        assertThat(CategoryPalette.forIndex(size)).isEqualTo(CategoryPalette.forIndex(0))
        assertThat(CategoryPalette.forIndex(-1)).isEqualTo(CategoryPalette.forIndex(size - 1))
    }
}
