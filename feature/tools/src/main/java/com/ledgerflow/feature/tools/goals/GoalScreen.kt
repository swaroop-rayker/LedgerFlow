package com.ledgerflow.feature.tools.goals

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
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
import com.ledgerflow.core.designsystem.component.LfFeedback
import com.ledgerflow.core.designsystem.component.LfIconButton
import com.ledgerflow.core.designsystem.component.LfProgressBar
import com.ledgerflow.core.designsystem.component.LfProgressTone
import com.ledgerflow.core.designsystem.component.lfGroupPosition
import com.ledgerflow.core.designsystem.icon.LfIcons
import com.ledgerflow.core.designsystem.pattern.LfConfirmSheet
import com.ledgerflow.core.designsystem.pattern.LfDetailPage
import com.ledgerflow.core.designsystem.pattern.LfPageSpacing
import com.ledgerflow.core.designsystem.pattern.LfOptionsSheet
import com.ledgerflow.core.designsystem.pattern.LfPanel
import com.ledgerflow.core.designsystem.pattern.LfSheetOption
import com.ledgerflow.core.designsystem.theme.LfLayout
import com.ledgerflow.core.designsystem.theme.LfTheme

/**
 * One savings goal (V2-PLAN §4.1; the owner's mockup 3, 2026-11-09).
 *
 * What it holds and where it is heading in one panel, with "Add money" and
 * "Take out" inline at its foot; then the linked category's total, read live;
 * then the amounts added by hand, whose rows open a sheet to edit or delete.
 * Edit and Delete for the goal itself are in the options sheet beside the
 * title. Every figure is bone: a goal is a plan, never a money colour.
 */
@Composable
public fun GoalScreen(
    state: GoalUiState,
    onEvent: (GoalEvent) -> Unit,
    onEdit: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (state.gone) {
        LaunchedEffect(Unit) { onBack() }
        return
    }
    val feedback = LfFeedback.current
    state.message?.let { message ->
        LaunchedEffect(message) {
            feedback.post(message)
            onEvent(GoalEvent.MessageShown)
        }
    }
    GoalOverlays(state, onEvent, onEdit)

    LfDetailPage(
        title = state.name,
        modifier = modifier,
        onBack = onBack,
        actions = {
            LfIconButton(
                icon = LfIcons.Options,
                contentDescription = "Goal options",
                onClick = { onEvent(GoalEvent.OptionsClicked) },
            )
        },
        actionsAlignment = LfActionAlignment.End,
        // The hand-added rows are slices of one panel; headings carry the space.
        spacing = LfPageSpacing.None,
    ) {
        if (state.loading) return@LfDetailPage
        item(key = "summary", contentType = "summary") { Summary(state, onEvent) }
        state.linked?.let { linked ->
            item(key = "linked", contentType = "linked") { Linked(linked) }
        }
        item(key = "hand-heading", contentType = "heading") { SectionHeading("Added by hand") }
        if (state.contributions.isEmpty()) {
            item(key = "hand-none", contentType = "none") {
                LfPanel {
                    Text(
                        text = "Nothing added by hand yet.",
                        style = LfTheme.type.bodySmall,
                        color = LfTheme.colors.text.muted,
                        modifier = Modifier.padding(LfTheme.space.cardInset),
                    )
                }
            }
        }
        itemsIndexed(state.contributions, key = { _, row -> row.id }, contentType = { _, _ -> "contribution" }) {
                index, row ->
            ContributionRow(
                row = row,
                position = lfGroupPosition(index, state.contributions.size),
                onClick = { onEvent(GoalEvent.ContributionClicked(row.id)) },
            )
        }
    }
}

@Composable
private fun Summary(state: GoalUiState, onEvent: (GoalEvent) -> Unit) {
    LfPanel {
        Column(
            modifier = Modifier.padding(LfTheme.space.cardInset),
            verticalArrangement = Arrangement.spacedBy(LfTheme.space.inlineGap),
        ) {
            Text(text = state.saved, style = LfTheme.type.amountHero, color = LfTheme.colors.text.primary)
            Text(text = state.ofTarget, style = LfTheme.type.bodySmall, color = LfTheme.colors.text.secondary)
            LfProgressBar(
                fraction = state.fraction,
                tone = if (state.warning) LfProgressTone.Warning else LfProgressTone.Accent,
                modifier = Modifier.padding(vertical = LfTheme.space.inlineGap),
            )
            state.facts.forEach { fact ->
                Text(
                    text = fact.text,
                    style = LfTheme.type.label,
                    color = if (fact.warning) LfTheme.colors.status.warning else LfTheme.colors.text.muted,
                )
            }
            LfActionRow(alignment = LfActionAlignment.End) {
                LfButton(
                    text = "Add money",
                    onClick = { onEvent(GoalEvent.AddMoneyClicked) },
                    style = LfButtonStyle.Inline,
                )
                LfButton(
                    text = "Take out",
                    onClick = { onEvent(GoalEvent.TakeOutClicked) },
                    style = LfButtonStyle.Inline,
                )
            }
        }
    }
}

