package com.ledgerflow.core.designsystem.component

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntSize
import com.ledgerflow.core.designsystem.theme.LfTheme
import kotlin.math.roundToInt

/**
 * An icon-only control (SPEC.md §9.4).
 *
 * [contentDescription] is **required**, not nullable. §9.6 asks for a content
 * description on every icon-only control, and an optional parameter is a
 * requirement that gets forgotten at the one call site nobody reviewed — an
 * icon-only button with no description is a button a screen-reader user cannot
 * identify at all, which is worse than a mislabelled one.
 *
 * The touch target is the full 48dp minimum even though the glyph is smaller.
 * A tap target sized to the ink is the most common accessibility defect in a
 * compact row, and this is used inside cards where space pressure is real.
 */
@Composable
public fun LfIconButton(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    tint: Color = LfIconButtonTokens.tint,
) {
    LfIconButton(
        icon = rememberVectorPainter(icon),
        contentDescription = contentDescription,
        onClick = onClick,
        modifier = modifier,
        tint = tint,
    )
}

/**
 * The same control over a [Painter] the caller already holds.
 *
 * **For lists.** Every `rememberVectorPainter` builds the vector's node tree
 * during composition and rasterises it into a bitmap of its own, which the
 * render thread then uploads. In a list that is paid once per row as rows
 * scroll in: measured 2026-09-29 on the Ledger at 120 Hz, a 48×48 upload sat in
 * most of the frames that missed their deadline. A list remembers one painter
 * and hands it to every row, so it is built and uploaded once.
 *
 * **One node** (PERF-120HZ-PLAN §2.3). This was a 48dp `Box` around Material's
 * `IconButton` around an `Icon`: five layout nodes and a lazily-built ripple per
 * Ledger row, composed inside the frame that scrolls the row in. It is now one
 * leaf that is clickable and draws its glyph, at the pixels the three drew:
 * the glyph is centred in Material's 40dp state layer, which is centred in the
 * 48dp target, and each centring rounds on its own -- at 2.625x that is
 * 10.5 → 11 and then 31.5 → 32, a pixel away from centring once. The press is
 * the same 40dp circle, as an unbounded ripple from the centre.
 */
@Composable
public fun LfIconButton(
    icon: Painter,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    tint: Color = LfIconButtonTokens.tint,
) {
    val spacing = LfTheme.spacing
    // Material 3's icon-button state layer: 40dp inside the 48dp target.
    val stateLayer = spacing.minTouchTarget - LfIconButtonTokens.stateLayerInset * 2
    val glyph = LfIconButtonTokens.glyph
    Spacer(
        modifier = modifier
            .size(spacing.minTouchTarget)
            .clickable(
                onClick = onClick,
                role = Role.Button,
                // Null, so the interaction source and the ripple are only built
                // when the control is first pressed -- not for every row drawn.
                interactionSource = null,
                indication = ripple(bounded = false, radius = stateLayer / 2),
            )
            .semantics { this.contentDescription = contentDescription }
            .drawWithCache {
                val target = IntSize(size.width.roundToInt(), size.height.roundToInt())
                val layer = IntSize(stateLayer.roundToPx(), stateLayer.roundToPx())
                val ink = IntSize(glyph.roundToPx(), glyph.roundToPx())
                val outer = Alignment.Center.align(layer, target, layoutDirection)
                val inner = Alignment.Center.align(ink, layer, layoutDirection)
                val inkSize = Size(ink.width.toFloat(), ink.height.toFloat())
                val filter = ColorFilter.tint(tint)
                onDrawBehind {
                    translate(left = (outer.x + inner.x).toFloat(), top = (outer.y + inner.y).toFloat()) {
                        with(icon) { draw(size = inkSize, colorFilter = filter) }
                    }
                }
            },
    )
}
