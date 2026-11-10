package com.ledgerflow.feature.tools.recurring

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.tooling.preview.PreviewFontScale
import androidx.compose.ui.tooling.preview.PreviewLightDark
import androidx.compose.ui.tooling.preview.PreviewScreenSizes
import com.ledgerflow.core.designsystem.component.LfActionAlignment
import com.ledgerflow.core.designsystem.component.LfActionRow
import com.ledgerflow.core.designsystem.component.LfAmountField
import com.ledgerflow.core.designsystem.component.LfButton
import com.ledgerflow.core.designsystem.component.LfChip
import com.ledgerflow.core.designsystem.component.LfChipStyle
import com.ledgerflow.core.designsystem.component.LfDivider
import com.ledgerflow.core.designsystem.component.LfSwitchRow
import com.ledgerflow.core.designsystem.component.LfTextField
import com.ledgerflow.core.designsystem.pattern.LfActionBar
import com.ledgerflow.core.designsystem.pattern.LfFormPage
import com.ledgerflow.core.designsystem.pattern.LfPanel
import com.ledgerflow.core.designsystem.theme.LfLayout
import com.ledgerflow.core.designsystem.theme.LfTheme
import com.ledgerflow.core.domain.recurring.Cadence
import com.ledgerflow.core.model.LedgerType
import com.ledgerflow.core.ui.picker.LfDetailRow
import com.ledgerflow.core.ui.picker.LfPickerDialog
import com.ledgerflow.feature.tools.goals.DayPicker

/**
 * New recurring, or Edit (V2-PLAN §4.2; the owner's mockup 3, 2026-11-09).
 *
 * Five repeat choices and three reminder choices are chips, never a segmented
 * control: four or more segments stack into a column at the owner's 1.15 text
 * size (V2-5). The sentence under "Add to Inbox" says when that happens,
 * because "add it for me" could otherwise read as "add it to my books".
 */
