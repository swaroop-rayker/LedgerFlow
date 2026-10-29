package com.ledgerflow.feature.inbox

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.PreviewFontScale
import androidx.compose.ui.tooling.preview.PreviewLightDark
import androidx.compose.ui.tooling.preview.PreviewScreenSizes
import com.ledgerflow.core.designsystem.component.LfActionAlignment
import com.ledgerflow.core.designsystem.component.LfActionRow
import com.ledgerflow.core.designsystem.component.LfAmountCard
import com.ledgerflow.core.designsystem.component.LfAmountTone
import com.ledgerflow.core.designsystem.component.LfButton
import com.ledgerflow.core.designsystem.component.LfButtonStyle
import com.ledgerflow.core.designsystem.component.LfCard
import com.ledgerflow.core.designsystem.component.LfContentState
import com.ledgerflow.core.designsystem.component.LfDivider
import com.ledgerflow.core.designsystem.component.LfEmptyState
import com.ledgerflow.core.designsystem.component.LfFeedback
import com.ledgerflow.core.designsystem.component.LfScaffold
import com.ledgerflow.core.designsystem.component.LfScreenTitle
import com.ledgerflow.core.designsystem.component.LfSegmentedControl
import com.ledgerflow.core.designsystem.component.LfStateContent
import com.ledgerflow.core.designsystem.component.LfTextField
import com.ledgerflow.core.designsystem.pattern.LfActionBar
import com.ledgerflow.core.designsystem.pattern.LfFormPage
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
    ReviewDialogs(state, onEvent)

    if (state.missing) {
        // No form and no bar: the item is gone. The empty state on a plain
        // scaffold, with its way back.
        LfScaffold(modifier = modifier) { padding ->
            LfEmptyState(
                title = "Not here any more",
                body = "This item was already reviewed, or it has been removed.",
                actionLabel = "Back",
                onAction = onBack,
                modifier = Modifier.fillMaxSize().padding(padding),
            )
        }
        return
    }

    if (state.loading) {
        // E4: the form used to draw empty -- a blank amount, no category --
        // for the moment the candidate took to read, then fill in. The heading
        // and still skeletons after 300 ms instead, and no Approve to press on
        // a form that is not there yet. Back stays, so a slow read can be left.
        LfFormPage(
            title = "Review",
            actionBar = null,
            modifier = modifier,
            actions = { LfButton(text = "Back", style = LfButtonStyle.Text, onClick = onBack) },
        ) {
            LfStateContent(state = LfContentState.Loading) {}
        }
        return
    }

    // Where it came from moved into the amount card (step F5), beside the
    // figure it explains.
    LfFormPage(
        title = "Review",
        actionBar = { ReviewActions(state, onEvent, onBack) },
        modifier = modifier,
    ) {
        ReviewFeedback(state, onEvent, onDone)
        ReviewFields(state, onEvent)
    }
}

/**
 * Review's messages, on the window's one snackbar (`LfFeedback`) rather than a
 * host of its own: two hosts on one screen is what E1's feedback removed.
 *
 * BUG73: an approval closed Review without a word, where the Inbox row's own
 * Approve says "Added to your ledger." It says which book now, posted so the
 * line survives this screen closing and shows over the Inbox it returns to.
 */
@Composable
private fun ReviewFeedback(state: ReviewUiState, onEvent: (ReviewEvent) -> Unit, onDone: () -> Unit) {
    val feedback = LfFeedback.current
    state.message?.let { message ->
        LaunchedEffect(message) {
            feedback.show(message)
            onEvent(ReviewEvent.MessageShown)
        }
    }
    LaunchedEffect(state.finished) {
        if (state.finished) {
            feedback.post(approvedMessage(state.ledger))
            onDone()
        }
    }
}

/** What an approval says (BUG73). */
internal fun approvedMessage(ledger: LedgerType?): String = when (ledger) {
    LedgerType.DEBIT -> "Added to Expenses"
    LedgerType.CREDIT -> "Added to Income"
    null -> "Added to your ledger."
}

/** The form itself, in the entry screen's order: how much, then what for. */
@Composable
private fun ReviewFields(
    state: ReviewUiState,
    onEvent: (ReviewEvent) -> Unit,
) {
    state.capturedText?.let { CapturedMessage(it) }

    // The canvas's head card (step F5, owner 2026-10-29): the payee, the book
    // as a word, the amount large in its book's colour, and the source. No
    // tag and a neutral figure while the book is unread: colouring it would
    // be the guess Law 2 forbids, made in pixels.
    LfAmountCard(
        value = state.amountText,
        onValueChange = { onEvent(ReviewEvent.AmountChanged(it)) },
        currencyCode = state.currencyCode,
        label = "Amount",
        title = state.selectedMerchant,
        tag = state.ledger?.label(),
        tone = when (state.ledger) {
            null -> LfAmountTone.Neutral
            LedgerType.DEBIT -> LfAmountTone.Debit
            LedgerType.CREDIT -> LfAmountTone.Credit
        },
        detail = state.provenanceLine(),
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

/**
 * The message as it arrived (BUG58, owner 2026-10-21).
 *
 * Above the form, because it is what the form is filled *from*: on a candidate
 * where nothing was extracted it is the only evidence there is, and before this
 * existed the user could not tell an OTP from a payment, nor report one.
 * Selectable, so a message can be copied out and handed over as a fixture.
 *
 * The same card as the details below it -- one shape per screen -- and quiet
 * type: it is reference, not a field.
 */
@Composable
private fun CapturedMessage(text: String) {
    LfCard {
        Column(verticalArrangement = Arrangement.spacedBy(LfTheme.spacing.xs)) {
            Text(
                text = "Message",
                style = LfTheme.typography.label,
                color = LfTheme.colors.textTertiary,
            )
            SelectionContainer {
                // Plex Mono, as the canvas draws it (step F5): the message
                // verbatim, the source the fields are read from.
                Text(
                    text = text,
                    style = LfTheme.type.figureLabel,
                    color = LfTheme.colors.textSecondary,
                )
            }
        }
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
 * An [LfActionBar]: the screen edge across and a small inset vertically, since
 * [LfScaffold] has already inset it for the navigation bar.
 */
@Composable
private fun ReviewActions(
    state: ReviewUiState,
    onEvent: (ReviewEvent) -> Unit,
    onBack: () -> Unit,
) {
    LfActionBar {
        LfActionRow(alignment = LfActionAlignment.End) {
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
