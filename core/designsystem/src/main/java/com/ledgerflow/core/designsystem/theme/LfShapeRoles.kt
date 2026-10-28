package com.ledgerflow.core.designsystem.theme

import androidx.compose.foundation.shape.CornerBasedShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import com.ledgerflow.core.designsystem.theme.primitive.LfScale

/**
 * Corner shapes by job (DESIGN_SYSTEM_PLAN §4.2), read as `LfTheme.shapes.container`.
 *
 * Step B3 set each role to the radius its main users already had. Step D3
 * moved every standalone row onto [row] and gave it Night Vault's 16 dp
 * early (owner, 2026-10-26), so rows change once rather than twice: they had
 * been 24 dp (the Ledger and the bin) and 8 dp (the Inbox, the Ledger's
 * unsaved rows, Organise). The other roles already hold Night Vault's values.
 */
@Immutable
public class LfShapeRoles internal constructor(
    /** Cards, grouped panels and sections. */
    public val container: CornerBasedShape,
    /** A list row that stands on its own: the Ledger, Inbox, bin and Organise rows. */
    public val row: CornerBasedShape,
    /** Buttons and other controls. */
    public val control: CornerBasedShape,
    /** Dialogs and bottom sheets. */
    public val dialog: CornerBasedShape,
    /** Fully round: pills, the + button, dots. */
    public val full: CornerBasedShape,
    /** A 40 dp icon tile (step F3): 12, since 16 on a square that small reads as a circle. */
    public val tile: CornerBasedShape,
)

internal val LfDefaultShapeRoles: LfShapeRoles = LfShapeRoles(
    container = RoundedCornerShape(LfScale.Radius16),
    row = RoundedCornerShape(LfScale.Radius16),
    control = RoundedCornerShape(LfScale.Radius16),
    dialog = RoundedCornerShape(LfScale.Radius24),
    full = RoundedCornerShape(percent = FULLY_ROUND_PERCENT),
    tile = RoundedCornerShape(LfScale.Radius12),
)

/**
 * The 8 dp corner the charts' cells still use.
 *
 * Not a role: Night Vault has no 8 dp shape. Fields, chips, segments, the
 * snackbar and Material's two small slots left it at step F3 (`control`, and
 * `full` for chips and segments). The heatmap's cells keep it until F6 restyles
 * the charts: at their size the 12 dp tile radius would turn a square into a
 * dot.
 */
internal val LfLegacySmallCorner: CornerBasedShape = RoundedCornerShape(LfScale.Radius8)

private const val FULLY_ROUND_PERCENT = 50

public val LocalLfShapeRoles: androidx.compose.runtime.ProvidableCompositionLocal<LfShapeRoles> =
    staticCompositionLocalOf { LfDefaultShapeRoles }
