package com.ledgerflow.feature.entry

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.tooling.preview.PreviewFontScale
import androidx.compose.ui.tooling.preview.PreviewLightDark
import androidx.compose.ui.tooling.preview.PreviewScreenSizes
import com.ledgerflow.core.designsystem.component.LfActionRow
import com.ledgerflow.core.designsystem.component.LfAmountField
import com.ledgerflow.core.designsystem.component.LfAmountTone
import com.ledgerflow.core.designsystem.component.LfButton
import com.ledgerflow.core.designsystem.component.LfButtonStyle
import com.ledgerflow.core.designsystem.component.LfCard
import com.ledgerflow.core.designsystem.component.LfChip
import com.ledgerflow.core.designsystem.component.LfChipStyle
import com.ledgerflow.core.designsystem.component.LfDivider
import com.ledgerflow.core.designsystem.component.LfScaffold
import com.ledgerflow.core.designsystem.component.LfSegmentedControl
import com.ledgerflow.core.designsystem.component.LfTextField
import com.ledgerflow.core.designsystem.format.MoneyFormat
import com.ledgerflow.core.designsystem.theme.LfTheme
import com.ledgerflow.core.model.LedgerType
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/**
 * Manual entry (SPEC.md §5.4), both ledgers, with the line-item editor.
 *
 * Stateless: state in, one event lambda out (CLAUDE.md §5). Nothing here
 * remembers anything the user typed — every keystroke goes up to the ViewModel
 * and comes back as state, which is the same discipline that lets `draft_entry`
 * see it (BUG6).
 *
 * The amount is the first thing focused, so the keyboard is already up when the
 * screen opens and the fastest path -- amount, chip, Save -- needs no
 * intervening tap. It is deliberately *not* focused when a draft was resumed:
 * the keyboard would cover the notice explaining why yesterday's figure is on
 * screen.
 */
@Composable
public fun EntryScreen(
    state: EntryUiState,
    onEvent: (EntryEvent) -> Unit,
    onDone: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // The commit is the ViewModel's; leaving is the shell's. Keying on the id
    // rather than a boolean means a second save cannot be swallowed by a flag
    // that was never reset.
    LaunchedEffect(state.savedEntryId) {
        if (state.savedEntryId != null) onDone()
    }

    EntryDialogs(state, onEvent)

    val amountFocus = remember { FocusRequester() }
    LaunchedEffect(state.isRestoring) {
        // Waits for the draft read, then decides once. Keyed on `Unit` this
        // ran at first composition -- before the restore had landed, when
        // `resumedFromDraft` is still false -- so it always focused and put the
        // keyboard over the notice explaining the resume.
        if (!state.isRestoring && !state.resumedFromDraft) {
            runCatching { amountFocus.requestFocus() }
        }
    }

    LfScaffold(
        modifier = modifier,
        bottomBar = { SaveBar(state, onEvent, onDone) },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = LfTheme.spacing.lg),
            verticalArrangement = Arrangement.spacedBy(LfTheme.spacing.md),
        ) {
            LedgerSelector(state, onEvent)

            LfAmountField(
                value = state.amountText,
                onValueChange = { onEvent(EntryEvent.AmountChanged(it)) },
                currencyCode = state.currencyCode,
                focusRequester = amountFocus,
                label = if (state.ledger == LedgerType.DEBIT) "Amount spent" else "Amount received",
                // Neutral until there is an amount. A coral zero on an
                // untouched form reads as an error rather than as an expense,
                // and it is the UI asserting something the user has not said.
                tone = when {
                    state.amountMinor == 0L -> LfAmountTone.Neutral
                    state.ledger == LedgerType.DEBIT -> LfAmountTone.Debit
                    else -> LfAmountTone.Credit
                },
            )

            if (state.resumedFromDraft) ResumeNotice(onEvent)
            if (state.combos.isNotEmpty()) ComboChips(state, onEvent)
            if (state.unsaved.isNotEmpty()) UnsavedStack(state, onEvent)

            DetailRows(state, onEvent)

            LfTextField(
                value = state.note,
                onValueChange = { onEvent(EntryEvent.NoteChanged(it)) },
                label = "Note (optional)",
            )

            LineItemEditor(state, onEvent)

            state.message?.let {
                Text(text = it, style = LfTheme.typography.bodyM, color = LfTheme.colors.debit)
            }
        }
    }
}

@Composable
private fun LedgerSelector(state: EntryUiState, onEvent: (EntryEvent) -> Unit) {
    LfSegmentedControl(
        options = listOf("Expense", "Income"),
        selectedIndex = state.ledger.ordinal,
        onSelect = { onEvent(EntryEvent.LedgerSelected(LedgerType.entries[it])) },
        modifier = Modifier.padding(top = LfTheme.spacing.md),
    )
}

