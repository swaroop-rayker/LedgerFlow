package com.ledgerflow.core.designsystem.component

import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.node.CompositionLocalConsumerModifierNode
import androidx.compose.ui.node.DrawModifierNode
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.node.currentValueOf
import androidx.compose.ui.node.invalidateDraw
import androidx.compose.ui.platform.InspectorInfo
import androidx.compose.ui.platform.LocalFontFamilyResolver
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.drawText
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.TextUnit
import com.ledgerflow.core.designsystem.theme.LocalLfColors
import com.ledgerflow.core.designsystem.theme.LocalLfTypography
import com.ledgerflow.core.designsystem.theme.primitive.LfScale
import kotlin.math.roundToInt

/**
 * The text measurer category initials are laid out with, one per [LfTheme]
 * root rather than one per swatch.
 *
 * A list of entries shows a handful of distinct initials over and over. A
 * measurer per row would lay "G" out again for every Groceries row that scrolls
 * in; one shared measurer lays it out once and answers every later row from its
 * cache. Null outside an [LfTheme], where each swatch makes its own.
 *
 * [LfTheme]: com.ledgerflow.core.designsystem.theme.LfTheme
 */
internal val LocalLfGlyphMeasurer: ProvidableCompositionLocal<TextMeasurer?> =
    staticCompositionLocalOf { null }

/**
 * Enough distinct initials for a long list's worth of categories, at both
 * layout directions, without evicting one another.
 */
internal const val GLYPH_MEASURER_CACHE_SIZE: Int = 32

/**
 * Draws a category swatch -- a filled circle carrying its initial -- into the
 * bounds of the node it modifies. What `LfCategoryDot` used to compose as a
 * `Box` holding a `Text`, without either node (PERF-120HZ-PLAN §2.2).
 *
 * The pixels are the old ones by construction: the circle is the same outline
 * `background(color, CircleShape)` drew, and the initial is laid out under the
 * same constraints the `Text` was given and placed where `Box`'s
 * `Alignment.Center` put it. `LfCategoryDotTest` holds it to the old pair.
 */
internal data class CategoryDotElement(
    val initial: String,
    val color: Color,
    val ink: Color,
    val tile: Boolean = false,
) : ModifierNodeElement<CategoryDotNode>() {
    override fun create(): CategoryDotNode = CategoryDotNode(initial, color, ink, tile)

    override fun update(node: CategoryDotNode) {
        node.update(initial, color, ink, tile)
    }

    override fun InspectorInfo.inspectableProperties() {
        name = "lfCategoryDot"
        properties["initial"] = initial
        properties["color"] = color
    }
}

/**
 * [tile] draws the reference samples' tinted tile (step F3, ADR-0031) instead
 * of the filled circle: a 12 dp-cornered square in the category's colour at
 * [TILE_TINT] over the raised surface, the initial in the category's colour.
 * One node either way, so a row that switches pays nothing for it.
 */
internal class CategoryDotNode(
    private var initial: String,
    private var color: Color,
    private var ink: Color,
    private var tile: Boolean,
) : Modifier.Node(), DrawModifierNode, CompositionLocalConsumerModifierNode {

    private var ownMeasurer: TextMeasurer? = null

    // What the last layout was made for. A redraw for unchanged inputs (a
    // ripple elsewhere in the row, a scroll that moves the layer) reuses it.
    private var laidOutFor: LayoutKey? = null
    private var layout: TextLayoutResult? = null
    private var outline: Outline? = null

    fun update(initial: String, color: Color, ink: Color, tile: Boolean) {
        if (initial != this.initial || tile != this.tile) laidOutFor = null
        this.initial = initial
        this.color = color
        this.ink = ink
        this.tile = tile
        invalidateDraw()
    }

    override fun ContentDrawScope.draw() {
        val box = IntSize(size.width.roundToInt(), size.height.roundToInt())
        val key = LayoutKey(box, density, fontScale, layoutDirection)
        if (key != laidOutFor) {
            val shape = if (tile) RoundedCornerShape(TILE_CORNER) else CircleShape
            outline = shape.createOutline(size, layoutDirection, this)
            layout = layOutInitial(box, this, layoutDirection)
            laidOutFor = key
        }
        val fill = if (tile) lerp(currentValueOf(LocalLfColors).surface.raised, color, TILE_TINT) else color
        drawOutline(requireNotNull(outline) { "laid out above" }, fill)
        val text = requireNotNull(layout) { "laid out above" }
        drawText(
            textLayoutResult = text,
            color = if (tile) color else ink,
            topLeft = Alignment.Center.align(text.size, box, layoutDirection).let {
                Offset(it.x.toFloat(), it.y.toFloat())
            },
        )
        drawContent()
    }

    private fun layOutInitial(box: IntSize, density: Density, direction: LayoutDirection): TextLayoutResult {
        val measurer = currentValueOf(LocalLfGlyphMeasurer)
            ?: ownMeasurer
            ?: TextMeasurer(currentValueOf(LocalFontFamilyResolver), density, direction, cacheSize = 1)
                .also { ownMeasurer = it }
        return measurer.measure(
            text = initial,
            style = currentValueOf(LocalLfTypography).label.copy(
                fontSize = initialSize(box, density, if (tile) TILE_INITIAL_RATIO else INITIAL_HEIGHT_RATIO),
                // Unspecified so the line box follows the pinned font size. The
                // scale's 16sp line height would otherwise keep scaling and
                // push a correctly-sized glyph out of a fixed-size circle.
                lineHeight = TextUnit.Unspecified,
            ),
            constraints = Constraints(maxWidth = box.width, maxHeight = box.height),
            density = density,
            layoutDirection = direction,
        )
    }

    private data class LayoutKey(
        val box: IntSize,
        val density: Float,
        val fontScale: Float,
        val direction: LayoutDirection,
    )
}

/**
 * Half the swatch's diameter, in `sp` that render at exactly that many pixels
 * whatever the user's font scale. See `LfCategoryDot` for why the initial does
 * not follow the font scale. The same `Dp.toSp()` the composed dot used, so
 * the conversion is the platform's rather than a division of our own.
 */
private fun initialSize(box: IntSize, density: Density, ratio: Float): TextUnit =
    with(density) { (box.width * ratio).toDp().toSp() }

/** The tile's initial: 16sp in a 40 dp tile, quieter than the dot's half. */
internal const val TILE_INITIAL_RATIO: Float = 0.4f

/** How much of the category's colour the tile's fill carries: the theme's subtle tint. */
internal const val TILE_TINT: Float = 0.24f

/** The samples' tile corner: 12, since 16 on a 40 dp square reads as a circle. */
private val TILE_CORNER = LfScale.Radius12 // = LfShapeRoles.tile; a node reads no theme shapes

/** The initial's height as a fraction of the swatch. Matches 12sp in a 24dp dot. */
internal const val INITIAL_HEIGHT_RATIO: Float = 0.5f
