package com.ledgerflow.feature.tools.recurring

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.PreviewFontScale
import androidx.compose.ui.tooling.preview.PreviewLightDark
import androidx.compose.ui.tooling.preview.PreviewScreenSizes
import com.ledgerflow.core.designsystem.component.LfActionAlignment
import com.ledgerflow.core.designsystem.component.LfAdaptiveRow
import com.ledgerflow.core.designsystem.component.LfFeedback
import com.ledgerflow.core.designsystem.component.LfIconButton
import com.ledgerflow.core.designsystem.component.lfGroupPosition
import com.ledgerflow.core.designsystem.icon.LfIcons
import com.ledgerflow.core.designsystem.pattern.LfConfirmSheet
import com.ledgerflow.core.designsystem.pattern.LfDetailPage
import com.ledgerflow.core.designsystem.pattern.LfOptionsSheet
import com.ledgerflow.core.designsystem.pattern.LfPageSpacing
import com.ledgerflow.core.designsystem.pattern.LfPanel
import com.ledgerflow.core.designsystem.pattern.LfSheetOption
import com.ledgerflow.core.designsystem.theme.LfTheme
import com.ledgerflow.feature.tools.goals.SectionHeading
import com.ledgerflow.feature.tools.goals.goalSlice

/**
 * One recurring item (V2-PLAN §4.2; the owner's mockup 2, 2026-11-09): the
 * amount, the book and cadence, when it is next due, how it reminds and
 * whether it adds to the Inbox; then "Recent", what each due date turned into.
 * Edit, Pause and Delete are in the options sheet beside the title.
 */
@Composable
public fun RecurringItemScreen(
    state: RecurringItemUiState,
    onEvent: (RecurringItemEvent) -> Unit,
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
            onEvent(RecurringItemEvent.MessageShown)
        }
    }
    ItemOverlays(state, onEvent, onEdit)
    LfDetailPage(
        title = state.name,
        modifier = modifier,
        onBack = onBack,
        actions = {
            LfIconButton(
                icon = LfIcons.Options,
                contentDescription = "Options",
                onClick = { onEvent(RecurringItemEvent.OptionsClicked) },
            )
        },
        actionsAlignment = LfActionAlignment.End,
        spacing = LfPageSpacing.None,
    ) {
        if (state.loading) return@LfDetailPage
        item(key = "summary", contentType = "summary") { Summary(state) }
        if (state.recent.isNotEmpty()) {
            item(key = "recent-heading", contentType = "heading") { SectionHeading("Recent") }
            itemsIndexed(state.recent, key = { _, row -> row.date }, contentType = { _, _ -> "occurrence" }) {
                    index, row ->
                OccurrenceRow(row, lfGroupPosition(index, state.recent.size))
            }
        }
    }
}

/** One due date and what it turned into. */
@Composable
private fun OccurrenceRow(row: OccurrenceRowUi, position: com.ledgerflow.core.designsystem.component.LfGroupPosition) {
    LfAdaptiveRow(
        leading = { Text(text = row.date, style = LfTheme.type.figureLabel, color = LfTheme.colors.text.secondary) },
        trailing = { Text(text = row.words, style = LfTheme.type.body, color = LfTheme.colors.text.primary) },
        modifier = Modifier
            .fillMaxWidth()
            .goalSlice(position)
            .padding(horizontal = LfTheme.space.rowInset, vertical = LfTheme.space.rowInsetVertical),
    )
}

@Composable
private fun Summary(state: RecurringItemUiState) {
    LfPanel {
        Column(
            modifier = Modifier.padding(LfTheme.space.cardInset),
            verticalArrangement = Arrangement.spacedBy(LfTheme.space.inlineGap),
        ) {
            Text(text = state.amount, style = LfTheme.type.amountHero, color = LfTheme.colors.text.primary)
            Text(text = state.subtitle, style = LfTheme.type.bodySmall, color = LfTheme.colors.text.secondary)
            state.facts.forEach { fact ->
                Text(text = fact, style = LfTheme.type.label, color = LfTheme.colors.text.muted)
            }
        }
    }
}

/** The options sheet and the delete confirmation, at most one at a time. */
@Composable
private fun ItemOverlays(state: RecurringItemUiState, onEvent: (RecurringItemEvent) -> Unit, onEdit: () -> Unit) {
    when {
        state.optionsOpen -> LfOptionsSheet(
            title = state.name,
            options = listOf(
                LfSheetOption(label = "Edit", onClick = {
                    onEvent(RecurringItemEvent.OptionsDismissed)
                    onEdit()
                }),
                LfSheetOption(
                    label = if (state.paused) "Resume" else "Pause",
                    onClick = { onEvent(RecurringItemEvent.PauseToggled) },
                ),
                LfSheetOption(label = "Delete", onClick = { onEvent(RecurringItemEvent.DeleteClicked) }),
            ),
            onDismiss = { onEvent(RecurringItemEvent.OptionsDismissed) },
        )
        state.confirmingDelete -> LfConfirmSheet(
            title = "Delete ${state.name}?",
            body = "It and its log of due dates are deleted for good. Entries in your books stay.",
            confirmLabel = "Delete",
            onConfirm = { onEvent(RecurringItemEvent.DeleteConfirmed) },
            onDismiss = { onEvent(RecurringItemEvent.DeleteCancelled) },
        )
    }
}

@PreviewScreenSizes
@PreviewFontScale
@PreviewLightDark
@Composable
private fun RecurringItemPreview() {
    LfTheme { RecurringItemScreen(state = RecurringPreviews.item, onEvent = {}, onEdit = {}, onBack = {}) }
}