/**
 * §6.1.2: a resumed draft is announced, never silently restored.
 *
 * Someone returning to yesterday's half-typed amount should be told why it is
 * on screen, and given a way out that is an explicit choice rather than a
 * side effect of starting to type.
 */
@Composable
private fun ResumeNotice(onEvent: (EntryEvent) -> Unit) {
    LfCard {
        Column(verticalArrangement = Arrangement.spacedBy(LfTheme.spacing.sm)) {
            Text(
                text = "Picked up where you left off",
                style = LfTheme.typography.bodyM,
                color = LfTheme.colors.textSecondary,
            )
            LfDivider()
            LfActionRow {
                LfButton(
                    text = "Keep it",
                    style = LfButtonStyle.Outlined,
                    onClick = { onEvent(EntryEvent.ResumeNoticeDismissed) },
                )
                LfButton(
                    text = "Start fresh",
                    style = LfButtonStyle.Outlined,
                    onClick = { onEvent(EntryEvent.DiscardRequested) },
                )
            }
        }
    }
}

/** The repeat-expense chips that make §5.4's four-tap target reachable. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ComboChips(state: EntryUiState, onEvent: (EntryEvent) -> Unit) {
    FlowRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(LfTheme.spacing.sm),
        verticalArrangement = Arrangement.spacedBy(LfTheme.spacing.sm),
    ) {
        state.combos.forEachIndexed { index, combo ->
            LfChip(
                label = combo.label,
                style = if (combo.categoryId == state.categoryId) {
                    LfChipStyle.Selected
                } else {
                    LfChipStyle.Assist
                },
                onClick = { onEvent(EntryEvent.ComboSelected(index)) },
            )
        }
    }
}

/**
 * The unsaved-entry stack (ADR-0013), newest first.
 *
 * D-06 allowed one draft per book because unbounded drafts would "accumulate
 * into a list nobody curates". This is that list, and it is why the constraint
 * could go: they only pile up unseen if nothing shows them. The same shape
 * serves the Inbox at P2 -- but over `pending_transaction`, which is a
 * different table on purpose (§5.4): one gates a commit, this recovers typing.
 */
@Composable
private fun UnsavedStack(state: EntryUiState, onEvent: (EntryEvent) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(LfTheme.spacing.sm)) {
        Text(
            text = "Unsaved (${state.unsaved.size})",
            style = LfTheme.typography.label,
            color = LfTheme.colors.textSecondary,
        )
        state.unsaved.forEach { draft ->
            LfCard {
                Column(verticalArrangement = Arrangement.spacedBy(LfTheme.spacing.sm)) {
                    Text(
                        text = MoneyFormat.symbolised(draft.amountMinor, draft.currencyCode),
                        style = LfTheme.typography.amountM,
                        color = LfTheme.colors.textPrimary,
                        maxLines = 1,
                        softWrap = false,
                    )
                    draft.subtitle()?.let {
                        Text(
                            text = it,
                            style = LfTheme.typography.bodyM,
                            color = LfTheme.colors.textSecondary,
                        )
                    }
                    LfDivider()
                    LfActionRow {
                        LfButton(
                            text = "Open",
                            style = LfButtonStyle.Outlined,
                            onClick = { onEvent(EntryEvent.DraftOpened(draft.id)) },
                        )
                        LfButton(
                            text = "Discard",
                            style = LfButtonStyle.Outlined,
                            onClick = { onEvent(EntryEvent.DraftDiscarded(draft.id)) },
                        )
                    }
                }
            }
        }
        LfActionRow {
            LfButton(
                text = "Start another",
                style = LfButtonStyle.Outlined,
                onClick = { onEvent(EntryEvent.NewDraftStarted) },
            )
        }
    }
}

/** What the card says under the amount, if there is anything worth saying. */
private fun EntryDraftCard.subtitle(): String? = listOfNotNull(
    note,
    if (lineItemCount > 0) "$lineItemCount line items" else null,
).joinToString(" · ").ifBlank { null }

@Composable
private fun DetailRows(state: EntryUiState, onEvent: (EntryEvent) -> Unit) {
    LfCard {
        Column {
            DetailRow(
                label = "Category",
                value = state.selectedCategory,
                onClick = { onEvent(EntryEvent.PickerOpened(EntryPicker.Category)) },
            )
            state.categoryId?.let { parentId ->
                LfDivider()
                DetailRow(
                    label = "Subcategory",
                    value = state.selectedSubcategory,
                    onClick = {
                        onEvent(EntryEvent.PickerOpened(EntryPicker.Subcategory(parentId)))
                    },
                )
            }
            LfDivider()
            DetailRow(
                label = "Merchant",
                value = state.selectedMerchant,
                onClick = { onEvent(EntryEvent.PickerOpened(EntryPicker.Merchant)) },
            )
            LfDivider()
            DetailRow(
                label = "Paid with",
                value = state.selectedPaymentMethod,
                onClick = { onEvent(EntryEvent.PickerOpened(EntryPicker.PaymentMethod)) },
            )
            LfDivider()
            DetailRow(
                label = "Date",
                value = state.occurredAt.asLocalDate(),
                onClick = { onEvent(EntryEvent.DateRequested) },
            )
        }
    }
}

