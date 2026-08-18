package com.ledgerflow.feature.entry

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Text
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.ledgerflow.core.designsystem.component.LfButton
import com.ledgerflow.core.designsystem.component.LfButtonStyle
import com.ledgerflow.core.designsystem.component.LfDialog
import com.ledgerflow.core.designsystem.component.LfDialogEmphasis
import com.ledgerflow.core.designsystem.format.MoneyFormat
import com.ledgerflow.core.designsystem.theme.LfTheme

/**
 * Everything this screen can raise over itself.
 *
 * Driven by [EntryUiState] rather than by a `remember` inside the composable,
 * so a rotation mid-choice keeps the choice. Losing a half-made selection to a
 * config change is a small version of the problem BUG6 exists to prevent, and
 * the fix is the same one: state belongs above the composition.
 */
@Composable
internal fun EntryDialogs(state: EntryUiState, onEvent: (EntryEvent) -> Unit) {
    state.picker?.let { PickerDialog(it, state, onEvent) }
    if (state.choosingDate) DateDialog(state, onEvent)
    if (state.confirmingDiscard) DiscardDialog(onEvent)
    state.discardingDraft?.let { DiscardDraftDialog(it, onEvent) }
}

@Composable
private fun PickerDialog(
    picker: EntryPicker,
    state: EntryUiState,
    onEvent: (EntryEvent) -> Unit,
) {
    val options = picker.optionsFrom(state)

    LfDialog(
        title = picker.title,
        body = picker.body,
        // "Clear" rather than "OK": tapping a row is the selection, so the
        // confirming action is the one thing a row cannot do — un-choose.
        confirmText = "Clear",
        onConfirm = { onEvent(EntryEvent.PickerItemSelected(null)) },
        onDismiss = { onEvent(EntryEvent.PickerDismissed) },
        detail = {
            Column(
                modifier = Modifier
                    .heightIn(max = PICKER_MAX_HEIGHT.dp)
                    .verticalScroll(rememberScrollState()),
            ) {
                if (options.isEmpty()) {
                    Text(
                        text = picker.emptyMessage,
                        style = LfTheme.typography.bodyM,
                        color = LfTheme.colors.textSecondary,
                    )
                }
                options.forEach { option ->
                    ChoiceRow(
                        label = option.label,
                        selected = option.id == picker.selectedIdIn(state),
                        onClick = { onEvent(EntryEvent.PickerItemSelected(option.id)) },
                    )
                }
            }
        },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DateDialog(state: EntryUiState, onEvent: (EntryEvent) -> Unit) {
    val pickerState = rememberDatePickerState(initialSelectedDateMillis = state.occurredAt)

    DatePickerDialog(
        onDismissRequest = { onEvent(EntryEvent.DateDismissed) },
        colors = androidx.compose.material3.DatePickerDefaults.colors(
            containerColor = LfTheme.colors.surfaceOverlay,
        ),
        confirmButton = {
            LfButton(
                text = "Set date",
                onClick = {
                    // Null means the user cleared the selection rather than
                    // picked one; the entry keeps the date it already had.
                    val selected = pickerState.selectedDateMillis
                    onEvent(
                        if (selected == null) {
                            EntryEvent.DateDismissed
                        } else {
                            EntryEvent.DateSelected(selected)
                        },
                    )
                },
            )
        },
        dismissButton = {
            LfButton(
                text = "Cancel",
                style = LfButtonStyle.Text,
                onClick = { onEvent(EntryEvent.DateDismissed) },
            )
        },
    ) {
        DatePicker(state = pickerState)
    }
}

@Composable
private fun DiscardDialog(onEvent: (EntryEvent) -> Unit) {
    LfDialog(
        title = "Start fresh?",
        body = "This deletes the entry you had in progress. It is not saved " +
            "anywhere else, so there is nothing to undo it with.",
        confirmText = "Discard it",
        // Destructive, so it must be chosen rather than dismissed by a tap
        // outside. D-06's whole argument is that a draft goes only when the
        // user says so.
        emphasis = LfDialogEmphasis.Warning,
        onConfirm = { onEvent(EntryEvent.DiscardConfirmed) },
        onDismiss = { onEvent(EntryEvent.DiscardDismissed) },
    )
}

/**
 * Discarding one card off the unsaved shelf.
 *
 * The control is a small icon beside other small controls, and what it destroys
 * is work the user typed and never saved. BUG6 is about losing that, and an
 * accidental tap loses it exactly as thoroughly as a process death does -- so
 * it is confirmed, and the confirmation names the amount rather than saying
 * "this item".
 */
@Composable
private fun DiscardDraftDialog(draft: EntryDraftCard, onEvent: (EntryEvent) -> Unit) {
    LfDialog(
        title = "Discard ${MoneyFormat.symbolised(draft.amountMinor, draft.currencyCode)}?",
        body = "This unsaved entry goes for good. It was never saved anywhere " +
            "else, so there is nothing to undo it with.",
        confirmText = "Discard it",
        emphasis = LfDialogEmphasis.Warning,
        onConfirm = { onEvent(EntryEvent.DraftDiscardConfirmed) },
        onDismiss = { onEvent(EntryEvent.DraftDiscardDismissed) },
    )
}

@Composable
private fun ChoiceRow(label: String, selected: Boolean, onClick: () -> Unit) {
    Text(
        text = label,
        style = LfTheme.typography.bodyL,
        color = if (selected) LfTheme.colors.accent else LfTheme.colors.textPrimary,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .defaultMinSize(minHeight = LfTheme.spacing.minTouchTarget)
            .padding(vertical = LfTheme.spacing.sm),
    )
}

/** One row of a picker. */
private data class PickerOption(val id: String, val label: String)

private val EntryPicker.title: String
    get() = when (this) {
        EntryPicker.Category -> "Category"
        is EntryPicker.Subcategory -> "Subcategory"
        EntryPicker.Merchant -> "Merchant"
        EntryPicker.PaymentMethod -> "Paid with"
    }

private val EntryPicker.body: String
    get() = when (this) {
        // Law 2: the two trees are disjoint, and saying so here is cheaper than
        // a user wondering why "Salary" is missing from an expense.
        EntryPicker.Category -> "Expense and income categories are separate lists."
        is EntryPicker.Subcategory -> "Only subcategories of the category you chose."
        EntryPicker.Merchant -> "Where the money went."
        EntryPicker.PaymentMethod -> "The instrument this came out of."
    }

private val EntryPicker.emptyMessage: String
    get() = when (this) {
        EntryPicker.Category -> "No categories yet. Add some in More → Organise."
        is EntryPicker.Subcategory -> "This category has no subcategories."
        EntryPicker.Merchant -> "No merchants yet. Add some in More → Organise."
        EntryPicker.PaymentMethod -> "No payment methods yet. Add some in More → Organise."
    }

private fun EntryPicker.optionsFrom(state: EntryUiState): List<PickerOption> = when (this) {
    EntryPicker.Category -> state.tree.map { PickerOption(it.parent.id, it.parent.name) }

    is EntryPicker.Subcategory -> state.tree
        .firstOrNull { it.parent.id == parentId }
        ?.children
        .orEmpty()
        .map { PickerOption(it.id, it.name) }

    EntryPicker.Merchant -> state.merchants.map { PickerOption(it.id, it.canonicalName) }

    EntryPicker.PaymentMethod -> state.paymentMethods.map { PickerOption(it.id, it.label) }
}

private fun EntryPicker.selectedIdIn(state: EntryUiState): String? = when (this) {
    EntryPicker.Category -> state.categoryId
    is EntryPicker.Subcategory -> state.subcategoryId
    EntryPicker.Merchant -> state.merchantId
    EntryPicker.PaymentMethod -> state.paymentMethodId
}

private const val PICKER_MAX_HEIGHT = 280
