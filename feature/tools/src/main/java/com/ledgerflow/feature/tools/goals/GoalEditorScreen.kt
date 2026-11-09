package com.ledgerflow.feature.tools.goals

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.tooling.preview.PreviewFontScale
import androidx.compose.ui.tooling.preview.PreviewLightDark
import androidx.compose.ui.tooling.preview.PreviewScreenSizes
import com.ledgerflow.core.designsystem.component.LfAmountField
import com.ledgerflow.core.designsystem.component.LfButton
import com.ledgerflow.core.designsystem.component.LfDivider
import com.ledgerflow.core.designsystem.component.LfTextField
import com.ledgerflow.core.designsystem.pattern.LfActionBar
import com.ledgerflow.core.designsystem.pattern.LfFormPage
import com.ledgerflow.core.designsystem.pattern.LfPanel
import com.ledgerflow.core.designsystem.theme.LfTheme
import com.ledgerflow.core.ui.picker.LfDetailRow
import com.ledgerflow.core.ui.picker.LfPickerDialog

/**
 * New goal, or Edit goal (V2-PLAN §4.1; the owner's mockup 4, 2026-11-09).
 *
 * A name, a target, an optional date, and optionally a category whose
 * approved entries count toward the goal from a day the user picks. The
 * sentence under the category says the entries stay in their book, because
 * "counts toward a goal" could otherwise read as "moved out of my spending".
 */
@Composable
public fun GoalEditorScreen(
    state: GoalEditorUiState,
    onEvent: (GoalEditorEvent) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (state.done) {
        LaunchedEffect(Unit) { onBack() }
        return
    }
    EditorPickers(state, onEvent)
    LfFormPage(
        title = if (state.isEdit) "Edit goal" else "New goal",
        modifier = modifier,
        onBack = onBack,
        actionBar = {
            LfActionBar {
                LfButton(
                    text = "Save goal",
                    onClick = { onEvent(GoalEditorEvent.SaveClicked) },
                    loading = state.saving,
                    enabled = !state.loading,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
    ) {
        GoalFields(state, onEvent)
        CategoryLink(state, onEvent)
        state.error?.let {
            Text(text = it, style = LfTheme.type.label, color = LfTheme.colors.status.danger)
        }
    }
}

/** The name, the target and its date. */
@Composable
private fun GoalFields(state: GoalEditorUiState, onEvent: (GoalEditorEvent) -> Unit) {
    LfTextField(
        value = state.name,
        onValueChange = { onEvent(GoalEditorEvent.NameChanged(it)) },
        label = "Name",
        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
    )
    LfAmountField(
        value = state.targetText,
        onValueChange = { onEvent(GoalEditorEvent.TargetChanged(it)) },
        currencyCode = state.currency,
        label = "Target",
    )
    LfDetailRow(
        label = "Target date",
        value = state.targetDate?.let(GoalWords::date),
        placeholder = "No date",
        onClick = { onEvent(GoalEditorEvent.PickerOpened(GoalPicker.TARGET_DATE)) },
    )
}

/** The optional category, the day it counts from, and what that means for the books. */
@Composable
private fun CategoryLink(state: GoalEditorUiState, onEvent: (GoalEditorEvent) -> Unit) {
    SectionHeading("Also count a category (optional)")
    LfPanel {
        Column(modifier = Modifier.padding(horizontal = LfTheme.space.rowInset)) {
            LfDetailRow(
                label = "Category",
                value = state.categoryLabel,
                placeholder = "None",
                onClick = { onEvent(GoalEditorEvent.PickerOpened(GoalPicker.CATEGORY)) },
            )
            if (state.categoryId != null) {
                LfDivider()
                LfDetailRow(
                    label = "Count from",
                    value = GoalWords.date(state.countFrom),
                    onClick = { onEvent(GoalEditorEvent.PickerOpened(GoalPicker.COUNT_FROM)) },
                )
            }
        }
    }
    state.categoryLabel?.takeIf { state.categoryId != null }?.let { label ->
        Text(
            text = countsSentence(label, GoalWords.date(state.countFrom)),
            style = LfTheme.type.label,
            color = LfTheme.colors.text.muted,
        )
    }
}

/** "Approved entries in Savings from 1 Aug 2026 add to this goal. They stay in your Expense book and its totals." */
internal fun countsSentence(label: String, from: String): String {
    val name = label.substringBeforeLast(" · ")
    val book = label.substringAfterLast(" · ", missingDelimiterValue = "")
    val stays = if (book.isEmpty()) {
        "They stay in their book and its totals."
    } else {
        "They stay in your $book book and its totals."
    }
    return "Approved entries in $name from $from add to this goal. $stays"
}

@Composable
private fun EditorPickers(state: GoalEditorUiState, onEvent: (GoalEditorEvent) -> Unit) {
    when (state.openPicker) {
        null -> Unit
        GoalPicker.CATEGORY -> LfPickerDialog(
            title = "Category",
            body = "Approved entries in it count toward the goal. A category counts its subcategories.",
            options = emptyList(),
            groups = state.categoryGroups,
            selectedId = state.categoryId,
            onSelect = { onEvent(GoalEditorEvent.CategoryPicked(it)) },
            onDismiss = { onEvent(GoalEditorEvent.PickerOpened(null)) },
            emptyMessage = "Add a category first.",
        )
        GoalPicker.TARGET_DATE -> DayPicker(
            selected = state.targetDate,
            onPicked = { onEvent(GoalEditorEvent.TargetDatePicked(it)) },
            onDismiss = { onEvent(GoalEditorEvent.PickerOpened(null)) },
            dismissLabel = "No date",
            onDismissLabel = { onEvent(GoalEditorEvent.TargetDatePicked(null)) },
        )
        GoalPicker.COUNT_FROM -> DayPicker(
            selected = state.countFrom,
            onPicked = { onEvent(GoalEditorEvent.CountFromPicked(it)) },
            onDismiss = { onEvent(GoalEditorEvent.PickerOpened(null)) },
        )
    }
}

@PreviewScreenSizes
@PreviewFontScale
@PreviewLightDark
@Composable
private fun GoalEditorPreview() {
    LfTheme { GoalEditorScreen(state = GoalsPreviews.editor, onEvent = {}, onBack = {}) }
}