@Composable
private fun DetailRow(label: String, value: String?, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .defaultMinSize(minHeight = LfTheme.spacing.minTouchTarget)
            .padding(vertical = LfTheme.spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(LfTheme.spacing.md),
    ) {
        Text(
            text = label,
            style = LfTheme.typography.bodyM,
            color = LfTheme.colors.textSecondary,
            // A row label is a control label (BUG9): whole, on one line. The
            // value beside it is what wraps if anything has to.
            maxLines = 1,
            softWrap = false,
        )
        Text(
            text = value ?: "None",
            style = LfTheme.typography.bodyL,
            color = if (value == null) LfTheme.colors.textTertiary else LfTheme.colors.textPrimary,
            modifier = Modifier.weight(1f),
        )
    }
}

/**
 * The multi-line editor (§5.4, shared in shape with the OCR review at P4).
 *
 * The delta between the lines and the entry total is shown rather than
 * corrected: §5.3 allows saving an unbalanced set, and the approval records the
 * difference as an `UNALLOCATED` line so the parts always add up to the whole.
 * Hiding the delta here would make that row appear from nowhere.
 */
@Composable
private fun LineItemEditor(state: EntryUiState, onEvent: (EntryEvent) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(LfTheme.spacing.sm)) {
        Text(
            text = "Line items",
            style = LfTheme.typography.label,
            color = LfTheme.colors.textSecondary,
        )

        state.lineItems.forEach { line ->
            LineItemCard(line, onEvent)
        }

        if (state.lineItems.isNotEmpty()) {
            Text(
                text = unallocatedLabel(state),
                style = LfTheme.typography.bodyM,
                color = if (state.unallocatedMinor == 0L) {
                    LfTheme.colors.textSecondary
                } else {
                    LfTheme.colors.warn
                },
            )
        }

        LfActionRow {
            LfButton(
                text = "Add line item",
                style = LfButtonStyle.Outlined,
                onClick = { onEvent(EntryEvent.LineItemAdded) },
            )
        }
    }
}

@Composable
private fun LineItemCard(line: EntryLineItem, onEvent: (EntryEvent) -> Unit) {
    LfCard {
        Column(verticalArrangement = Arrangement.spacedBy(LfTheme.spacing.sm)) {
            LfTextField(
                value = line.name,
                onValueChange = { onEvent(EntryEvent.LineItemNameChanged(line.key, it)) },
                label = "Item",
            )
            LfTextField(
                // Raw text, parsed to minor units by the ViewModel -- the same
                // contract as the entry amount, so the two fields in one form
                // cannot behave differently.
                value = line.amountText,
                onValueChange = { onEvent(EntryEvent.LineItemAmountChanged(line.key, it)) },
                label = "Amount",
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            )
            LfDivider()
            LfActionRow {
                LfButton(
                    text = "Remove",
                    style = LfButtonStyle.Outlined,
                    onClick = { onEvent(EntryEvent.LineItemRemoved(line.key)) },
                )
            }
        }
    }
}

@Composable
private fun SaveBar(state: EntryUiState, onEvent: (EntryEvent) -> Unit, onDone: () -> Unit) {
    Column(modifier = Modifier.padding(LfTheme.spacing.md)) {
        LfActionRow {
            LfButton(text = "Cancel", style = LfButtonStyle.Text, onClick = onDone)
            LfButton(
                text = "Save",
                enabled = state.canSave,
                loading = state.isSaving,
                onClick = { onEvent(EntryEvent.SaveRequested) },
            )
        }
    }
}

private fun unallocatedLabel(state: EntryUiState): String = when {
    state.unallocatedMinor == 0L -> "Line items match the total."
    state.unallocatedMinor > 0L ->
        "${MoneyFormat.symbolised(state.unallocatedMinor, state.currencyCode)} unallocated."

    else ->
        "Line items exceed the total by " +
            MoneyFormat.symbolised(-state.unallocatedMinor, state.currencyCode) + "."
}

private fun Long.asLocalDate(): String =
    DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM)
        .format(Instant.ofEpochMilli(this).atZone(ZoneId.systemDefault()).toLocalDate())

@PreviewScreenSizes
@PreviewFontScale
@PreviewLightDark
@Composable
private fun EntryScreenPreview() {
    LfTheme {
        EntryScreen(
            state = EntryUiState(
                amountText = "1250",
                amountMinor = 1_250_00L,
                occurredAt = 1_755_540_000_000L,
            ),
            onEvent = {},
            onDone = {},
        )
    }
}
