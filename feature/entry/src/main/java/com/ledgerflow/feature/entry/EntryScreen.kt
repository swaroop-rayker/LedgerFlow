package com.ledgerflow.feature.entry

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.PreviewFontScale
import androidx.compose.ui.tooling.preview.PreviewLightDark
import androidx.compose.ui.tooling.preview.PreviewScreenSizes
import androidx.compose.ui.unit.dp
import com.ledgerflow.core.designsystem.component.LfActionRow
import com.ledgerflow.core.designsystem.component.LfAmountField
import com.ledgerflow.core.designsystem.component.LfAmountTone
import com.ledgerflow.core.designsystem.component.LfButton
import com.ledgerflow.core.designsystem.component.LfButtonStyle
import com.ledgerflow.core.designsystem.component.LfCard
import com.ledgerflow.core.designsystem.component.LfChip
import com.ledgerflow.core.designsystem.component.LfChipStyle
import com.ledgerflow.core.designsystem.component.LfDivider
import com.ledgerflow.core.designsystem.component.LfIconButton
import com.ledgerflow.core.designsystem.component.LfScaffold
import com.ledgerflow.core.designsystem.component.LfSegmentedControl
import com.ledgerflow.core.designsystem.component.LfTextField
import com.ledgerflow.core.designsystem.format.MoneyFormat
import com.ledgerflow.core.designsystem.icon.LfIcons
import com.ledgerflow.core.designsystem.theme.LfTheme
import com.ledgerflow.core.ui.picker.LfDetailRow
import com.ledgerflow.core.ui.lineitem.LfLineItemEditor
import com.ledgerflow.core.ui.lineitem.LineItemEditorEvent
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
    // Cancel decides in the ViewModel whether it parks the form or leaves, so
    // the screen only reacts to the answer.
    LaunchedEffect(state.dismissed) {
        if (state.dismissed) onDone()
    }

    EntryDialogs(state, onEvent)

    val amountFocus = remember { FocusRequester() }
    // Keyed on the form generation as well as the restore, so "Start another"
    // and a ledger switch put the caret back in the amount. Without it the
    // shelf hands you an empty form you then have to tap into, which defeats
    // the point of parking one entry to begin the next.
    LaunchedEffect(state.isRestoring, state.formGeneration) {
        if (!state.isRestoring && !state.resumedFromDraft) {
            runCatching { amountFocus.requestFocus() }
        }
    }

    LfScaffold(
        modifier = modifier,
        bottomBar = { SaveBar(state, onEvent) },
    ) { padding ->
        EntryForm(state, onEvent, amountFocus, padding)
    }
}

/**
 * Everything that scrolls.
 *
 * Split out of [EntryScreen] when the itemised-mode control pushed that
 * function past detekt's length and complexity thresholds. The limits were
 * measuring something real: the screen function's job is the scaffold, the
 * dialogs and the focus effect, and the form's contents had simply been living
 * inside it.
 */
