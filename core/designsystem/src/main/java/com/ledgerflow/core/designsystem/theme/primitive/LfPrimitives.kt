package com.ledgerflow.core.designsystem.theme.primitive

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/*
 * The primitive token layer (DESIGN_SYSTEM_PLAN §4.1): raw values with neutral
 * names, saying what a value *is* and never where it is used.
 *
 * Everything here is `internal` to `:core:designsystem`. A feature module
 * cannot compile against a primitive, so "screens never reference raw values"
 * is enforced by Kotlin rather than by review. Screens read the semantic roles
 * (`LfTheme.colors.money.outgoing`, `LfTheme.space.screenEdge`, …), and the
 * roles read these.
 *
 * A visual direction is mostly a new set of primitives plus new role
 * mappings; nothing outside this package and the role builders has to change
 * for it. Step B1 moved the previous values here unchanged; step F2 replaced
 * the palette with Night Vault's (ADR-0031).
 */

/**
 * Night Vault's palette, both themes (ADR-0031, design-system step F2). Names
 * give hue and depth, not a job.
 *
 * Every value the canvas's style sheet draws is taken from it. Five it does not
 * draw were chosen inside the contrast gate (`ContrastTest`,
 * `NoticeContrastTest`, `Bug63_OffSwitchIsVisibleTest`), with the owner's
 * approval on 2026-10-28: the light overlay, the faint text in both themes, the
 * light pink, and the control edge in both themes. The canvas drew control
 * edges at `#3A3D44`, about 1.6:1, which is too faint for an off switch's thumb
 * (BUG63); the edge here is the lightest value that keeps 3:1.
 */
internal object LfPalette {
    // Dark theme (the default; SPEC §9.1).
    val Graphite950: Color = Color(0xFF121316)
    val Graphite900: Color = Color(0xFF1A1B1F)
    val Graphite850: Color = Color(0xFF222429)
    val Graphite800: Color = Color(0xFF2B2D33)
    val Stone500: Color = Color(0xFF75716A)
    val Bone100: Color = Color(0xFFECE8E0)
    val Bone400: Color = Color(0xFFA39F96)
    val Bone500: Color = Color(0xFF908C84)
    val Coral300: Color = Color(0xFFFF8A80)
    val Mint300: Color = Color(0xFF6FD6A8)
    val Amber400: Color = Color(0xFFF2B457)
    val Pink300: Color = Color(0xFFF49AC1)

    // Light theme.
    val Paper50: Color = Color(0xFFF4F3F0)
    val White: Color = Color(0xFFFFFFFF)
    val Paper100: Color = Color(0xFFEEECE8)
    val Paper300: Color = Color(0xFFE3E0D9)
    val Stone600: Color = Color(0xFF837F77)
    val Ink950: Color = Color(0xFF17181B)
    val Ink600: Color = Color(0xFF55534D)
    val Ink500: Color = Color(0xFF6A675F)
    val Coral800: Color = Color(0xFFB3362F)
    val Mint800: Color = Color(0xFF1D7350)
    val Amber800: Color = Color(0xFF8F5B00)
    val Magenta700: Color = Color(0xFFA8336F)

    // Both themes.
    val Black: Color = Color(0xFF000000)
}

/** Today's spacing, stroke and motion steps. */
internal object LfScale {
    val Space4 = 4.dp
    val Space8 = 8.dp
    val Space16 = 16.dp
    val Space24 = 24.dp
    val Space32 = 32.dp
    val Space48 = 48.dp

    val Radius8 = 8.dp
    val Radius16 = 16.dp
    val Radius24 = 24.dp

    val Stroke1 = 1.dp
    val Stroke2 = 2.dp

    const val Duration60 = 60
    const val Duration120 = 120
    const val Duration240 = 240
    const val Duration400 = 400
    val Slide30 = 30.dp
}

/** Today's type sizes and line heights (SPEC §9.2), in pairs. */
internal object LfTypeScale {
    val Size34 = 34.sp
    val Line40 = 40.sp
    val Size28 = 28.sp
    val Line32 = 32.sp
    val Size22 = 22.sp
    val Line28 = 28.sp
    val Size18 = 18.sp
    val Line24 = 24.sp
    val Line22 = 22.sp
    val Size16 = 16.sp
    val Size14 = 14.sp
    val Line20 = 20.sp
    val Size12 = 12.sp
    val Line16 = 16.sp
}
