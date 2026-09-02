package com.ledgerflow.core.designsystem.chart

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color
import com.ledgerflow.core.designsystem.theme.LfTheme
import kotlin.math.abs

/**
 * Chart colour, derived from the user's own taxonomy (§16 Q19, option 2).
 *
 * **The palette is not extended and no categorical ramp is invented.**
 * `CLAUDE.md` is firm that colour is not a fix for a layout problem, and
 * `category.color_argb` already holds a colour the user chose. So the hue comes
 * from their taxonomy and nothing here decides what colour a category "is".
 *
 * **What is normalised is lightness and saturation, and only those.** Those
 * colours were picked to read as small dots in a list; as adjacent arcs in a
 * donut they fail differently — two neighbours can be near-identical in
 * lightness, and an arbitrarily dark or pale one has no guaranteed contrast
 * against the card in either theme. Pinning S and L to per-theme constants
 * leaves hue as the only axis that varies, which is exactly the axis that
 * separates one slice from the next, and makes contrast a property of the
 * constant rather than of whatever the user picked.
 *
 * The alternative considered and rejected was using `color_argb` untouched: it
 * is more faithful, and it produces donuts where two categories are
 * indistinguishable and a third disappears into the card. Faithfulness to an
 * input chosen for a different purpose is not the goal; a chart the user can
 * read is.
 *
 * **A category with no colour, and the `''` sentinel, get a neutral** from the
 * palette rather than a generated hue — "unfiled" is a real answer and it
 * should not look like a category.
 */
@Immutable
public object LfCategoryPalette {

    /** Fixed per theme, so contrast is a property of these two numbers. */
    private const val DARK_SATURATION = 0.52f
    private const val DARK_LIGHTNESS = 0.62f
    private const val LIGHT_SATURATION = 0.58f
    private const val LIGHT_LIGHTNESS = 0.46f

    /**
     * The chart colour for a category.
     *
     * @param colorArgb the taxonomy's own colour, or null when the category has
     *   none — and for the `''` sentinel, which is not a category at all.
     */
    @Composable
    public fun colorFor(colorArgb: Int?): Color {
        val colors = LfTheme.colors
        if (colorArgb == null) return colors.textTertiary
        return normalise(colorArgb, colors.isDark)
    }

    /**
     * Deterministic fallback when a category exists but carries no colour.
     *
     * Hue from the id's hash so the same category is the same colour on every
     * launch and on every screen. A random or index-based hue would make a
     * donut change colours when a filter changed the ordering, which reads as
     * the data having changed.
     */
    @Composable
    public fun colorForId(id: String, colorArgb: Int?): Color {
        if (id.isEmpty()) return LfTheme.colors.textTertiary
        if (colorArgb != null) return colorFor(colorArgb)
        val hue = abs(id.hashCode() % HUE_STEPS) * (FULL_TURN / HUE_STEPS)
        val dark = LfTheme.colors.isDark
        return hsl(
            hue = hue,
            saturation = if (dark) DARK_SATURATION else LIGHT_SATURATION,
            lightness = if (dark) DARK_LIGHTNESS else LIGHT_LIGHTNESS,
        )
    }

    internal fun normalise(colorArgb: Int, isDark: Boolean): Color = hsl(
        hue = hueOf(colorArgb),
        saturation = if (isDark) DARK_SATURATION else LIGHT_SATURATION,
        lightness = if (isDark) DARK_LIGHTNESS else LIGHT_LIGHTNESS,
    )

    /** Hue in degrees, 0..360. Achromatic inputs land on 0 and stay grey-ish. */
    internal fun hueOf(argb: Int): Float {
        val r = ((argb shr RED_SHIFT) and CHANNEL_MASK) / CHANNEL_MAX
        val g = ((argb shr GREEN_SHIFT) and CHANNEL_MASK) / CHANNEL_MAX
        val b = (argb and CHANNEL_MASK) / CHANNEL_MAX
        val max = maxOf(r, g, b)
        val min = minOf(r, g, b)
        val delta = max - min
        if (delta == 0f) return 0f
        val hue = when (max) {
            r -> SEXTANT * (((g - b) / delta) % SEXTANT_COUNT)
            g -> SEXTANT * (((b - r) / delta) + GREEN_SEXTANT_OFFSET)
            else -> SEXTANT * (((r - g) / delta) + BLUE_SEXTANT_OFFSET)
        }
        return if (hue < 0f) hue + FULL_TURN else hue
    }

    /**
     * HSL to sRGB.
     *
     * Hand-rolled rather than via `android.graphics.Color.HSVToColor` so the
     * conversion is a pure function that Robolectric and plain JVM tests can
     * both call — the same reason `:core:model` avoids `java.util.Currency`.
     * These are chart *coordinates* in the Law 3 sense: real-valued and
     * legitimately so.
     */
    internal fun hsl(hue: Float, saturation: Float, lightness: Float): Color {
        val c = (1f - abs(HALF_TURN_FACTOR * lightness - 1f)) * saturation
        val x = c * (1f - abs(((hue / SEXTANT) % HALF_TURN_FACTOR) - 1f))
        val m = lightness - c / HALF_TURN_FACTOR
        val (r, g, b) = when {
            hue < RED_YELLOW -> Triple(c, x, 0f)
            hue < YELLOW_GREEN -> Triple(x, c, 0f)
            hue < GREEN_CYAN -> Triple(0f, c, x)
            hue < CYAN_BLUE -> Triple(0f, x, c)
            hue < BLUE_MAGENTA -> Triple(x, 0f, c)
            else -> Triple(c, 0f, x)
        }
        return Color(red = r + m, green = g + m, blue = b + m, alpha = 1f)
    }

    /**
     * Enough distinct hues that adjacent generated colours are separable, and
     * few enough that they are not near-duplicates. A donut with more slices
     * than this has bigger problems than colour — §5.6 caps the visible set with
     * Top-N + "Other" for exactly that reason.
     */
    private const val HUE_STEPS = 12

    // sRGB channel extraction.
    private const val RED_SHIFT = 16
    private const val GREEN_SHIFT = 8
    private const val CHANNEL_MASK = 0xFF
    private const val CHANNEL_MAX = 255f

    // The hue circle, in sextants -- the six faces of the RGB cube.
    private const val FULL_TURN = 360f
    private const val SEXTANT = 60f

    // The five boundaries between the RGB cube's six faces, named for the
    // transition each one is. Written out rather than computed as multiples of
    // SEXTANT so the `when` reads as a hue wheel and not as arithmetic.
    private const val RED_YELLOW = 60f
    private const val YELLOW_GREEN = 120f
    private const val GREEN_CYAN = 180f
    private const val CYAN_BLUE = 240f
    private const val BLUE_MAGENTA = 300f
    private const val SEXTANT_COUNT = 6f
    private const val GREEN_SEXTANT_OFFSET = 2f
    private const val BLUE_SEXTANT_OFFSET = 4f

    /** Two, as it appears in the HSL chroma and midpoint terms. */
    private const val HALF_TURN_FACTOR = 2f
}
