package com.ledgerflow.feature.tools.goals

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.DatePickerState
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Text
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.ledgerflow.core.designsystem.component.LfAmountField
import com.ledgerflow.core.designsystem.component.LfButton
import com.ledgerflow.core.designsystem.component.LfButtonStyle
import com.ledgerflow.core.designsystem.component.LfDatePickerDialog
import com.ledgerflow.core.designsystem.component.LfDialog
import com.ledgerflow.core.designsystem.component.LfTextField
import com.ledgerflow.core.designsystem.theme.LfTheme
import com.ledgerflow.core.ui.picker.LfDetailRow

/**
 * "Add money" or "Take out" (V2-PLAN §4.1): an amount, its day, a note.
 *
 * The body says what this is and is not, because "add money" reads like a
 * deposit: it is a note against the plan, and the books do not change.
 */
@Composable
internal fun ContributionDialog(editor: ContributionEditorState, currency: String, onEvent: (GoalEvent) -> Unit) {
    if (editor.showDatePicker) {
        DayPicker(
            selected = editor.date,
            onPicked = { onEvent(GoalEvent.DatePicked(it)) },
            onDismiss = { onEvent(GoalEvent.DatePickerDismissed) },
        )
    }
    LfDialog(
        title = editor.title,
        body = if (editor.takingOut) {
            "Money you took out of this goal. A note against the plan: your books don't change."
        } else {
            "Money you put toward this goal. A note against the plan: your books don't change."
        },
        confirmText = "Save",
        onConfirm = { onEvent(GoalEvent.EditorSaved) },
        onDismiss = { onEvent(GoalEvent.EditorDismissed) },
        detail = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(LfTheme.space.itemGap),
            ) {
                LfAmountField(
                    value = editor.amountText,
                    onValueChange = { onEvent(GoalEvent.AmountChanged(it)) },
                    currencyCode = currency,
                    label = "Amount",
                )
                LfDetailRow(
                    label = "Date",
                    value = GoalWords.date(editor.date),
                    onClick = { onEvent(GoalEvent.DateClicked) },
                )
                LfTextField(
                    value = editor.note,
                    onValueChange = { onEvent(GoalEvent.NoteChanged(it)) },
                    label = "Note (optional)",
                )
                editor.error?.let {
                    Text(text = it, style = LfTheme.type.label, color = LfTheme.colors.status.danger)
                }
            }
        },
    )
}

/**
 * Material's date picker in day numbers, as the budget form's start date is.
 * The dialog speaks UTC millis; a day number has no time, so the conversion
 * stays at this boundary.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun DayPicker(
    selected: Int?,
    onPicked: (Int) -> Unit,
    onDismiss: () -> Unit,
    dismissLabel: String = "Cancel",
    onDismissLabel: () -> Unit = onDismiss,
) {
    val state: DatePickerState = rememberDatePickerState(
        initialSelectedDateMillis = selected?.let { it.toLong() * MILLIS_PER_DAY },
    )
    LfDatePickerDialog(
        state = state,
        onDismiss = onDismiss,
        confirmButton = {
            LfButton(
                text = "Set",
                onClick = {
                    val millis = state.selectedDateMillis
                    if (millis != null) onPicked((millis / MILLIS_PER_DAY).toInt()) else onDismiss()
                },
                style = LfButtonStyle.Inline,
            )
        },
        dismissButton = { LfButton(text = dismissLabel, onClick = onDismissLabel, style = LfButtonStyle.Inline) },
    )
}

private const val MILLIS_PER_DAY = 86_400_000L
