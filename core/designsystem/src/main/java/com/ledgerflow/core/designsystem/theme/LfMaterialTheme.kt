package com.ledgerflow.core.designsystem.theme

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
 *   text field) and decorative dividers with `outlineVariant`. `border.subtle`
 *   is the hairline, so it maps to the latter; the former gets
 *   [LfColors.textTertiary], which is what makes an off switch visible.
 * - **`surfaceTint` is transparent.** Material tints a raised surface with the
 *   primary colour; LedgerFlow separates surfaces with hairlines, not tints
 *   (`CLAUDE.md` §5).
 */
internal fun lfColorScheme(colors: LfColors): ColorScheme {
    val highest = lerp(colors.surface.overlay, colors.border.subtle, HIGHEST_CONTAINER_MIX)
    // Material's containers go from the page up: lowest, low, container, high,
    // highest. In the dark palette the raised surface is lighter than the page;
    // in the light palette it is white, lighter than the page too, so the order
    // of the two lowest steps is the other way round.
    val lowest = if (colors.isDark) colors.surface.canvas else colors.surface.raised
    val low = if (colors.isDark) colors.surface.raised else colors.surface.canvas
    val inverseAction = if (colors.isDark) LfLightColors.action.primary else LfDarkColors.action.primary

    return lightColorScheme(
        primary = colors.action.primary,
        onPrimary = colors.action.onPrimary,
        primaryContainer = colors.action.subtle,
        onPrimaryContainer = colors.text.primary,
        inversePrimary = inverseAction,
        secondary = colors.text.secondary,
        onSecondary = colors.surface.canvas,
        secondaryContainer = colors.surface.overlay,
        onSecondaryContainer = colors.text.primary,
        tertiary = colors.text.secondary,
        onTertiary = colors.surface.canvas,
        tertiaryContainer = colors.surface.overlay,
        onTertiaryContainer = colors.text.primary,
        background = colors.surface.canvas,
        onBackground = colors.text.primary,
        surface = colors.surface.raised,
        onSurface = colors.text.primary,
        surfaceVariant = colors.surface.overlay,
        onSurfaceVariant = colors.text.secondary,
        surfaceTint = Color.Transparent,
        inverseSurface = colors.surface.inverse,
        inverseOnSurface = colors.text.onInverse,
        // status.danger: the same coral as money.outgoing today, a separate role
        // so a direction can tell a failure from an expense (plan §4.2).
        error = colors.status.danger,
        onError = colors.surface.canvas,
        errorContainer = colors.status.dangerSubtle,
        onErrorContainer = colors.text.primary,
        outline = colors.border.strong,
        outlineVariant = colors.border.subtle,
        scrim = colors.surface.scrim,
        surfaceBright = colors.surface.overlay,
        surfaceContainer = low,
        surfaceContainerHigh = colors.surface.overlay,
        surfaceContainerHighest = highest,
        surfaceContainerLow = low,
        surfaceContainerLowest = lowest,
        surfaceDim = colors.surface.canvas,
        primaryFixed = colors.action.subtle,
        primaryFixedDim = colors.action.primary,
        onPrimaryFixed = colors.text.primary,
        onPrimaryFixedVariant = colors.text.secondary,
        secondaryFixed = colors.surface.overlay,
        secondaryFixedDim = colors.surface.raised,
        onSecondaryFixed = colors.text.primary,
        onSecondaryFixedVariant = colors.text.secondary,
        tertiaryFixed = colors.surface.overlay,
        tertiaryFixedDim = colors.surface.raised,
        onTertiaryFixed = colors.text.primary,
        onTertiaryFixedVariant = colors.text.secondary,
    )
}

/**
 * Material's shapes from the shape roles, so pickers and sheets stop using
 * Material's 28 dp. Material's two small slots (menus, text fields) take
 * today's 8 dp, which has no role: see [LfLegacySmallCorner].
 */
internal fun lfShapes(shapes: LfShapeRoles): Shapes = Shapes(
    extraSmall = LfLegacySmallCorner,
    small = LfLegacySmallCorner,
    medium = shapes.control,
    large = shapes.dialog,
    extraLarge = shapes.dialog,
)

internal val LfDarkColorScheme: ColorScheme = lfColorScheme(LfDarkColors)
internal val LfLightColorScheme: ColorScheme = lfColorScheme(LfLightColors)
internal val LfMaterialShapes: Shapes = lfShapes(LfDefaultShapeRoles)

/** `surfaceContainerHighest` sits halfway from the overlay surface to the hairline. */
private const val HIGHEST_CONTAINER_MIX = 0.5f
