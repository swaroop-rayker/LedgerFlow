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
import com.ledgerflow.core.designsystem.component.LfTextField
import com.ledgerflow.core.designsystem.format.MoneyFormat
import com.ledgerflow.core.domain.taxonomy.MerchantNormalizer
import com.ledgerflow.core.designsystem.theme.LfTheme
import com.ledgerflow.core.ui.picker.LfPickerDialog
import com.ledgerflow.core.ui.picker.LfPickerOption

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
    if (state.confirmingSingleItem) SingleItemDialog(state, onEvent)
    state.discardingDraft?.let { DiscardDraftDialog(it, onEvent) }
}

/**
 * The entry form's picker, over the shared component.
 *
 * The dialog itself moved to `:core:ui` when the Inbox's review screen needed
 * the same one (CHANGE#2). What stays here is the translation: which options
 * this picker offers, what is selected, and the merchant-only search and
 * create. `:core:ui` knows no domain type, so that mapping cannot live there.
 */
@Composable
private fun PickerDialog(
    picker: EntryPicker,
    state: EntryUiState,
    onEvent: (EntryEvent) -> Unit,
) {
    val canCreate = state.canCreateMerchant(picker)
    LfPickerDialog(
        title = picker.title,
        body = picker.body,
        options = picker.optionsFrom(state).map { LfPickerOption(it.id, it.label) },
        selectedId = picker.selectedIdIn(state),
        onSelect = { onEvent(EntryEvent.PickerItemSelected(it)) },
        onDismiss = { onEvent(EntryEvent.PickerDismissed) },
        emptyMessage = picker.emptyMessage,
        // Merchants only. §5.4 promises autocomplete for this one field, and it
        // is the only picker whose list grows without bound -- categories are a
        // tree the user curates, payment methods are a handful. A search box
        // over six payment methods is clutter.
        query = if (picker is EntryPicker.Merchant) state.merchantQuery else null,
        onQueryChange = { onEvent(EntryEvent.MerchantQueryChanged(it)) },
        createLabel = if (canCreate) "Add \"${state.merchantQuery.trim()}\"" else null,
        onCreate = if (canCreate) {
            { onEvent(EntryEvent.MerchantCreateRequested) }
        } else {
            null
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

/**
 * Leaving itemised mode with lines entered.
 *
 * Confirmed rather than silent because the control that triggers it is a
 * two-option toggle -- one stray tap -- and what it discards is typing that
 * exists nowhere else. The same reasoning as discarding a draft (BUG6), and the
 * body names the count so the user knows the size of what they are about to
 * lose.
 */
@Composable
private fun SingleItemDialog(state: EntryUiState, onEvent: (EntryEvent) -> Unit) {
    val count = state.editor.rows.size
    LfDialog(
        title = if (count == 1) "Remove the item?" else "Remove all $count items?",
        body = "Going back to a single item discards the breakdown you have " +
            "entered. The entry keeps its total.",
        confirmText = "Remove",
        emphasis = LfDialogEmphasis.Warning,
        onConfirm = { onEvent(EntryEvent.SingleItemConfirmed) },
        onDismiss = { onEvent(EntryEvent.SingleItemDismissed) },
    )
}

/**
 * Whether to offer creating the typed merchant.
 *
 * Compares on the **normalised key**, not the raw text, because that is what
 * `merchant.normalized_key` is unique on -- so "zepto", "Zepto " and "Zepto
 * Pvt Ltd" all recognise the existing row and the offer correctly disappears.
 * Offering to add one of those would not fail (`createOrGet` returns the
 * existing merchant), but it would promise the user a new shop and quietly give
 * them an old one.
 *
 * Hidden merchants are deliberately **not** consulted here: they are absent
 * from `state.merchants`, so a name held by a hidden row still offers to add.
 * That is the honest offer -- `createOrGet` un-hides it and the user gets the
 * merchant they asked for, with its aliases (BUG11).
 */
private fun EntryUiState.canCreateMerchant(picker: EntryPicker): Boolean {
    if (picker !is EntryPicker.Merchant) return false
    val typed = MerchantNormalizer.normalize(merchantQuery)
    if (typed.isEmpty()) return false
    return merchants.none { MerchantNormalizer.normalize(it.canonicalName) == typed }
}

private val EntryPicker.title: String
    get() = when (this) {
        is EntryPicker.Category -> "Category"
        is EntryPicker.Subcategory -> "Subcategory"
        EntryPicker.Merchant -> "Merchant"
        EntryPicker.PaymentMethod -> "Paid with"
    }

private val EntryPicker.body: String
    get() = when (this) {
        // Law 2: the two trees are disjoint, and saying so here is cheaper than
        // a user wondering why "Salary" is missing from an expense.
        is EntryPicker.Category -> "Expense and income categories are separate lists."
        is EntryPicker.Subcategory -> "Only subcategories of the category you chose."
        EntryPicker.Merchant -> "Where the money went."
        EntryPicker.PaymentMethod -> "The instrument this came out of."
    }

private val EntryPicker.emptyMessage: String
    get() = when (this) {
        is EntryPicker.Category -> "No categories yet. Add some in More → Organise."
        is EntryPicker.Subcategory -> "This category has no subcategories."
        EntryPicker.Merchant -> "No merchants yet. Add some in More → Organise."
        EntryPicker.PaymentMethod -> "No payment methods yet. Add some in More → Organise."
    }

private fun EntryPicker.optionsFrom(state: EntryUiState): List<LfPickerOption> = when (this) {
    is EntryPicker.Category -> state.tree.map { LfPickerOption(it.parent.id, it.parent.name) }

    is EntryPicker.Subcategory -> state.tree
        .firstOrNull { it.parent.id == parentId }
        ?.children
        .orEmpty()
        .map { LfPickerOption(it.id, it.name) }

    EntryPicker.Merchant -> state.merchants
        .filter { it.canonicalName.contains(state.merchantQuery.trim(), ignoreCase = true) }
        .map { LfPickerOption(it.id, it.canonicalName) }

    EntryPicker.PaymentMethod -> state.paymentMethods.map { LfPickerOption(it.id, it.label) }
}

/**
 * What is already chosen, so the open picker can tick it.
 *
 * Reads the *line* when the picker was opened for one. Without this the dialog
 * shows nothing selected while editing a line that already has a category,
 * which reads as "not set" and invites the user to set it again.
 */
private fun EntryPicker.selectedIdIn(state: EntryUiState): String? {
    val line = lineKey?.let { key -> state.lineItems.firstOrNull { it.key == key } }
    return when (this) {
        is EntryPicker.Category -> line?.categoryId ?: state.categoryId.takeIf { line == null }
        is EntryPicker.Subcategory -> line?.subcategoryId ?: state.subcategoryId.takeIf { line == null }
        EntryPicker.Merchant -> state.merchantId
        EntryPicker.PaymentMethod -> state.paymentMethodId
    }
}