@Composable
private fun Linked(linked: LinkedCategoryUi) {
    Column {
        SectionHeading(linked.heading)
        LfPanel {
            LfAdaptiveRow(
                leading = { Text(text = linked.since, style = LfTheme.type.body, color = LfTheme.colors.text.primary) },
                trailing = {
                    Text(text = linked.amount, style = LfTheme.type.amountSmall, color = LfTheme.colors.text.primary)
                },
                modifier = Modifier.padding(
                    horizontal = LfTheme.space.rowInset,
                    vertical = LfTheme.space.rowInsetVertical,
                ),
            )
        }
    }
}

@Composable
private fun ContributionRow(
    row: ContributionRowUi,
    position: com.ledgerflow.core.designsystem.component.LfGroupPosition,
    onClick: () -> Unit,
) {
    LfAdaptiveRow(
        leading = {
            Text(
                text = listOfNotNull(row.date, row.note ?: if (row.takenOut) "Taken out" else null)
                    .joinToString(" · "),
                style = LfTheme.type.body,
                color = LfTheme.colors.text.primary,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        },
        trailing = { Text(text = row.amount, style = LfTheme.type.amountSmall, color = LfTheme.colors.text.primary) },
        modifier = Modifier
            .fillMaxWidth()
            .goalSlice(position)
            // A tappable row is at least a touch target tall (SPEC §9.6).
            .defaultMinSize(minHeight = LfLayout.minTouchTarget)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = LfTheme.space.rowInset, vertical = LfTheme.space.rowInsetVertical),
    )
}

/** The page's sheets and its dialog, at most one open at a time. */
@Composable
private fun GoalOverlays(state: GoalUiState, onEvent: (GoalEvent) -> Unit, onEdit: () -> Unit) {
    when {
        state.editor != null -> ContributionDialog(state.editor, state.currency, onEvent)
        state.optionsOpen -> LfOptionsSheet(
            title = state.name,
            options = listOf(
                LfSheetOption(label = "Edit goal", onClick = {
                    onEvent(GoalEvent.OptionsDismissed)
                    onEdit()
                }),
                LfSheetOption(label = "Delete goal", onClick = { onEvent(GoalEvent.DeleteClicked) }),
            ),
            onDismiss = { onEvent(GoalEvent.OptionsDismissed) },
        )
        state.confirmingDelete -> LfConfirmSheet(
            title = "Delete ${state.name}?",
            body = deleteBody(state),
            confirmLabel = "Delete",
            onConfirm = { onEvent(GoalEvent.DeleteConfirmed) },
            onDismiss = { onEvent(GoalEvent.DeleteCancelled) },
        )
        state.contributionOptions != null -> LfOptionsSheet(
            options = listOf(
                LfSheetOption(label = "Edit", onClick = { onEvent(GoalEvent.EditContributionClicked) }),
                LfSheetOption(label = "Delete", onClick = { onEvent(GoalEvent.DeleteContributionClicked) }),
            ),
            onDismiss = { onEvent(GoalEvent.ContributionOptionsDismissed) },
        )
    }
}

/** Names what goes and what stays (CLAUDE.md §7's rule for anything irreversible). */
internal fun deleteBody(state: GoalUiState): String {
    val count = state.contributions.size
    val goes = when (count) {
        0 -> "The goal is deleted for good."
        1 -> "The goal and its 1 hand-added amount are deleted for good."
        else -> "The goal and its $count hand-added amounts are deleted for good."
    }
    val stays = if (state.linked != null) " Entries in its category stay in your books." else ""
    return goes + stays
}

@PreviewScreenSizes
@PreviewFontScale
@PreviewLightDark
@Composable
private fun GoalScreenPreview() {
    LfTheme { GoalScreen(state = GoalsPreviews.goal, onEvent = {}, onEdit = {}, onBack = {}) }
}
