package com.ledgerflow.core.designsystem.theme

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/**
 * LedgerFlow's semantic colours (SPEC.md §9.1).
 *
 * Held separately from Material's `ColorScheme` because the tokens that carry
 * the most meaning here -- [debit], [credit], [warn] -- have no Material slot.
 * Mapping "money leaving your account" onto `error` would be both wrong and
 * unreadable: an expense is not an error.
 *
 * **Semi-dark by default**, not OLED black: a warm-neutral dark that is easy on
 * the eyes at night, which is when people reconcile their spending.
 */
@Immutable
public data class LfColors(
    val surfaceBase: Color,
    val surfaceRaised: Color,
    val surfaceOverlay: Color,
    val outline: Color,
    val textPrimary: Color,
    val textSecondary: Color,
    val textTertiary: Color,
    val accent: Color,
    val onAccent: Color,
    /** Expenses. Muted coral, deliberately not alarm-red. */
    val debit: Color,
    /** Income. Muted mint. */
    val credit: Color,
    val warn: Color,
    val isDark: Boolean,
)

/**
 * Dark palette -- the default and primary theme.
 *
 * `textTertiary` is `#7F8798`, not the `#6B7285` originally specified: that
 * measured 3.73:1 against `surfaceBase`, below the 4.5:1 that §9.6 mandates for
 * normal text, while being specified as a text colour. See [LfContrast] and the
 * test that enforces it.
 */
public val LfDarkColors: LfColors = LfColors(
    surfaceBase = Color(0xFF15171C),
    surfaceRaised = Color(0xFF1D2027),
    surfaceOverlay = Color(0xFF252932),
    outline = Color(0xFF333846),
    textPrimary = Color(0xFFE8EAF0),
    textSecondary = Color(0xFF9AA1B4),
    textTertiary = Color(0xFF7F8798),
    accent = Color(0xFF6E8BFF),
    onAccent = Color(0xFF0B1020),
    debit = Color(0xFFFF7A85),
    credit = Color(0xFF5FD0A6),
    warn = Color(0xFFF2B457),
    isDark = true,
)

/**
 * Light mirror. Required by §9.1, and the debit/credit hues are darkened rather
 * than reused: the dark-theme coral and mint are unreadable on a light surface,
 * and an amount the user cannot read is worse than no colour coding at all.
 */
public val LfLightColors: LfColors = LfColors(
    surfaceBase = Color(0xFFF7F8FA),
    surfaceRaised = Color(0xFFFFFFFF),
    surfaceOverlay = Color(0xFFEFF1F5),
    outline = Color(0xFFD8DCE5),
    textPrimary = Color(0xFF15171C),
    textSecondary = Color(0xFF525A6B),
    textTertiary = Color(0xFF6B7285),
    accent = Color(0xFF3A5BD9),
    onAccent = Color(0xFFFFFFFF),
    debit = Color(0xFFC2374A),
    credit = Color(0xFF1F7A5A),
    warn = Color(0xFF9A6410),
    isDark = false,
)

public val LocalLfColors: androidx.compose.runtime.ProvidableCompositionLocal<LfColors> =
    staticCompositionLocalOf { LfDarkColors }

/**
 * WCAG contrast maths, used by the design-system test rather than by runtime
 * code.
 *
 * §9.6 states "Contrast AA minimum" as a requirement. A requirement nobody
 * measures is a preference, so the ratios are computed from the token values
 * and asserted -- a palette edit that drops a pair below its threshold fails
 * the build instead of shipping.
 */
public object LfContrast {

    /** WCAG AA for normal text. */
    public const val AA_NORMAL: Double = 4.5

    /** WCAG AA for large text (>= 18.66px bold / >= 24px regular). */
    public const val AA_LARGE: Double = 3.0

    public fun ratio(foreground: Color, background: Color): Double {
        val lighter = maxOf(relativeLuminance(foreground), relativeLuminance(background))
        val darker = minOf(relativeLuminance(foreground), relativeLuminance(background))
        return (lighter + OFFSET) / (darker + OFFSET)
    }

    private fun relativeLuminance(color: Color): Double =
        RED_WEIGHT * linearize(color.red) +
            GREEN_WEIGHT * linearize(color.green) +
            BLUE_WEIGHT * linearize(color.blue)

    private fun linearize(channel: Float): Double {
        val value = channel.toDouble()
        return if (value <= LINEAR_THRESHOLD) {
            value / LINEAR_DIVISOR
        } else {
            Math.pow((value + GAMMA_OFFSET) / GAMMA_DIVISOR, GAMMA_EXPONENT)
        }
    }

    private const val OFFSET = 0.05
    private const val RED_WEIGHT = 0.2126
    private const val GREEN_WEIGHT = 0.7152
    private const val BLUE_WEIGHT = 0.0722
    private const val LINEAR_THRESHOLD = 0.03928
    private const val LINEAR_DIVISOR = 12.92
    private const val GAMMA_OFFSET = 0.055
    private const val GAMMA_DIVISOR = 1.055
    private const val GAMMA_EXPONENT = 2.4
}
