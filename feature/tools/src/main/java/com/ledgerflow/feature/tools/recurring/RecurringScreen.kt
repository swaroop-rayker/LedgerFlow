package com.ledgerflow.feature.tools.recurring

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
import com.ledgerflow.core.designsystem.component.LfEmptyState
import com.ledgerflow.core.designsystem.component.LfGroupPosition
import com.ledgerflow.core.designsystem.component.lfGroupPosition
import com.ledgerflow.core.designsystem.pattern.LfDetailPage
import com.ledgerflow.core.designsystem.pattern.LfPageSpacing
import com.ledgerflow.core.designsystem.theme.LfLayout
import com.ledgerflow.core.designsystem.theme.LfTheme
import com.ledgerflow.core.model.LedgerType
import com.ledgerflow.feature.tools.goals.SectionHeading
import com.ledgerflow.feature.tools.goals.goalSlice

/**
 * Recurring (V2-PLAN §4.2; the owner's mockup 1, 2026-11-09).
 *
 * Suggestions first, each with "Not recurring" and "Add" inline; then each
 * book in its own panel, never one list or one total across the two (Law 2).
 * A row is about two lines: the name and its amount, then the schedule.
 */
@Composable
public fun RecurringScreen(
    state: RecurringListUiState,
    onEvent: (RecurringListEvent) -> Unit,
    onNew: () -> Unit,
    onAddSuggestion: (merchantId: String, ledger: LedgerType) -> Unit,
    onOpen: (String) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    LfDetailPage(
        title = "Recurring",
        modifier = modifier,
        onBack = onBack,
        spacing = LfPageSpacing.None,
        notices = {
            LfActionRow(alignment = LfActionAlignment.Start) {
                LfButton(text = "New", onClick = onNew, style = LfButtonStyle.Outlined)
            }
        },
        statusContent = if (state.isEmpty) {
            {
                LfEmptyState(
                    title = "Nothing recurring yet",
                    body = "Add rent, a subscription or a salary to be reminded before it's due. " +
                        "Payments that look recurring will be suggested here.",
                )
            }
        } else {
            null
        },
    ) {
        if (state.suggestions.isNotEmpty()) {
            item(key = "suggestions-heading", contentType = "heading") {
                SectionHeading("Looks recurring · from your history")
            }
            itemsIndexed(
                state.suggestions,
                key = { _, row -> "s-${row.ledger}-${row.merchantId}" },
                contentType = { _, _ -> "suggestion" },
            ) { index, row ->
                SuggestionRow(row, lfGroupPosition(index, state.suggestions.size), onEvent, onAddSuggestion)
            }
        }
        book("Expense", state.expense, onOpen)
        book("Income", state.income, onOpen)
    }
}

/** One book's items under its heading; nothing at all when the book has none. */
private fun LazyListScope.book(heading: String, rows: List<RecurringRowUi>, onOpen: (String) -> Unit) {
    if (rows.isEmpty()) return
    item(key = "heading-$heading", contentType = "heading") { SectionHeading(heading) }
    itemsIndexed(rows, key = { _, row -> row.id }, contentType = { _, _ -> "item" }) { index, row ->
        ItemRow(row, lfGroupPosition(index, rows.size), onClick = { onOpen(row.id) })
    }
}

@Composable
private fun ItemRow(row: RecurringRowUi, position: LfGroupPosition, onClick: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .goalSlice(position)
            .defaultMinSize(minHeight = LfLayout.minTouchTarget)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = LfTheme.space.rowInset, vertical = LfTheme.space.rowInsetVertical),
        verticalArrangement = Arrangement.spacedBy(LfTheme.space.inlineGap),
    ) {
        NameAndAmount(row.name, row.amount, muted = row.paused)
        Text(text = row.caption, style = LfTheme.type.label, color = LfTheme.colors.text.muted)
    }
}

@Composable
private fun SuggestionRow(
    row: SuggestionRowUi,
    position: LfGroupPosition,
    onEvent: (RecurringListEvent) -> Unit,
    onAdd: (String, LedgerType) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .goalSlice(position)
            .padding(horizontal = LfTheme.space.rowInset, vertical = LfTheme.space.rowInsetVertical),
        verticalArrangement = Arrangement.spacedBy(LfTheme.space.inlineGap),
    ) {
        NameAndAmount(row.name, row.amount, muted = false)
        Text(text = row.caption, style = LfTheme.type.label, color = LfTheme.colors.text.muted)
        LfActionRow(alignment = LfActionAlignment.End) {
            LfButton(
                text = "Not recurring",
                onClick = { onEvent(RecurringListEvent.NotRecurring(row.merchantId, row.ledger)) },
                style = LfButtonStyle.Inline,
            )
            LfButton(text = "Add", onClick = { onAdd(row.merchantId, row.ledger) }, style = LfButtonStyle.Inline)
        }
    }
}

/** The name and its amount; the amount drops below at large text (`LfAdaptiveRow`). Bone: a plan, not money moving. */
@Composable
internal fun NameAndAmount(name: String, amount: String, muted: Boolean) {
    val color = if (muted) LfTheme.colors.text.muted else LfTheme.colors.text.primary
    LfAdaptiveRow(
        leading = {
            Text(text = name, style = LfTheme.type.body, color = color, maxLines = 2, overflow = TextOverflow.Ellipsis)
        },
        trailing = { Text(text = amount, style = LfTheme.type.amountSmall, color = color) },
    )
}

@PreviewScreenSizes
@PreviewFontScale
@PreviewLightDark
@Composable
private fun RecurringScreenPreview() {
    LfTheme {
        RecurringScreen(
            state = RecurringPreviews.list,
            onEvent = {},
            onNew = {},
            onAddSuggestion = { _, _ -> },
            onOpen = {},
            onBack = {},
        )
    }
}
