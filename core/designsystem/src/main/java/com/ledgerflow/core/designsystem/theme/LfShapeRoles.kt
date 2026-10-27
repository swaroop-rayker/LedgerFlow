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
)

internal val LfDefaultShapeRoles: LfShapeRoles = LfShapeRoles(
    container = RoundedCornerShape(LfScale.Radius16),
    row = RoundedCornerShape(LfScale.Radius16),
    control = RoundedCornerShape(LfScale.Radius16),
    dialog = RoundedCornerShape(LfScale.Radius24),
    full = RoundedCornerShape(percent = FULLY_ROUND_PERCENT),
)

/**
 * The 8 dp corner that text fields, chips, segments and chart cells use today.
 *
 * Not a role: Night Vault has no 8 dp shape. In step F3 fields take `control`,
 * chips and segments take `full`, and chart cells take the tile radius. Until
 * then their component tokens read this, so B3 moves nothing on screen.
 */
internal val LfLegacySmallCorner: CornerBasedShape = RoundedCornerShape(LfScale.Radius8)

private const val FULLY_ROUND_PERCENT = 50

public val LocalLfShapeRoles: androidx.compose.runtime.ProvidableCompositionLocal<LfShapeRoles> =
    staticCompositionLocalOf { LfDefaultShapeRoles }
