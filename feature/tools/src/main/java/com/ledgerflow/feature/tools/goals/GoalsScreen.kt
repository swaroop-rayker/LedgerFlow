package com.ledgerflow.feature.tools.goals

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.PreviewFontScale
import androidx.compose.ui.tooling.preview.PreviewLightDark
import androidx.compose.ui.tooling.preview.PreviewScreenSizes
import com.ledgerflow.core.designsystem.component.LfActionAlignment
import com.ledgerflow.core.designsystem.component.LfActionRow
import com.ledgerflow.core.designsystem.component.LfAdaptiveRow
import com.ledgerflow.core.designsystem.component.LfButton
import com.ledgerflow.core.designsystem.component.LfButtonStyle
import com.ledgerflow.core.designsystem.component.LfEmptyState
import com.ledgerflow.core.designsystem.component.LfGroupPosition
import com.ledgerflow.core.designsystem.component.LfProgressBar
import com.ledgerflow.core.designsystem.component.LfProgressTone
import com.ledgerflow.core.designsystem.component.lfGroupPosition
import com.ledgerflow.core.designsystem.component.lfGroupSlice
import com.ledgerflow.core.designsystem.icon.LfIcons
import com.ledgerflow.core.designsystem.pattern.LfDetailPage
import com.ledgerflow.core.designsystem.pattern.LfPageSpacing
import com.ledgerflow.core.designsystem.theme.LfLayout
import com.ledgerflow.core.designsystem.theme.LfTheme

/**
 * Savings goals (V2-PLAN §4.1; the owner's mockup 2, 2026-11-09).
 *
 * **One panel, about two lines a goal** (CLAUDE.md §5): the name and the
 * amounts on one line, the bar, then where the goal is heading. A goal is a
 * plan, not money moving, so its figures are bone and its bar the primary
 * action's tone, amber only when the pace misses the target date — never a
 * money colour. Reached goals follow under their own heading.
 */
@Composable
public fun GoalsScreen(
    state: GoalsUiState,
    onNewGoal: () -> Unit,
    onOpenGoal: (String) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // One painter for every reached row (CLAUDE.md §8: never one per row).
    val tick = rememberVectorPainter(LfIcons.Selected)
    LfDetailPage(
        title = "Savings goals",
        modifier = modifier,
        onBack = onBack,
        // The rows are slices of one panel: no gap between them. Headings
        // carry their own space.
        spacing = LfPageSpacing.None,
        notices = {
            LfActionRow(alignment = LfActionAlignment.Start) {
                LfButton(text = "New goal", onClick = onNewGoal, style = LfButtonStyle.Outlined)
            }
        },
        statusContent = if (state.isEmpty) {
            {
                LfEmptyState(
                    title = "Start your first goal",
                    body = "Set a target, then add money to it by hand, or let a category's entries count toward it.",
                )
            }
        } else {
            null
        },
    ) {
        goalRows(state.active, key = "active", tick = tick, onOpenGoal = onOpenGoal)
        if (state.reached.isNotEmpty()) {
            item(key = "reached-heading", contentType = "heading") { SectionHeading("Reached") }
            goalRows(state.reached, key = "reached", tick = tick, onOpenGoal = onOpenGoal)
        }
    }
}

private fun LazyListScope.goalRows(rows: List<GoalRowUi>, key: String, tick: Painter, onOpenGoal: (String) -> Unit) {
    itemsIndexed(rows, key = { _, row -> "$key-${row.id}" }, contentType = { _, _ -> key }) { index, row ->
        val position = lfGroupPosition(index, rows.size)
        if (key == "reached") {
            ReachedRow(row, position, tick, onClick = { onOpenGoal(row.id) })
        } else {
            GoalRow(row, position, onClick = { onOpenGoal(row.id) })
        }
    }
}

@Composable
private fun GoalRow(row: GoalRowUi, position: LfGroupPosition, onClick: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .goalSlice(position)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = LfTheme.space.rowInset, vertical = LfTheme.space.rowInsetVertical),
        verticalArrangement = Arrangement.spacedBy(LfTheme.space.inlineGap),
    ) {
        NameAndAmounts(row)
        LfProgressBar(
            fraction = row.fraction,
            tone = if (row.warning) LfProgressTone.Warning else LfProgressTone.Accent,
        )
        Text(
            text = row.caption,
            style = LfTheme.type.label,
            color = if (row.warning) LfTheme.colors.status.warning else LfTheme.colors.text.muted,
        )
    }
}

/** A reached goal is one line: a tick, the name, what it holds. */
@Composable
private fun ReachedRow(row: GoalRowUi, position: LfGroupPosition, tick: Painter, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .goalSlice(position)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = LfTheme.space.rowInset, vertical = LfTheme.space.rowInsetVertical),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(LfTheme.space.itemGap),
    ) {
        Icon(painter = tick, contentDescription = "Reached", tint = LfTheme.colors.text.secondary)
        LfAdaptiveRow(
            leading = { GoalName(row.name) },
            trailing = { SavedAmount(row.saved) },
            modifier = Modifier.weight(1f),
        )
    }
}

/** The name and what it holds; the amount drops below at large text (`LfAdaptiveRow`). */
@Composable
private fun NameAndAmounts(row: GoalRowUi) {
    LfAdaptiveRow(
        leading = { GoalName(row.name) },
        trailing = { SavedAmount(row.saved) },
    )
}

@Composable
private fun SavedAmount(text: String) {
    Text(text = text, style = LfTheme.type.amountSmall, color = LfTheme.colors.text.primary)
}

@Composable
private fun GoalName(name: String) {
    Text(
        text = name,
        style = LfTheme.type.body,
        color = LfTheme.colors.text.primary,
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
    )
}

/** A heading over a panel ("Reached", "Added by hand"). */
@Composable
internal fun SectionHeading(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        style = LfTheme.type.label,
        color = LfTheme.colors.text.secondary,
        modifier = modifier.padding(top = LfTheme.space.sectionGap, bottom = LfTheme.space.itemGap),
    )
}

/** A row's slice of its list's one panel, as Payment apps draws its rows. */
@Composable
internal fun Modifier.goalSlice(position: LfGroupPosition): Modifier = lfGroupSlice(
    position = position,
    container = LfTheme.colors.surface.raised,
    edge = LfTheme.colors.border.subtle,
    edgeWidth = LfTheme.borders.hairline,
    radius = LfLayout.panelRadius,
    dividerInset = LfTheme.space.rowInset,
)

@PreviewScreenSizes
@PreviewFontScale
@PreviewLightDark
@Composable
private fun GoalsScreenPreview() {
    LfTheme {
        GoalsScreen(state = GoalsPreviews.list, onNewGoal = {}, onOpenGoal = {}, onBack = {})
    }
}