@Composable
private fun EntryForm(
    state: EntryUiState,
    onEvent: (EntryEvent) -> Unit,
    amountFocus: FocusRequester,
    padding: PaddingValues,
) {
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

        // Only once there is an amount -- the same rule the unsaved shelf
        // follows below. On an untouched form this is a control for a
        // decision the user has not reached yet.
        if (state.amountMinor > 0L) ModeSelector(state, onEvent)

        if (state.resumedFromDraft) ResumeNotice(onEvent)
        if (state.combos.isNotEmpty()) ComboChips(state, onEvent)
        // Also shown with an empty shelf once the form has an amount, so
        // "Start another" exists *before* there is a second draft. Gated on
        // `unsaved` alone it only appeared once something was already
        // parked -- which is the one state from which you can never park
        // anything.
        if (state.unsaved.isNotEmpty() || state.amountMinor > 0L) {
            UnsavedStack(state, onEvent)
        }

        DetailRows(state, onEvent)

        LfTextField(
            value = state.note,
            onValueChange = { onEvent(EntryEvent.NoteChanged(it)) },
            label = "Note (optional)",
        )

        if (state.itemised) {
            LfLineItemEditor(
                state = state.editor,
                onEvent = { onEvent(it.toEntryEvent()) },
            )
        }

        state.message?.let {
            Text(text = it, style = LfTheme.typography.bodyM, color = LfTheme.colors.debit)
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
 * The unsaved-entry stack (ADR-0013) — a horizontal shelf, newest first.
 *
 * D-06 allowed one draft per book because unbounded drafts would "accumulate
 * into a list nobody curates". This is that list, and it is why the constraint
 * could go: they only pile up unseen if nothing shows them. The same shape
 * serves the Inbox at P2 -- but over `pending_transaction`, which is a
 * different table on purpose (§5.4): one gates a commit, this recovers typing.
 *
 * **Horizontal, not a vertical list.** The first build stacked full-width cards
 * inline, and it was reported as confusing for a structural reason: every
 * parked entry pushed the amount field and the whole form further down, so the
 * more drafts you had the further you scrolled to do the thing you opened the
 * screen for. The stack read as an obstruction rather than a shelf. A row is
 * the same height whatever the count, sits under the amount without displacing
 * anything, and reads the way a queue should.
 *
 * The card width is chosen so the next one always peeks past the screen edge.
 * A row whose cards happen to end flush with the margin reads as a complete
 * list and nobody swipes it.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun UnsavedStack(state: EntryUiState, onEvent: (EntryEvent) -> Unit) {
    val spacing = LfTheme.spacing

    Column(verticalArrangement = Arrangement.spacedBy(spacing.sm)) {
        // FlowRow, not Row. At font scale 2.0 "Start another" does not fit
        // beside the count, and `softWrap = false` turns that into a clipped
        // "Start anoth" -- BUG9's documented residual case. The remedy is the
        // one the design system already uses everywhere: the container wraps
        // the whole control onto the next line, never the word.
        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(LfTheme.spacing.sm),
            verticalArrangement = Arrangement.spacedBy(LfTheme.spacing.xs),
        ) {
            Text(
                modifier = Modifier.align(Alignment.CenterVertically),
                text = if (state.unsaved.isEmpty()) {
                    "Working on this one"
                } else {
                    "Unsaved · ${state.unsaved.size}"
                },
                style = LfTheme.typography.label,
                color = LfTheme.colors.textSecondary,
                maxLines = 1,
                softWrap = false,
            )
            // In the header, not in the row. As the trailing tile it was the
            // item that peeked past the screen edge -- so the one action always
            // available was the one always half cut off, and it got worse with
            // every draft added. Here it is fixed, full width for its label,
            // and the cards do the peeking, which is what peeking is for.
            LfButton(
                text = "Start another",
                style = LfButtonStyle.Text,
                onClick = { onEvent(EntryEvent.NewDraftStarted) },
            )
        }

        if (state.unsaved.isEmpty()) return@Column

        LazyRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(spacing.sm),
        ) {
            items(items = state.unsaved, key = { it.id }, contentType = { "draft" }) { draft ->
                DraftCard(draft, onEvent)
            }
        }
    }
}

@Composable
private fun DraftCard(draft: EntryDraftCard, onEvent: (EntryEvent) -> Unit) {
    val spacing = LfTheme.spacing
    val colors = LfTheme.colors
    val shape = RoundedCornerShape(spacing.cornerMedium)

    Box(
        modifier = Modifier
            .width(spacing.peekCardWidth)
            .clip(shape)
            .background(colors.surfaceRaised)
            .border(1.dp, colors.outline, shape)
            // The whole card opens it. A card whose only tap target is a small
            // button inside it is a card people tap and nothing happens.
            .clickable { onEvent(EntryEvent.DraftOpened(draft.id)) }
            .padding(start = spacing.md, end = spacing.xs, bottom = spacing.md),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(spacing.xs)) {
            DraftCardHeader(draft, onEvent)
            Text(
                text = MoneyFormat.symbolised(draft.amountMinor, draft.currencyCode),
                style = LfTheme.typography.amountM,
                color = colors.textPrimary,
                maxLines = 1,
                softWrap = false,
                modifier = Modifier.padding(end = spacing.sm),
            )
            // What it is filed as gets its own line above the note. Folding
            // both onto one `maxLines = 1` line meant the merchant and category
            // -- the thing that identifies which draft this is -- were the first
            // characters to be ellipsised away by a long note.
            draft.filedAs?.let {
                Text(
                    text = it,
                    style = LfTheme.typography.bodyM,
                    color = colors.textPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(end = spacing.sm),
                )
            }
            Text(
                text = draft.subtitle() ?: "No details yet",
                style = LfTheme.typography.label,
                color = colors.textSecondary,
                // A note is content, not a control label, so BUG9's no-ellipsis
                // rule does not apply: a truncated note is still readable, and
                // the card opens to the whole thing.
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(end = spacing.sm),
            )
        }
    }
}

/** How old it is, and the way to throw it away. */
@Composable
private fun DraftCardHeader(draft: EntryDraftCard, onEvent: (EntryEvent) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = draft.age,
            style = LfTheme.typography.label,
            color = LfTheme.colors.textTertiary,
            maxLines = 1,
            softWrap = false,
        )
        LfIconButton(
            icon = LfIcons.Close,
            // Names what it throws away. "Close" beside an amount could as
            // easily mean "collapse this card".
            contentDescription = "Discard unsaved entry of " +
                MoneyFormat.spoken(draft.amountMinor, draft.currencyCode),
            onClick = { onEvent(EntryEvent.DraftDiscardRequested(draft.id)) },
        )
    }
}

