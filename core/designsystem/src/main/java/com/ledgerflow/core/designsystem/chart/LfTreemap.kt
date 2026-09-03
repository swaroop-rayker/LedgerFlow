package com.ledgerflow.core.designsystem.chart

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.ledgerflow.core.designsystem.theme.LfTheme
import kotlin.math.abs

/**
 * A3's optional treemap (`SPEC.md` §5.6).
 *
 * **Optional, and it stays the secondary view.** `docs/DATAVIZ-PLAN.md` marks
 * the nested list as A3's primary form; a treemap answers "which of these is
 * biggest" at a glance and is worse than a list at everything else, so it is
 * offered beside the list rather than instead of it.
 *
 * **Labels are drawn only where they fit.** A tile too small for its own name
 * gets none — the alternative is text spilling across neighbours, which makes
 * the *large* tiles unreadable to label a small one nobody can tap anyway. The
 * measurement is real ([androidx.compose.ui.text.TextMeasurer]), so it degrades
 * correctly at font scale 2.0 instead of being right at 1.0.
 *
 * A consequence worth knowing before reading the goldens as a bug: it is the
 * *label* that decides, not the tile. A long name can vanish from a larger tile
 * while a short one survives in a smaller one beside it -- "Transport" is wider
 * than "Utilities" in the same 78px box. That is the correct outcome; the list
 * beside the chart carries every name.
 *
 * **Every tile is tappable and the whole chart is described** (§9.6). A Canvas
 * has no child nodes to hang per-tile semantics on, so the description names
 * the largest few and the list beside it carries the rest.
 */
@Composable
public fun LfTreemap(
    tiles: List<LfTreemapDatum>,
    modifier: Modifier = Modifier,
    height: Dp = DefaultHeight,
    onTileClick: (String) -> Unit = {},
) {
    val measurer = rememberTextMeasurer()
    val colors = LfTheme.colors
    val labelStyle = LfTheme.typography.label

    val layout = remember(tiles) {
        LfTreemapLayout.layout(tiles.map { it.id to it.value })
    }
    val byId = remember(tiles) { tiles.associateBy { it.id } }

    val description = if (tiles.isEmpty()) {
        "Treemap, no data"
    } else {
        tiles.sortedByDescending { it.value }.take(DESCRIBED_TILES)
            .joinToString(prefix = "Treemap. Largest: ") { it.label }
    }

    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .height(height)
            .semantics { contentDescription = description }
            .pointerInput(layout) {
                detectTapGestures { offset ->
                    val hit = layout.firstOrNull { tile ->
                        offset.x >= tile.x * size.width &&
                            offset.x <= (tile.x + tile.width) * size.width &&
                            offset.y >= tile.y * size.height &&
                            offset.y <= (tile.y + tile.height) * size.height
                    }
                    if (hit != null) onTileClick(hit.id)
                }
            },
    ) {
        layout.forEach { tile ->
            val datum = byId[tile.id] ?: return@forEach
            val topLeft = Offset(tile.x * size.width, tile.y * size.height)
            val tileSize = Size(tile.width * size.width, tile.height * size.height)

            drawRect(color = datum.color, topLeft = topLeft, size = tileSize)
            // A hairline gutter in the surface colour rather than a stroke:
            // strokes straddle the edge and would eat into the neighbour's
            // area, which is the one thing a treemap must not misrepresent.
            drawRect(
                color = colors.surfaceBase,
                topLeft = topLeft,
                size = Size(tileSize.width, GUTTER_PX),
            )
            drawRect(
                color = colors.surfaceBase,
                topLeft = topLeft,
                size = Size(GUTTER_PX, tileSize.height),
            )

            drawTileLabel(
                label = datum.label,
                topLeft = topLeft,
                size = tileSize,
                measurer = measurer,
                style = labelStyle.copy(
                    color = readableOn(datum.color, colors.surfaceBase, colors.textPrimary),
                ),
            )
        }
    }
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawTileLabel(
    label: String,
    topLeft: Offset,
    size: Size,
    measurer: androidx.compose.ui.text.TextMeasurer,
    style: TextStyle,
) {
    val layout = measurer.measure(label, style)
    val fits = layout.size.width + LABEL_PADDING_PX * 2 <= size.width &&
        layout.size.height + LABEL_PADDING_PX * 2 <= size.height
    if (!fits) return

    drawText(
        textLayoutResult = layout,
        topLeft = Offset(topLeft.x + LABEL_PADDING_PX, topLeft.y + LABEL_PADDING_PX),
    )
}

/**
 * The more legible of two theme colours on [background].
 *
 * The palette is pastel by design, so a single fixed label colour is wrong on
 * half of it: white disappears on the pale greens and a dark label disappears
 * on the saturated ones. Picking by luminance is the only rule that holds for a
 * colour the theme may move underneath us.
 *
 * The two candidates are passed in rather than hardcoded because `LfTheme` bans
 * literal colours, and because `surfaceBase` and `textPrimary` are opposite
 * ends of *whichever* theme is active — so this works in dark mode without a
 * second branch.
 */
private fun readableOn(background: Color, a: Color, b: Color): Color {
    val target = background.luminance()
    return if (abs(a.luminance() - target) >= abs(b.luminance() - target)) a else b
}

private const val GUTTER_PX = 2f
private const val LABEL_PADDING_PX = 6f
private const val DESCRIBED_TILES = 3

/**
 * Measured against the donut's 104dp beside it, not chosen for comfort.
 *
 * A treemap packs in two dimensions and needs more area than a ring to keep
 * small tiles tappable, but `CLAUDE.md`'s brief is explicit that the graphic
 * orients and the list is the content. On device at 200dp a single-category
 * treemap filled a quarter of the viewport above the figures the user came for.
 */
private val DefaultHeight: Dp = 160.dp
