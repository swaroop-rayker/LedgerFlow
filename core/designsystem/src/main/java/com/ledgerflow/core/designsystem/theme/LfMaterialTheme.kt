package com.ledgerflow.core.designsystem.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp

/**
 * Material's whole colour scheme, built from [LfColors] (DESIGN_SYSTEM_PLAN
 * step A2).
 *
 * `LfTheme` used to set ten of Material's 48 colour roles and leave the rest at
 * Material's own purple baseline. Every `Lf*` component passes its colours
 * explicitly, so the gap only showed where a Material widget drew with a role
 * nobody had set: the date pickers and the Add sheet came out lavender, and an
 * unchecked `Switch` drew its thumb in the hairline colour on Material's own
 * track, so it all but vanished in the dark theme (BUG63). Now every role is
 * mapped, and `LfMaterialThemeTest` fails if Material adds one that is not.
 *
 * **Today's palette, unchanged.** Nothing here is a new colour: each role is
 * one of the twelve [LfColors] tokens, or a blend of two of them for the few
 * "container" roles Material uses as a tinted fill.
 *
 * Two mappings are worth knowing:
 * - **`outline` is the medium grey, `outlineVariant` is the hairline.**
 *   Material draws control edges with `outline` (a switch's thumb and border, a
 *   text field) and decorative dividers with `outlineVariant`. [LfColors.outline]
 *   is a hairline, so it maps to the latter; the former gets
 *   [LfColors.textTertiary], which is what makes an off switch visible.
 * - **`surfaceTint` is transparent.** Material tints a raised surface with the
 *   primary colour; LedgerFlow separates surfaces with hairlines, not tints
 *   (`CLAUDE.md` §5).
 */
internal fun lfColorScheme(colors: LfColors): ColorScheme {
    val primaryContainer = lerp(colors.surfaceRaised, colors.accent, CONTAINER_TINT)
    val errorContainer = lerp(colors.surfaceRaised, colors.debit, CONTAINER_TINT)
    val highest = lerp(colors.surfaceOverlay, colors.outline, HIGHEST_CONTAINER_MIX)
    // Material's containers go from the page up: lowest, low, container, high,
    // highest. In the dark palette the raised surface is lighter than the page;
    // in the light palette it is white, lighter than the page too, so the order
    // of the two lowest steps is the other way round.
    val lowest = if (colors.isDark) colors.surfaceBase else colors.surfaceRaised
    val low = if (colors.isDark) colors.surfaceRaised else colors.surfaceBase
    val inverseAccent = if (colors.isDark) LfLightColors.accent else LfDarkColors.accent

    // Every role is given, so the light and dark builders' different defaults
    // never apply; one builder, named arguments, the compiler checks each name.
    return lightColorScheme(
        primary = colors.accent,
        onPrimary = colors.onAccent,
        primaryContainer = primaryContainer,
        onPrimaryContainer = colors.textPrimary,
        inversePrimary = inverseAccent,
        secondary = colors.textSecondary,
        onSecondary = colors.surfaceBase,
        secondaryContainer = colors.surfaceOverlay,
        onSecondaryContainer = colors.textPrimary,
        tertiary = colors.textSecondary,
        onTertiary = colors.surfaceBase,
        tertiaryContainer = colors.surfaceOverlay,
        onTertiaryContainer = colors.textPrimary,
        background = colors.surfaceBase,
        onBackground = colors.textPrimary,
        surface = colors.surfaceRaised,
        onSurface = colors.textPrimary,
        surfaceVariant = colors.surfaceOverlay,
        onSurfaceVariant = colors.textSecondary,
        surfaceTint = Color.Transparent,
        inverseSurface = colors.textPrimary,
        inverseOnSurface = colors.surfaceBase,
        // Kept on the money-out colour, as before this step: splitting "danger"
        // from "money out" is the semantic layer's job (plan §4.2, step B1).
        error = colors.debit,
        onError = colors.surfaceBase,
        errorContainer = errorContainer,
        onErrorContainer = colors.textPrimary,
        outline = colors.textTertiary,
        outlineVariant = colors.outline,
        scrim = Color.Black,
        surfaceBright = colors.surfaceOverlay,
        surfaceContainer = low,
        surfaceContainerHigh = colors.surfaceOverlay,
        surfaceContainerHighest = highest,
        surfaceContainerLow = low,
        surfaceContainerLowest = lowest,
        surfaceDim = colors.surfaceBase,
        primaryFixed = primaryContainer,
        primaryFixedDim = colors.accent,
        onPrimaryFixed = colors.textPrimary,
        onPrimaryFixedVariant = colors.textSecondary,
        secondaryFixed = colors.surfaceOverlay,
        secondaryFixedDim = colors.surfaceRaised,
        onSecondaryFixed = colors.textPrimary,
        onSecondaryFixedVariant = colors.textSecondary,
        tertiaryFixed = colors.surfaceOverlay,
        tertiaryFixedDim = colors.surfaceRaised,
        onTertiaryFixed = colors.textPrimary,
        onTertiaryFixedVariant = colors.textSecondary,
    )
}

/** Material's shapes from the radius tokens, so pickers and sheets stop using Material's 28 dp. */
internal fun lfShapes(spacing: LfSpacing): Shapes = Shapes(
    extraSmall = RoundedCornerShape(spacing.cornerSmall),
    small = RoundedCornerShape(spacing.cornerSmall),
    medium = RoundedCornerShape(spacing.cornerMedium),
    large = RoundedCornerShape(spacing.cornerLarge),
    extraLarge = RoundedCornerShape(spacing.cornerLarge),
)

internal val LfDarkColorScheme: ColorScheme = lfColorScheme(LfDarkColors)
internal val LfLightColorScheme: ColorScheme = lfColorScheme(LfLightColors)
internal val LfMaterialShapes: Shapes = lfShapes(LfSpacing())

/** How much of the accent (or debit) colour a Material "container" fill carries. */
private const val CONTAINER_TINT = 0.24f

/** `surfaceContainerHighest` sits halfway from the overlay surface to the hairline. */
private const val HIGHEST_CONTAINER_MIX = 0.5f