/** What the card says under the amount, if there is anything worth saying. */
private fun EntryDraftCard.subtitle(): String? = listOfNotNull(
    note,
    // Singular matters more since ADR-0018: an itemised entry always has lines,
    // so "1 items" went from a rare edge case to something on most cards.
    when (lineItemCount) {
        0 -> null
        1 -> "1 item"
        else -> "$lineItemCount items"
    },
).joinToString(" · ").ifBlank { null }

@Composable
private fun DetailRows(state: EntryUiState, onEvent: (EntryEvent) -> Unit) {
    LfCard {
        Column {
            // Absent when itemised: such an entry files at line grain and
            // stores no category of its own (ADR-0018). Leaving the rows here
            // would offer a choice that is written nowhere.
            if (!state.itemised) {
                LfDetailRow(
                    label = "Category",
                    value = state.selectedCategory,
                    onClick = { onEvent(EntryEvent.PickerOpened(EntryPicker.Category())) },
                )
                state.categoryId?.let { parentId ->
                    LfDivider()
                    LfDetailRow(
                        label = "Subcategory",
                        value = state.selectedSubcategory,
                        onClick = {
                            onEvent(EntryEvent.PickerOpened(EntryPicker.Subcategory(parentId)))
                        },
                    )
                }
                LfDivider()
            }
            LfDetailRow(
                label = "Merchant",
                value = state.selectedMerchant,
                onClick = { onEvent(EntryEvent.PickerOpened(EntryPicker.Merchant)) },
            )
            LfDivider()
            LfDetailRow(
                label = "Paid with",
                value = state.selectedPaymentMethod,
                onClick = { onEvent(EntryEvent.PickerOpened(EntryPicker.PaymentMethod)) },
            )
            LfDivider()
            LfDetailRow(
                label = "Date",
                value = state.occurredAt.asLocalDate(),
                onClick = { onEvent(EntryEvent.DateRequested) },
            )
        }
    }
}

/**
 * `Single item | Itemised` (SPEC.md §5.4, ADR-0018).
 *
 * A second segmented control on one screen is a real cost, and it is paid
 * deliberately: this is the choice that decides where the entry's spend is
 * attributed, and a feature nobody can find is a feature nobody uses. It sits
 * under the amount because that is the order of the decision -- how much, then
 * what for.
 */
@Composable
private fun ModeSelector(state: EntryUiState, onEvent: (EntryEvent) -> Unit) {
    LfSegmentedControl(
        options = listOf("Single item", "Itemised"),
        selectedIndex = if (state.itemised) 1 else 0,
        onSelect = { onEvent(EntryEvent.ModeSelected(itemised = it == 1)) },
    )
}

/**
 * The shared editor's events, in this screen's vocabulary.
 *
 * The editor lives in `:core:ui` and knows nothing about entries, so the
 * translation happens here rather than there -- which is what lets the Inbox
 * drive the same component at P2 with its own event type.
 */
private fun LineItemEditorEvent.toEntryEvent(): EntryEvent = when (this) {
    LineItemEditorEvent.AddRequested -> EntryEvent.LineItemAdded
    is LineItemEditorEvent.Expanded -> EntryEvent.LineItemExpanded(key)
    LineItemEditorEvent.Collapsed -> EntryEvent.LineItemCollapsed
    is LineItemEditorEvent.NameChanged -> EntryEvent.LineItemNameChanged(key, value)
    is LineItemEditorEvent.UnitPriceChanged -> EntryEvent.LineItemUnitPriceChanged(key, text)
    is LineItemEditorEvent.QuantityChanged -> EntryEvent.LineItemQuantityChanged(key, text)
    is LineItemEditorEvent.RemoveRequested -> EntryEvent.LineItemRemoved(key)
    is LineItemEditorEvent.CategoryRequested -> EntryEvent.LineItemCategoryRequested(key)
    is LineItemEditorEvent.SubcategoryRequested -> EntryEvent.LineItemSubcategoryRequested(key)
}

@Composable
private fun SaveBar(state: EntryUiState, onEvent: (EntryEvent) -> Unit) {
    Column(modifier = Modifier.padding(LfTheme.spacing.md)) {
        LfActionRow {
            LfButton(
                text = "Cancel",
                style = LfButtonStyle.Text,
                onClick = { onEvent(EntryEvent.CancelRequested) },
            )
            LfButton(
                text = "Save",
                enabled = state.canSave,
                loading = state.isSaving,
                onClick = { onEvent(EntryEvent.SaveRequested) },
            )
        }
    }
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
