package com.ledgerflow.core.designsystem.theme

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import com.ledgerflow.core.designsystem.theme.primitive.LfPalette

/**
 * LedgerFlow's semantic colour roles (SPEC.md §9.1; DESIGN_SYSTEM_PLAN §4.2).
 *
 * Grouped by job: [surface], [text], [border], [action], [money], [status],
 * [chart]. A screen asks for a meaning (`colors.money.outgoing`), and the theme
 * decides what that looks like in each theme and visual direction. The values
 * come from the primitive palette (`theme.primitive.LfPalette`), which no
 * module outside this one can see.
 *
 * Held separately from Material's `ColorScheme` because the roles that carry
 * the most meaning here -- [money] and [status] -- have no Material slot.
 * Mapping "money leaving your account" onto `error` would be both wrong and
 * unreadable: an expense is not an error. For the same reason [money] and
 * [status] are separate groups even where today's values coincide
 * (`status.danger` is the money-out coral until a direction separates them):
 * a screen that means "destructive" must never say "money out", or the two can
 * never be told apart later.
 *
 * **Semi-dark by default**, not OLED black: a warm-neutral dark that is easy on
 * the eyes at night, which is when people reconcile their spending.
 *
 * The flat names this class had before step B1 (`surfaceBase`, `debit`, …)
 * remain as deprecated aliases until every screen has moved (step G).
 */
@Immutable
public class LfColors internal constructor(
    public val surface: LfSurfaceColors,
    public val text: LfTextColors,
    public val border: LfBorderColors,
    public val action: LfActionColors,
    public val money: LfMoneyColors,
    public val status: LfStatusColors,
    public val chart: LfChartColors,
    public val isDark: Boolean,
) {
    @Deprecated("Use surface.canvas (DESIGN_SYSTEM_PLAN §4.2)", ReplaceWith("surface.canvas"))
    public val surfaceBase: Color get() = surface.canvas

    @Deprecated("Use surface.raised (DESIGN_SYSTEM_PLAN §4.2)", ReplaceWith("surface.raised"))
    public val surfaceRaised: Color get() = surface.raised

    @Deprecated("Use surface.overlay (DESIGN_SYSTEM_PLAN §4.2)", ReplaceWith("surface.overlay"))
    public val surfaceOverlay: Color get() = surface.overlay

    @Deprecated("Use border.subtle (DESIGN_SYSTEM_PLAN §4.2)", ReplaceWith("border.subtle"))
    public val outline: Color get() = border.subtle

    @Deprecated("Use text.primary (DESIGN_SYSTEM_PLAN §4.2)", ReplaceWith("text.primary"))
    public val textPrimary: Color get() = text.primary

    @Deprecated("Use text.secondary (DESIGN_SYSTEM_PLAN §4.2)", ReplaceWith("text.secondary"))
    public val textSecondary: Color get() = text.secondary

    @Deprecated("Use text.muted (DESIGN_SYSTEM_PLAN §4.2)", ReplaceWith("text.muted"))
    public val textTertiary: Color get() = text.muted

    @Deprecated("Use action.primary (DESIGN_SYSTEM_PLAN §4.2)", ReplaceWith("action.primary"))
    public val accent: Color get() = action.primary

    @Deprecated("Use action.onPrimary (DESIGN_SYSTEM_PLAN §4.2)", ReplaceWith("action.onPrimary"))
    public val onAccent: Color get() = action.onPrimary

    @Deprecated(
        "Use money.outgoing for an amount, status.danger for a destructive action (DESIGN_SYSTEM_PLAN §4.2)",
        ReplaceWith("money.outgoing"),
    )
    public val debit: Color get() = money.outgoing

    @Deprecated("Use money.incoming (DESIGN_SYSTEM_PLAN §4.2)", ReplaceWith("money.incoming"))
    public val credit: Color get() = money.incoming

    @Deprecated("Use status.warning (DESIGN_SYSTEM_PLAN §4.2)", ReplaceWith("status.warning"))
    public val warn: Color get() = status.warning
}

/** What things sit on. [canvas] is the page; [raised] cards and rows; [overlay] dialogs, sheets, menus. */
@Immutable
public class LfSurfaceColors internal constructor(
    public val canvas: Color,
    public val raised: Color,
    public val overlay: Color,
    /** Recessed wells (an input's inside, a quoted message). Today the canvas itself. */
    public val sunken: Color,
    /** The reversed surface of a snackbar or tooltip. */
    public val inverse: Color,
    public val scrim: Color,
)

/**
 * Text and icon colours. [muted] is held to 4.5:1 against the canvas (SPEC §9.6,
 * `ContrastTest`); [disabled] is a control that cannot be tapped.
 */
@Immutable
public class LfTextColors internal constructor(
    public val primary: Color,
    public val secondary: Color,
    public val muted: Color,
    public val disabled: Color,
    /** On an [LfActionColors.primary] fill. */
    public val onAction: Color,
    /** On an [LfSurfaceColors.inverse] fill. */
    public val onInverse: Color,
)

/** [subtle] is the hairline (`CLAUDE.md` §5); [strong] a control's edge; [focus] the focused field. */
@Immutable
public class LfBorderColors internal constructor(
    public val subtle: Color,
    public val strong: Color,
    public val focus: Color,
)

/** The main action and the current selection. [subtle] is a faint fill of it. */
@Immutable
public class LfActionColors internal constructor(
    public val primary: Color,
    public val onPrimary: Color,
    public val pressed: Color,
    public val subtle: Color,
)

