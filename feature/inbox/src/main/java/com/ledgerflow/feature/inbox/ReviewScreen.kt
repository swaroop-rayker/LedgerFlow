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
import com.ledgerflow.core.designsystem.component.LfChip
import com.ledgerflow.core.designsystem.component.LfChipStyle
import com.ledgerflow.core.designsystem.component.LfEmptyState
import com.ledgerflow.core.designsystem.component.LfScaffold
import com.ledgerflow.core.designsystem.component.LfScreenTitle
import com.ledgerflow.core.designsystem.component.LfSegmentedControl
import com.ledgerflow.core.designsystem.component.LfTextField
import com.ledgerflow.core.designsystem.theme.LfTheme
import com.ledgerflow.core.model.Category
import com.ledgerflow.core.model.LedgerType

/**
 * Reviewing one candidate (SPEC.md §5.1). P2-6.
 *
 * **Its own screen rather than the entry form**, decided at P2-6. A pending row
 * is not a draft: routing one through `:feature:entry` would put a half-reviewed
 * candidate into `draft_entry` and the drafts stack, where discarding it in one
 * place would leave it alive in the other. The two queues gate different things
 * — one a commit, one unsaved typing (§5.4) — and merging them would blur that.
 *
 * §5.1 asks for "fields prefilled and focus on Category picker". Everything else
 * here corrects what the parser read; the category is the one thing it never
 * supplies, so it is the section with nothing pre-chosen.
 *
 * One card shape for every section, hairline-bordered by [LfCard], with the
 * actions pinned rather than scrolled — the user's decision should not require
 * finding the bottom of a form.
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

    LaunchedEffect(state.approved) { if (state.approved) onDone() }

    state.message?.let { message ->
        LaunchedEffect(message) {
            snackbarHostState.showSnackbar(message)
            onEvent(ReviewEvent.MessageShown)
        }
    }

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

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = LfTheme.spacing.md),
            verticalArrangement = Arrangement.spacedBy(LfTheme.spacing.sm),
        ) {
            LfScreenTitle(title = "Review", subtitle = state.provenanceLine())

            BookCard(state, onEvent)
            DetailsCard(state, onEvent)
            CategoryCard(state, onEvent)
        }
    }
}

/**
 * The book, first and unskippable.
 *
 * No pre-selection when the parser could not read a direction. Law 2 keeps the
 * two ledgers apart precisely because nothing downstream reconciles them, so a
 * default here would be a guess that files income as spend and never surfaces.
 */
@Composable
private fun BookCard(state: ReviewUiState, onEvent: (ReviewEvent) -> Unit) {
    LfCard {
        Column(verticalArrangement = Arrangement.spacedBy(LfTheme.spacing.xs)) {
            Text(
                text = "Book",
                style = LfTheme.typography.bodyL,
                color = LfTheme.colors.textPrimary,
            )
            val books = LedgerType.entries
            LfSegmentedControl(
                options = books.map { it.label() },
                selectedIndex = state.ledger?.let(books::indexOf) ?: -1,
                onSelect = { onEvent(ReviewEvent.LedgerChosen(books[it])) },
                modifier = Modifier.fillMaxWidth(),
            )
            if (state.ledger == null) {
                Text(
                    text = "The message did not say. Choose one.",
                    style = LfTheme.typography.label,
                    color = LfTheme.colors.textTertiary,
                )
            }
        }
    }
}

@Composable
private fun DetailsCard(state: ReviewUiState, onEvent: (ReviewEvent) -> Unit) {
    LfCard {
        Column(verticalArrangement = Arrangement.spacedBy(LfTheme.spacing.sm)) {
            LfTextField(
                value = state.amountText,
                onValueChange = { onEvent(ReviewEvent.AmountChanged(it)) },
                label = "Amount",
                modifier = Modifier.fillMaxWidth(),
            )
            LfTextField(
                value = state.merchantText,
                onValueChange = { onEvent(ReviewEvent.MerchantChanged(it)) },
                label = "Payee",
                supportingText = "Added to your merchants when you approve.",
                modifier = Modifier.fillMaxWidth(),
            )
            LfTextField(
                value = state.noteText,
                onValueChange = { onEvent(ReviewEvent.NoteChanged(it)) },
                label = "Note",
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

/**
 * §5.1's emphasis: the one field the parser never fills.
 *
 * Chips rather than a dropdown so the common case is one tap, and inside an
 * [LfActionRow] so a long category list wraps as whole chips at font scale 2.0
 * rather than clipping a label (BUG9).
 */
@Composable
private fun CategoryCard(state: ReviewUiState, onEvent: (ReviewEvent) -> Unit) {
    LfCard {
        Column(verticalArrangement = Arrangement.spacedBy(LfTheme.spacing.xs)) {
            Text(
                text = "Category",
                style = LfTheme.typography.bodyL,
                color = LfTheme.colors.textPrimary,
            )
            if (state.ledger == null) {
                Text(
                    text = "Choose a book first.",
                    style = LfTheme.typography.label,
                    color = LfTheme.colors.textTertiary,
                )
                return@LfCard
            }
            LfActionRow(alignment = LfActionAlignment.Start) {
                // Uncategorised is an answer, not a missing one -- approval
                // accepts a null category, so offering it explicitly is honest.
                LfChip(
                    label = "Uncategorised",
                    style = if (state.categoryId == null) {
                        LfChipStyle.Selected
                    } else {
                        LfChipStyle.Assist
                    },
                    onClick = { onEvent(ReviewEvent.CategoryChosen(null)) },
                )
                state.categories.forEach { category ->
                    LfChip(
                        label = category.displayName().trim(),
                        style = if (category.id == state.categoryId) {
                            LfChipStyle.Selected
                        } else {
                            LfChipStyle.Assist
                        },
                        onClick = { onEvent(ReviewEvent.CategoryChosen(category.id)) },
                    )
                }
            }
        }
    }
}

/**
 * Pinned, so the decision never requires finding the bottom of the form.
 *
 * `xs` padding only: [LfScaffold] has already inset this for the navigation bar,
 * and a uniform `lg` here would stack 24dp on an inset that exists for the same
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

private fun LedgerType.label(): String = when (this) {
    LedgerType.DEBIT -> "Expense"
    LedgerType.CREDIT -> "Income"
}

private fun ReviewUiState.provenanceLine(): String = buildList {
    add(sourceLabel)
    add(occurredAtLabel)
    rawBodyHint?.let(::add)
    if (needsManualFill) add("nothing was extracted")
}.filter { it.isNotEmpty() }.joinToString(" · ")

// ── Previews ─────────────────────────────────────────────────────────────────

private val sampleCategories = listOf(
    Category("c1", null, LedgerType.DEBIT, "Food", "cart", 0x00FF8800, 1, true),
    Category("c2", "c1", LedgerType.DEBIT, "Groceries", "cart", 0x00FF8800, 2, false),
    Category("c3", null, LedgerType.DEBIT, "Transport", "car", 0x006E8BFF, 3, true),
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
                merchantText = "RAMESH KUMAR",
                categories = sampleCategories,
                sourceLabel = "From an SMS",
                occurredAtLabel = "27 Aug 2026",
                rawBodyHint = "Ref 999999999998",
            ),
            onEvent = {},
            onDone = {},
            onBack = {},
        )
    }
}

/** The §5.1 never-drop case: nothing extracted, so every field is the user's. */
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
                needsManualFill = true,
                sourceLabel = "From an SMS",
                occurredAtLabel = "27 Aug 2026",
            ),
            onEvent = {},
            onDone = {},
            onBack = {},
        )
    }
}
