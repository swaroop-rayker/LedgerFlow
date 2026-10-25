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
 * A visual direction (Night Vault, step F2) is mostly a new set of primitives
 * plus new role mappings; nothing outside this package and the role builders
 * has to change for it.
 *
 * Step B1 moved today's values here unchanged: every golden is
 * byte-identical before and after.
 */

/** Today's palette, both themes. Names give hue and depth, not a job. */
internal object LfPalette {
    // Dark theme (the default; SPEC §9.1).
    val Graphite950: Color = Color(0xFF15171C)
    val Graphite900: Color = Color(0xFF1D2027)
    val Graphite850: Color = Color(0xFF252932)
    val Graphite700: Color = Color(0xFF333846)
    val Mist100: Color = Color(0xFFE8EAF0)
    val Mist400: Color = Color(0xFF9AA1B4)
    val Mist500: Color = Color(0xFF7F8798)
    val Periwinkle400: Color = Color(0xFF6E8BFF)
    val Navy975: Color = Color(0xFF0B1020)
    val Coral400: Color = Color(0xFFFF7A85)
    val Mint400: Color = Color(0xFF5FD0A6)
    val Amber400: Color = Color(0xFFF2B457)

    // Light theme.
    val Paper50: Color = Color(0xFFF7F8FA)
    val White: Color = Color(0xFFFFFFFF)
    val Paper100: Color = Color(0xFFEFF1F5)
    val Paper300: Color = Color(0xFFD8DCE5)
    val Slate600: Color = Color(0xFF525A6B)
    val Slate500: Color = Color(0xFF6B7285)
    val Indigo600: Color = Color(0xFF3A5BD9)
    val Coral700: Color = Color(0xFFC2374A)
    val Mint700: Color = Color(0xFF1F7A5A)
    val Amber800: Color = Color(0xFF9A6410)

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
