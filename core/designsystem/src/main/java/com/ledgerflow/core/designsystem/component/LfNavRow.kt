package com.ledgerflow.core.designsystem.component

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.Layout
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
 * The icon tile the reference samples put at the start arrives in step F3.
 */
@Composable
public fun LfNavRow(
    title: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    value: String? = null,
) {
    val gap = LfTheme.space.itemGap
    Layout(
        modifier = modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = LfLayout.minTouchTarget)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = LfTheme.space.rowInset, vertical = LfTheme.space.rowInsetVertical),
        content = {
            Text(text = title, style = LfTheme.type.body, color = LfTheme.colors.text.primary)
            // Decoration: the row's role already says it opens something.
            Icon(imageVector = LfIcons.Chevron, contentDescription = null, tint = LfTheme.colors.text.muted)
            value?.let {
                Text(text = it, style = LfTheme.type.bodySmall, color = LfTheme.colors.text.secondary)
            }
        },
    ) { measurables, constraints ->
        val gapPx = gap.roundToPx()
        val loose = constraints.copy(minWidth = 0, minHeight = 0)
        val chevron = measurables[1].measure(loose)
        val text = (constraints.maxWidth - chevron.width - gapPx).coerceAtLeast(0)
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
        val height = maxOf(textHeight, chevron.height, constraints.minHeight)
        layout(constraints.maxWidth, height) {
            val top = (height - textHeight) / 2
            if (beside) {
                titlePlaceable.placeRelative(0, (height - titlePlaceable.height) / 2)
                valuePlaceable?.placeRelative(text - valuePlaceable.width, (height - valuePlaceable.height) / 2)
            } else {
                titlePlaceable.placeRelative(0, top)
                valuePlaceable?.placeRelative(0, top + titlePlaceable.height)
            }
            chevron.placeRelative(constraints.maxWidth - chevron.width, (height - chevron.height) / 2)
        }
    }
}
