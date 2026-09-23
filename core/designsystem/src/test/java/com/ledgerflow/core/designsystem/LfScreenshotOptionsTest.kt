package com.ledgerflow.core.designsystem

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.dropbox.differ.Color
import com.dropbox.differ.Image
import com.dropbox.differ.Mask
import com.github.takahirom.roborazzi.RoborazziOptions
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * The screenshot tolerance lets through what a Linux runner does to text, and
 * nothing a person would see (`LfScreenshotOptions`).
 *
 * Every case runs the **configured** comparator and validator — the objects the
 * goldens are compared with — rather than restating the number, so changing the
 * tolerance in either direction turns one of these red.
 *
 * Under Robolectric only because `RoborazziOptions` reads Robolectric's
 * configuration when it is constructed; nothing here renders.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [OPTIONS_ROBOLECTRIC_SDK])
class LfScreenshotOptionsTest {

    private val compare = requireNotNull(LfScreenshotOptions.compareOptions)

    /** BUG33: what CI showed on 2026-09-23 — edge pixels moved by up to 4/255. */
    @Test
    fun Bug33_antiAliasingNoiseAsCiSawIt_passes() {
        val golden = page()
        val linux = page().nudged(at = EDGE, by = 4)

        assertThat(differences(golden, linux)).isEqualTo(0)
        assertThat(passes(golden, linux)).isTrue()
    }

    /** The bound itself: 8/255 on R, G and B at once. */
    @Test
    fun theToleranceItself_passes() {
        assertThat(passes(page(), page().nudged(at = EDGE, by = 8))).isTrue()
    }

    /** The reason for the change: Roborazzi's own default failed a 2/255 move. */
    @Test
    fun roborazzisDefault_wouldHaveFailedTheSameNoise() {
        val default = requireNotNull(RoborazziOptions().compareOptions)
        val golden = page()
        val linux = page().nudged(at = EDGE, by = 2)

        assertThat(default.resultValidator(default.imageComparator.compare(golden, linux, mask()))).isFalse()
    }

    /** BUG9's shape: a glyph one pixel over is ink where the background was. */
    @Test
    fun aOnePixelShift_fails() {
        assertThat(passes(page(glyphAt = 4), page(glyphAt = 5))).isFalse()
    }

    /**
     * A palette token moving (#3B5BDB to #4263EB, a plausible "slightly
     * different blue") is a distance of about 0.075: past the tolerance.
     */
    @Test
    fun aPaletteChange_fails() {
        val before = page(ink = rgb(0x3B, 0x5B, 0xDB))
        val after = page(ink = rgb(0x42, 0x63, 0xEB))

        assertThat(passes(before, after)).isFalse()
    }

    /** One pixel past the tolerance is enough: the validator allows no count. */
    @Test
    fun aSinglePixelPastTheTolerance_fails() {
        val golden = page()
        val one = page().nudged(at = EDGE, by = 40)

        assertThat(differences(golden, one)).isEqualTo(1)
        assertThat(passes(golden, one)).isFalse()
    }

    private fun differences(a: Image, b: Image): Int = result(a, b).pixelDifferences

    private fun passes(a: Image, b: Image): Boolean = compare.resultValidator(result(a, b))

    private fun result(a: Image, b: Image) = compare.imageComparator.compare(a, b, mask())

    private fun mask() = Mask(SIZE, SIZE)

    /** A light page with a 3x3 "glyph" of [ink] whose left edge is at column [glyphAt]. */
    private fun page(glyphAt: Int = 4, ink: Color = rgb(0x1A, 0x1A, 0x1A)): PixelImage {
        val paper = rgb(0xFA, 0xFA, 0xFB)
        return PixelImage { x, y -> if (x in glyphAt until glyphAt + 3 && y in 4 until 7) ink else paper }
    }

    private fun rgb(r: Int, g: Int, b: Int) = Color(r / 255f, g / 255f, b / 255f, 1f)

    /** An [Image] whose pixel at [at] has each of R, G and B lowered by [by]/255. */
    private fun PixelImage.nudged(at: Pair<Int, Int>, by: Int): PixelImage = PixelImage { x, y ->
        val c = pixel(x, y)
        if (x == at.first && y == at.second) Color(c.r - by / 255f, c.g - by / 255f, c.b - by / 255f, c.a) else c
    }

    private class PixelImage(val pixel: (Int, Int) -> Color) : Image {
        override val width: Int = SIZE
        override val height: Int = SIZE
        override fun getPixel(x: Int, y: Int): Color = pixel(x, y)
    }

    private companion object {
        const val SIZE = 12

        /** An anti-aliased edge pixel: on the paper, just left of the glyph. */
        val EDGE = 3 to 5
    }
}

private const val OPTIONS_ROBOLECTRIC_SDK = 34
