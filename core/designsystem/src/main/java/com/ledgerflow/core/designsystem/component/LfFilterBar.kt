package com.ledgerflow.core.designsystem.component

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import com.ledgerflow.core.designsystem.icon.LfIcons

/**
 * A list's period and its filters on one line (step F5, the reference
 * samples' filter bar): a pill naming the period, which opens the choice, and
 * a filter control carrying how many filters are on.
 *
 * It replaced Analytics' row of seven period chips and its separate "Filters"
 * button, two bands of chrome above the charts (owner, 2026-10-29). The cost
 * is a second tap to change the period; the pill always names the one in
 * force, so nothing about the window is hidden by it.
 *
 * **The count is on the control**, as the button it replaced had it: a
 * screen quietly showing a subset is how someone concludes their data is
 * missing. Labels never wrap (BUG9); at a large font scale the filter control
 * moves to the next line whole.
 *
 * @param period what the pill says: the period in force ("Month", "3 Aug – 19 Aug").
 * @param activeFilters how many filters are on; 0 shows the icon alone.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
public fun LfFilterBar(
    period: String,
    onPeriod: () -> Unit,
    activeFilters: Int,
    onFilters: () -> Unit,
    modifier: Modifier = Modifier,
) {
    FlowRow(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalArrangement = Arrangement.spacedBy(LfFilterBarTokens.gap),
        itemVerticalAlignment = Alignment.CenterVertically,
    ) {
        FilterPill(
            label = period,
            trailing = LfIcons.Expand,
            description = "Period: $period. Changes the period.",
            onClick = onPeriod,
        )
        FilterPill(
            label = if (activeFilters > 0) activeFilters.toString() else null,
            leading = LfIcons.Filter,
            description = when (activeFilters) {
                0 -> "Filters, none on"
                1 -> "Filters, 1 on"
                else -> "Filters, $activeFilters on"
            },
            onClick = onFilters,
        )
    }
}

@Composable
private fun FilterPill(
    label: String?,
    description: String,
    onClick: () -> Unit,
    leading: ImageVector? = null,
    trailing: ImageVector? = null,
) {
    val tokens = LfFilterBarTokens
    Row(
        modifier = Modifier
            .defaultMinSize(minHeight = tokens.height, minWidth = tokens.height)
            .clip(tokens.shape)
            .background(tokens.container)
            .clickable(onClick = onClick)
            // One announcement per control, with what tapping does: the icon
            // and the count read as fragments on their own.
            .clearAndSetSemantics {
                contentDescription = description
                role = Role.Button
                onClick { onClick(); true }
            }
            .padding(horizontal = tokens.inset),
        horizontalArrangement = Arrangement.spacedBy(tokens.iconGap, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        leading?.let { PillIcon(it) }
        label?.let {
            Text(
                text = it,
                style = tokens.labelStyle,
                color = tokens.content,
                maxLines = 1,
                softWrap = false,
            )
        }
        trailing?.let { PillIcon(it) }
    }
}

@Composable
private fun PillIcon(icon: ImageVector) {
    Icon(
        imageVector = icon,
        contentDescription = null,
        tint = LfFilterBarTokens.content,
        modifier = Modifier.size(LfFilterBarTokens.icon),
    )
}