@Composable
public fun RecurringEditorScreen(
    state: RecurringEditorUiState,
    onEvent: (RecurringEditorEvent) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (state.done) {
        LaunchedEffect(Unit) { onBack() }
        return
    }
    EditorPickers(state, onEvent)
    LfFormPage(
        title = if (state.isEdit) "Edit recurring" else "New recurring",
        modifier = modifier,
        onBack = onBack,
        actionBar = {
            LfActionBar {
                LfButton(
                    text = "Save",
                    onClick = { onEvent(RecurringEditorEvent.SaveClicked) },
                    loading = state.saving,
                    enabled = !state.loading,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
    ) {
        WhatFields(state, onEvent)
        WhenFields(state, onEvent)
        ReminderFields(state, onEvent)
        state.error?.let { Text(text = it, style = LfTheme.type.label, color = LfTheme.colors.status.danger) }
    }
}

/** The name, the book and the amount. */
@Composable
private fun WhatFields(state: RecurringEditorUiState, onEvent: (RecurringEditorEvent) -> Unit) {
    LfTextField(
        value = state.name,
        onValueChange = { onEvent(RecurringEditorEvent.NameChanged(it)) },
        label = "Name",
        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
    )
    ChoiceChips(
        label = "Book",
        options = LedgerType.entries.map { it to RecurringWords.book(it) },
        selected = state.ledger,
        onPick = { onEvent(RecurringEditorEvent.LedgerPicked(it)) },
    )
    LfAmountField(
        value = state.amountText,
        onValueChange = { onEvent(RecurringEditorEvent.AmountChanged(it)) },
        currencyCode = state.currency,
        label = "Amount",
    )
    LfSwitchRow(
        label = "Amount varies (bills)",
        checked = state.amountVaries,
        onCheckedChange = { onEvent(RecurringEditorEvent.AmountVariesToggled) },
        // A switch row is a touch target tall, as on Settings (SPEC §9.6).
        modifier = Modifier.defaultMinSize(minHeight = LfLayout.minTouchTarget),
        supporting = "Matched by merchant alone, not by amount.".takeIf { state.amountVaries },
    )
}

/** How often, the next due date, and who and what it is for. */
@Composable
private fun WhenFields(state: RecurringEditorUiState, onEvent: (RecurringEditorEvent) -> Unit) {
    ChoiceChips(
        label = "Repeats",
        options = Cadence.entries.map { it to RecurringWords.cadence(it, everyDays = null) },
        selected = state.cadence,
        onPick = { onEvent(RecurringEditorEvent.CadencePicked(it)) },
    )
    if (state.cadence == Cadence.EVERY_N_DAYS) {
        LfTextField(
            value = state.everyDaysText,
            onValueChange = { onEvent(RecurringEditorEvent.EveryDaysChanged(it)) },
            label = "Days apart",
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        )
    }
    LfPanel {
        Column(modifier = Modifier.padding(horizontal = LfTheme.space.rowInset)) {
            LfDetailRow(
                label = if (state.isEdit) "Next due" else "First due",
                value = RecurringWords.date(state.firstDue),
                onClick = { onEvent(RecurringEditorEvent.PickerOpened(RecurringPicker.DUE_DATE)) },
            )
            LfDivider()
            LfDetailRow(
                label = "Merchant",
                value = state.merchantName,
                onClick = { onEvent(RecurringEditorEvent.PickerOpened(RecurringPicker.MERCHANT)) },
            )
            LfDivider()
            LfDetailRow(
                label = "Category",
                value = state.categoryLabel,
                onClick = { onEvent(RecurringEditorEvent.PickerOpened(RecurringPicker.CATEGORY)) },
            )
        }
    }
}

/** When to remind, and whether an unmatched due date is added to the Inbox. */
@Composable
private fun ReminderFields(state: RecurringEditorUiState, onEvent: (RecurringEditorEvent) -> Unit) {
    ChoiceChips(
        label = "Remind me",
        options = listOf(0 to "On the day", 1 to "1 day before", 3 to "3 days before"),
        selected = state.remindDaysBefore,
        onPick = { onEvent(RecurringEditorEvent.RemindPicked(it)) },
    )
    LfSwitchRow(
        label = "Add to Inbox if not captured",
        checked = state.toInbox,
        onCheckedChange = { onEvent(RecurringEditorEvent.ToInboxToggled) },
        modifier = Modifier.defaultMinSize(minHeight = LfLayout.minTouchTarget),
        supporting = if (state.toInbox) {
            "If no SMS or notification for it arrives by 2 days after it's due, it waits in your Inbox to approve."
        } else {
            "Reminds only. Nothing is ever added for you."
        },
    )
}

/** A label, then one chip per option, wrapping as whole chips (BUG9). */
@Composable
private fun <T> ChoiceChips(label: String, options: List<Pair<T, String>>, selected: T, onPick: (T) -> Unit) {
    Column {
        Text(text = label, style = LfTheme.type.label, color = LfTheme.colors.text.secondary)
        LfActionRow(alignment = LfActionAlignment.Start) {
            options.forEach { (value, text) ->
                LfChip(
                    label = text,
                    style = if (value == selected) LfChipStyle.Selected else LfChipStyle.Assist,
                    onClick = { onPick(value) },
                )
            }
        }
    }
}

@Composable
private fun EditorPickers(state: RecurringEditorUiState, onEvent: (RecurringEditorEvent) -> Unit) {
    val close = { onEvent(RecurringEditorEvent.PickerOpened(null)) }
    when (state.openPicker) {
        null -> Unit
        RecurringPicker.DUE_DATE -> DayPicker(
            selected = state.firstDue,
            onPicked = { onEvent(RecurringEditorEvent.DuePicked(it)) },
            onDismiss = close,
        )
        RecurringPicker.MERCHANT -> LfPickerDialog(
            title = "Merchant",
            body = "Who it is paid to or received from. A capture from them can answer a due date.",
            options = state.merchantOptions,
            selectedId = state.merchantId,
            onSelect = { onEvent(RecurringEditorEvent.MerchantPicked(it)) },
            onDismiss = close,
            emptyMessage = "No merchants yet.",
        )
        RecurringPicker.CATEGORY -> LfPickerDialog(
            title = "Category",
            // Not yet carried into the Inbox candidate, which takes the
            // merchant's usual category; the sentence says so rather than promise.
            body = "Shown on the item's page. Its Inbox candidate takes the merchant's usual category.",
            options = state.categoryOptions,
            selectedId = state.categoryChoice,
            onSelect = { onEvent(RecurringEditorEvent.CategoryPicked(it)) },
            onDismiss = close,
            emptyMessage = "This book has no categories yet.",
        )
    }
}

@PreviewScreenSizes
@PreviewFontScale
@PreviewLightDark
@Composable
private fun RecurringEditorPreview() {
    LfTheme { RecurringEditorScreen(state = RecurringPreviews.editor, onEvent = {}, onBack = {}) }
}
