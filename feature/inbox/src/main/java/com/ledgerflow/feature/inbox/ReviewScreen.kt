package com.ledgerflow.feature.inbox

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.PreviewFontScale
import androidx.compose.ui.tooling.preview.PreviewLightDark
import androidx.compose.ui.tooling.preview.PreviewScreenSizes
import com.ledgerflow.core.designsystem.component.LfActionAlignment
import com.ledgerflow.core.designsystem.component.LfActionRow
import com.ledgerflow.core.designsystem.component.LfButton
import com.ledgerflow.core.designsystem.component.LfButtonStyle
import com.ledgerflow.core.designsystem.component.LfCard
import com.ledgerflow.core.designsystem.component.LfDivider
import com.ledgerflow.core.designsystem.component.LfEmptyState
import com.ledgerflow.core.designsystem.component.LfScaffold
import com.ledgerflow.core.designsystem.component.LfScreenTitle
import com.ledgerflow.core.designsystem.component.LfSegmentedControl
import com.ledgerflow.core.designsystem.component.LfTextField
import com.ledgerflow.core.designsystem.theme.LfTheme
import com.ledgerflow.core.model.Category
import com.ledgerflow.core.model.LedgerType
import com.ledgerflow.core.ui.lineitem.LfLineItemEditor
import com.ledgerflow.core.ui.picker.LfDetailRow

/**
 * Reviewing one candidate (SPEC.md §5.1). P2-6, redesigned.
 *
 * **The manual entry form's screen, filled in from a message.** Same detail
 * rows, same pickers, same `Single item | Itemised` control, same line editor —
 * the pickers and rows are literally the same composables from `:core:ui`, so
 * the two screens cannot drift. That is the owner's requirement made structural
 * rather than a thing to remember.
 *
 * **There is no book control.** The message already said: "debited" is spend,
 * "credited" is income, and the parser read that before this screen opened.
 * Asking again was confusing and is gone. The one exception is a candidate the
 * parser could not read at all — §5.1's never-drop rows — where there is nothing
 * to derive from; those get a Book row in the details card, in the same picker
 * style as every other row, and only then.
 *
 * Its own screen rather than `:feature:entry` prefilled: a candidate is not a
 * draft, and routing one through `draft_entry` would put a half-reviewed
 * message into the drafts stack, where discarding it in one place leaves it
 * alive in the other.
 */
@Composable
public fun ReviewScreen(
    state: ReviewUiState,
    onEvent: (ReviewEvent) -> Unit,
    onDone: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(state.finished) { if (state.finished) onDone() }

    state.message?.let { message ->
        LaunchedEffect(message) {
            snackbarHostState.showSnackbar(message)
            onEvent(ReviewEvent.MessageShown)
        }
    }

    ReviewDialogs(state, onEvent)

    LfScaffold(
        modifier = modifier,
        snackbarHostState = snackbarHostState,
        bottomBar = { if (!state.missing) ReviewActions(state, onEvent, onBack) },
    ) { padding ->
        if (state.missing) {
            LfEmptyState(
                title = "Not here any more",
                body = "This item was already reviewed, or it has been removed.",
                actionLabel = "Back",
                onAction = onBack,
                modifier = Modifier.fillMaxSize().padding(padding),
            )
            return@LfScaffold
        }

        ReviewForm(state, onEvent, Modifier.padding(padding))
    }
}