/**
 * The two books (Law 2): money leaving and money arriving, never one colour for
 * both and never borrowed for anything that is not an amount.
 */
@Immutable
public class LfMoneyColors internal constructor(
    /** Expenses. Muted coral, deliberately not alarm-red. */
    public val outgoing: Color,
    public val outgoingSubtle: Color,
    /** Income. Muted mint. */
    public val incoming: Color,
    public val incomingSubtle: Color,
)

/** Feedback. [danger] is for destructive actions and failures, never an expense. */
@Immutable
public class LfStatusColors internal constructor(
    public val warning: Color,
    public val warningSubtle: Color,
    public val danger: Color,
    public val dangerSubtle: Color,
    public val success: Color,
    public val successSubtle: Color,
    public val info: Color,
    public val infoSubtle: Color,
)

/** Chart furniture. Category colours stay in `LfCategoryPalette`, which keeps its own contrast rule. */
@Immutable
public class LfChartColors internal constructor(
    public val grid: Color,
    public val axisLabel: Color,
    public val track: Color,
)

/**
 * The twelve colours each theme had before step B1: the only inputs the role
 * mapping reads, so every role is one of them or a blend of two
 * (DESIGN_SYSTEM_PLAN §4.2). One parameter per colour of that palette, hence
 * twelve.
 */
@Suppress("LongParameterList")
private class Palette(
    val canvas: Color,
    val raised: Color,
    val overlay: Color,
    val hairline: Color,
    val textPrimary: Color,
    val textSecondary: Color,
    val textMuted: Color,
    val accent: Color,
    val onAccent: Color,
    val debit: Color,
    val credit: Color,
    val warn: Color,
)

private fun rolesFrom(p: Palette, isDark: Boolean): LfColors {
    fun subtle(of: Color) = lerp(p.raised, of, SUBTLE_TINT)
    return LfColors(
        surface = LfSurfaceColors(
            canvas = p.canvas,
            raised = p.raised,
            overlay = p.overlay,
            sunken = p.canvas,
            inverse = p.textPrimary,
            scrim = LfPalette.Black,
        ),
        text = LfTextColors(
            primary = p.textPrimary,
            secondary = p.textSecondary,
            muted = p.textMuted,
            // What LfButton's disabled text label has always used.
            disabled = p.textMuted,
            onAction = p.onAccent,
            onInverse = p.canvas,
        ),
        border = LfBorderColors(subtle = p.hairline, strong = p.textMuted, focus = p.accent),
        action = LfActionColors(
            primary = p.accent,
            onPrimary = p.onAccent,
            pressed = p.accent,
            subtle = subtle(p.accent),
        ),
        money = LfMoneyColors(
            outgoing = p.debit,
            outgoingSubtle = subtle(p.debit),
            incoming = p.credit,
            incomingSubtle = subtle(p.credit),
        ),
        status = LfStatusColors(
            warning = p.warn,
            warningSubtle = subtle(p.warn),
            // Today's values: errors have always been the coral. A direction
            // gives danger its own colour (DESIGN_SYSTEM_PLAN step F2).
            danger = p.debit,
            dangerSubtle = subtle(p.debit),
            success = p.credit,
            successSubtle = subtle(p.credit),
            info = p.accent,
            infoSubtle = subtle(p.accent),
        ),
        // track: the empty part of a bar or a heatmap's empty day, as four of the
        // five charts draw it (the donut's ring keeps the hairline, its own token).
        chart = LfChartColors(grid = p.hairline, axisLabel = p.textMuted, track = p.overlay),
        isDark = isDark,
    )
}

/** How much of a colour its "subtle" fill carries over the raised surface. */
private const val SUBTLE_TINT = 0.24f

/**
 * Dark palette -- the default and primary theme.
 *
 * `text.muted` is `#7F8798`, not the `#6B7285` originally specified: that
 * measured 3.73:1 against the canvas, below the 4.5:1 that §9.6 mandates for
 * normal text, while being specified as a text colour. See [LfContrast] and the
 * test that enforces it.
 */
public val LfDarkColors: LfColors = rolesFrom(
    Palette(
        canvas = LfPalette.Graphite950,
        raised = LfPalette.Graphite900,
        overlay = LfPalette.Graphite850,
        hairline = LfPalette.Graphite700,
        textPrimary = LfPalette.Mist100,
        textSecondary = LfPalette.Mist400,
        textMuted = LfPalette.Mist500,
        accent = LfPalette.Periwinkle400,
        onAccent = LfPalette.Navy975,
        debit = LfPalette.Coral400,
        credit = LfPalette.Mint400,
        warn = LfPalette.Amber400,
    ),
    isDark = true,
)

/**
 * Light mirror. Required by §9.1, and the money hues are darkened rather than
 * reused: the dark-theme coral and mint are unreadable on a light surface, and
 * an amount the user cannot read is worse than no colour coding at all.
 */
public val LfLightColors: LfColors = rolesFrom(
    Palette(
        canvas = LfPalette.Paper50,
        raised = LfPalette.White,
        overlay = LfPalette.Paper100,
        hairline = LfPalette.Paper300,
        textPrimary = LfPalette.Graphite950,
        textSecondary = LfPalette.Slate600,
        textMuted = LfPalette.Slate500,
        accent = LfPalette.Indigo600,
        onAccent = LfPalette.White,
        debit = LfPalette.Coral700,
        credit = LfPalette.Mint700,
        warn = LfPalette.Amber800,
    ),
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
