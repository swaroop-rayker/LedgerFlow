package com.ledgerflow.core.designsystem.component

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.Measurable
import androidx.compose.ui.layout.MeasureResult
import androidx.compose.ui.layout.MeasureScope
import androidx.compose.ui.layout.layoutId
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.Constraints
import com.ledgerflow.core.designsystem.icon.LfIcons
import com.ledgerflow.core.designsystem.theme.LfLayout
import com.ledgerflow.core.designsystem.theme.LfTheme

/**
 * A row that opens a page: its name, the page's current value when one value
 * says it, and a chevron (DESIGN_SYSTEM_PLAN §3, step D4).
 *
 * Built for Profile and the Settings index, where rows sit together in one
 * [com.ledgerflow.core.designsystem.pattern.LfPanel] with [LfDivider]s between
 * them rather than one card each: a list of places to go is scanned, and
 * a stack of cards for it is the bloat the compactness brief rules out.
 *
 * **The value sits beside the title only while every title word still fits.**
 * Otherwise it moves under the title. Side by side regardless, at font scale
 * 2.0 "Security & privacy" beside "Phone's lock" read "Secur / ity", and capping
 * the value's width was not enough ("Notificati / ons" beside "Stopped"): one
 * line can be too narrow for both, which is BUG9's defect in a row rather than
 * a button. `LfNavRowTitleBreaksBetweenWordsTest` holds it. The chevron stays
 * centred and mirrors in RTL.
 *
 * **[icon]: the reference samples' tile at the start** (step F5): the page's
 * icon in a 40 dp tile on the overlay surface, and the row 56 dp at least
 * (owner, 2026-10-29: the canvas drew 64, and 56 keeps a list of places to
 * go compact). Decoration, like the chevron: the title names the page.
 */
@Composable
public fun LfNavRow(
    title: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    value: String? = null,
    icon: ImageVector? = null,
) {
    val gap = LfTheme.space.itemGap
    val tokens = LfNavRowTokens
    Layout(
        modifier = modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = if (icon != null) tokens.minHeightWithIcon else LfLayout.minTouchTarget)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = LfTheme.space.rowInset, vertical = LfTheme.space.rowInsetVertical),
        content = {
            Text(text = title, style = LfTheme.type.body, color = LfTheme.colors.text.primary)
            // Decoration: the row's role already says it opens something.
            Icon(imageVector = LfIcons.Chevron, contentDescription = null, tint = LfTheme.colors.text.muted)
            value?.let {
                Text(text = it, style = LfTheme.type.bodySmall, color = LfTheme.colors.text.secondary)
            }
            icon?.let { NavRowTile(it) }
        },
    ) { all, constraints -> measureNavRow(all, constraints, gap.roundToPx()) }
}

/** The tile [LfNavRow] draws at its start (step F5). */
@Composable
private fun NavRowTile(icon: ImageVector) {
    val tokens = LfNavRowTokens
    Box(
        modifier = Modifier
            .layoutId(TILE_ID)
            .size(tokens.tileSize)
            .background(tokens.tileColor, tokens.tileShape),
        contentAlignment = Alignment.Center,
    ) {
        Icon(imageVector = icon, contentDescription = null, tint = tokens.glyphTint)
    }
}

/**
 * [LfNavRow]'s layout: the tile at the start, the chevron at the end, and the
 * value beside the title only while every title word still fits.
 */
private fun MeasureScope.measureNavRow(all: List<Measurable>, constraints: Constraints, gapPx: Int): MeasureResult {
    val loose = constraints.copy(minWidth = 0, minHeight = 0)
    val tile = all.firstOrNull { it.layoutId == TILE_ID }?.measure(loose)
    val measurables = all.filter { it.layoutId != TILE_ID }
    val lead = tile?.let { it.width + gapPx } ?: 0
    val chevron = measurables[1].measure(loose)
    val text = (constraints.maxWidth - lead - chevron.width - gapPx).coerceAtLeast(0)
    val titleMeasurable = measurables[0]
    val valueMeasurable = measurables.getOrNull(2)

    val valueWidth = valueMeasurable?.maxIntrinsicWidth(Constraints.Infinity) ?: 0
    val titleLongestWord = titleMeasurable.minIntrinsicWidth(Constraints.Infinity)
    val beside = valueMeasurable == null || titleLongestWord + gapPx + valueWidth <= text

    val valuePlaceable = valueMeasurable?.measure(
        Constraints(maxWidth = if (beside) minOf(valueWidth, text) else text),
    )
    val titleWidth = if (beside && valuePlaceable != null) text - valuePlaceable.width - gapPx else text
    val titlePlaceable = titleMeasurable.measure(Constraints(maxWidth = titleWidth.coerceAtLeast(0)))

    val textHeight = if (beside) {
        maxOf(titlePlaceable.height, valuePlaceable?.height ?: 0)
    } else {
        titlePlaceable.height + (valuePlaceable?.height ?: 0)
    }
    val height = maxOf(textHeight, chevron.height, tile?.height ?: 0, constraints.minHeight)
    return layout(constraints.maxWidth, height) {
        val top = (height - textHeight) / 2
        tile?.placeRelative(0, (height - tile.height) / 2)
        if (beside) {
            titlePlaceable.placeRelative(lead, (height - titlePlaceable.height) / 2)
            valuePlaceable?.placeRelative(
                lead + text - valuePlaceable.width,
                (height - valuePlaceable.height) / 2,
            )
        } else {
            titlePlaceable.placeRelative(lead, top)
            valuePlaceable?.placeRelative(lead, top + titlePlaceable.height)
        }
        chevron.placeRelative(constraints.maxWidth - chevron.width, (height - chevron.height) / 2)
    }
}

private const val TILE_ID = "lf-nav-row-tile"