/** The form itself, in the entry screen's order: how much, then what for. */
@Composable
private fun ReviewForm(
    state: ReviewUiState,
    onEvent: (ReviewEvent) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = LfTheme.spacing.md),
        verticalArrangement = Arrangement.spacedBy(LfTheme.spacing.sm),
    ) {
        LfScreenTitle(title = "Review", subtitle = state.provenanceLine())

        LfTextField(
            value = state.amountText,
            onValueChange = { onEvent(ReviewEvent.AmountChanged(it)) },
            label = "Amount",
            modifier = Modifier.fillMaxWidth(),
        )

        // Under the amount, because that is the order of the decision:
        // how much, then what for (§5.4, ADR-0018).
        LfSegmentedControl(
            options = listOf("Single item", "Itemised"),
            selectedIndex = if (state.itemised) 1 else 0,
            onSelect = { onEvent(ReviewEvent.ModeSelected(itemised = it == 1)) },
        )

        if (state.itemised) {
            LfLineItemEditor(
                state = state.editorState(),
                onEvent = { onEvent(it.toReviewEvent()) },
            )
        }

        DetailRows(state, onEvent)

        LfTextField(
            value = state.noteText,
            onValueChange = { onEvent(ReviewEvent.NoteChanged(it)) },
            label = "Note",
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/**
 * The entry form's details card, row for row.
 *
 * Category and Subcategory are absent when itemised: such an entry files at
 * line grain and stores no category of its own (ADR-0018), so leaving the rows
 * here would offer a choice that is written nowhere.
 *
 * Book appears **only** when the parser could not read a direction. On every
 * ordinary candidate the message decided it and there is nothing to ask.
 */
@Composable
private fun DetailRows(state: ReviewUiState, onEvent: (ReviewEvent) -> Unit) {
    LfCard {
        Column {
            if (state.bookIsUnread) {
                LfDetailRow(
                    label = "Book",
                    value = state.ledger?.label(),
                    placeholder = "Choose",
                    onClick = { onEvent(ReviewEvent.PickerOpened(ReviewPicker.Book)) },
                )
                LfDivider()
            }
            if (!state.itemised) {
                LfDetailRow(
                    label = "Category",
                    value = state.selectedCategory,
                    onClick = { onEvent(ReviewEvent.PickerOpened(ReviewPicker.Category())) },
                )
                state.categoryId?.let { parentId ->
                    LfDivider()
                    LfDetailRow(
                        label = "Subcategory",
                        value = state.selectedSubcategory,
                        onClick = {
                            onEvent(ReviewEvent.PickerOpened(ReviewPicker.Subcategory(parentId)))
                        },
                    )
                }
                LfDivider()
            }
            LfDetailRow(
                label = "Merchant",
                value = state.selectedMerchant,
                onClick = { onEvent(ReviewEvent.PickerOpened(ReviewPicker.Merchant)) },
            )
            LfDivider()
            LfDetailRow(
                label = "Paid with",
                value = state.selectedPaymentMethod,
                onClick = { onEvent(ReviewEvent.PickerOpened(ReviewPicker.PaymentMethod)) },
            )
            LfDivider()
            LfDetailRow(
                label = "Date",
                value = state.occurredAt.asLocalDate(),
                onClick = { onEvent(ReviewEvent.DateRequested) },
            )
        }
    }
}

/**
 * Pinned, so the decision never requires finding the bottom of the form.
 *
 * `xs` padding only: [LfScaffold] has already inset this for the navigation
 * bar, and a uniform `lg` would stack 24dp on an inset that exists for the same
 * purpose — out of the one thing on screen that scrolls.
 */
@Composable
private fun ReviewActions(
    state: ReviewUiState,
    onEvent: (ReviewEvent) -> Unit,
    onBack: () -> Unit,
) {
    LfActionRow(
        alignment = LfActionAlignment.End,
        modifier = Modifier.padding(horizontal = LfTheme.spacing.md, vertical = LfTheme.spacing.xs),
    ) {
        LfButton(text = "Back", style = LfButtonStyle.Text, onClick = onBack)
        LfButton(
            text = "Discard",
            style = LfButtonStyle.Outlined,
            onClick = { onEvent(ReviewEvent.Discard) },
        )
        LfButton(
            text = "Approve",
            enabled = state.canApprove,
            loading = state.submitting,
            onClick = { onEvent(ReviewEvent.Approve) },
        )
    }
}

internal fun LedgerType.label(): String = when (this) {
    LedgerType.DEBIT -> "Expense"
    LedgerType.CREDIT -> "Income"
}

private fun ReviewUiState.provenanceLine(): String = buildList {
    add(sourceLabel)
    referenceHint?.let(::add)
    if (needsManualFill) add("nothing was extracted")
}.filter { it.isNotEmpty() }.joinToString(" · ")

// ── Previews ─────────────────────────────────────────────────────────────────

private val sampleCategories = listOf(
    Category("c1", null, LedgerType.DEBIT, "Food", "cart", 0x00FF8800, 1, true),
    Category("c2", "c1", LedgerType.DEBIT, "Groceries", "cart", 0x00FF8800, 2, false),
)

@PreviewScreenSizes
@PreviewFontScale
@PreviewLightDark
@Composable
private fun ReviewScreenPreview() {
    LfTheme {
        ReviewScreen(
            state = ReviewUiState(
                pendingId = "1",
                loading = false,
                ledger = LedgerType.DEBIT,
                amountText = "2.00",
                rawMerchantName = "RAMESH KUMAR",
                categories = sampleCategories,
                sourceLabel = "From an SMS",
                occurredAt = 1_787_810_214_627L,
                referenceHint = "Ref 999999999998",
            ),
            onEvent = {},
            onDone = {},
            onBack = {},
        )
    }
}

/**
 * §5.1's never-drop case: nothing extracted, so the Book row appears and every
 * other field is the user's to fill.
 */
@PreviewFontScale
@PreviewLightDark
@Composable
private fun ReviewUnparsedPreview() {
    LfTheme {
        ReviewScreen(
            state = ReviewUiState(
                pendingId = "2",
                loading = false,
                ledger = null,
                bookIsUnread = true,
                needsManualFill = true,
                sourceLabel = "From an SMS",
                occurredAt = 1_787_810_214_627L,
            ),
            onEvent = {},
            onDone = {},
            onBack = {},
        )
    }
}
