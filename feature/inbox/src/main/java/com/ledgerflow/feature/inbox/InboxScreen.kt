package com.ledgerflow.feature.inbox

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.PreviewFontScale
import androidx.compose.ui.tooling.preview.PreviewLightDark
import androidx.compose.ui.tooling.preview.PreviewScreenSizes
import androidx.compose.ui.unit.dp
import com.ledgerflow.core.designsystem.component.LfActionAlignment
import com.ledgerflow.core.designsystem.component.LfActionRow
import com.ledgerflow.core.designsystem.component.LfButton
import com.ledgerflow.core.designsystem.component.LfButtonStyle
import com.ledgerflow.core.designsystem.component.LfChip
import com.ledgerflow.core.designsystem.component.LfChipStyle
import com.ledgerflow.core.designsystem.component.LfEmptyState
import com.ledgerflow.core.designsystem.component.LfDialog
import com.ledgerflow.core.designsystem.component.LfDialogEmphasis
import com.ledgerflow.core.designsystem.component.LfScaffold
import com.ledgerflow.core.designsystem.component.LfScreenTitle
import com.ledgerflow.core.designsystem.format.MoneyFormat
import com.ledgerflow.core.designsystem.format.TimeStamp
import com.ledgerflow.core.designsystem.theme.LfTheme
import com.ledgerflow.core.domain.inbox.InboxFilter
import com.ledgerflow.core.domain.inbox.PendingTransaction
import com.ledgerflow.core.domain.ingest.ExtractedDirection
import com.ledgerflow.core.domain.ingest.ExtractedTransaction
import com.ledgerflow.core.model.EntrySource
import com.ledgerflow.core.model.Money
import com.ledgerflow.core.model.PendingStatus

/**
 * The approval queue (SPEC.md §5.1). P2-6.
 *
 * **Nothing on this screen has reached the ledger**, and that is the whole
 * point of it existing: Law 1 requires a human tap between an automated source
 * and a `ledger_entry`, and this is where the tap happens.
 *
 * ## Why a row is two lines
 *
 * The owner's standing brief: a row that presents one thing plus its actions
 * costs about two lines, not a card with a header and a row of pill buttons. A
 * queue exists to be scanned and emptied — if four items fill the screen, the
 * item is too big.
 *
 * The first version of this screen missed that by a line, measured on the
 * device: payee and amount, then provenance, then a third line of actions. What
 * bought the line back was moving **Discard onto the swipe** where §5.1 wanted
 * it anyway — with only Review and Approve left, the actions fit beside the
 * provenance. They are still [LfButtonStyle.Inline] inside an [LfActionRow], so
 * at font scale 2.0 they drop to their own line rather than clipping a label
 * (BUG9); two lines is the comfortable case, not a promise kept by truncating.
 *
 * One container for every row and one for the filter band — one shape per
 * screen, hairline border rather than elevation at this size.
 */
@Composable
public fun InboxScreen(
    state: InboxUiState,
    onEvent: (InboxEvent) -> Unit,
    onReview: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val snackbarHostState = remember { SnackbarHostState() }

    state.undoableDiscard?.let { discard ->
        LaunchedEffect(discard.pendingId) {
            val result = snackbarHostState.showSnackbar(
                message = "Discarded ${discard.label}",
                actionLabel = "Undo",
                duration = SnackbarDuration.Short,
            )
            onEvent(
                if (result == SnackbarResult.ActionPerformed) {
                    InboxEvent.UndoDiscard
                } else {
                    InboxEvent.UndoExpired
                },
            )
        }
    }

    state.message?.let { message ->
        LaunchedEffect(message) {
            snackbarHostState.showSnackbar(message)
            onEvent(InboxEvent.MessageShown)
        }
    }

    state.confirmation?.let { EraseConfirmationDialog(it, onEvent) }

    LfScaffold(modifier = modifier, snackbarHostState = snackbarHostState) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding),
            verticalArrangement = Arrangement.spacedBy(LfTheme.spacing.sm),
        ) {
            LfScreenTitle(
                title = "Inbox",
                subtitle = state.subtitle(),
                modifier = Modifier.padding(horizontal = LfTheme.spacing.md),
            )

            FilterBand(state, onEvent)

            if (state.canErase && state.rows.isNotEmpty()) EraseBand(state, onEvent)

            if (state.rows.isEmpty() && !state.loading) {
                LfEmptyState(
                    title = state.emptyTitle(),
                    body = state.emptyBody(),
                    modifier = Modifier.padding(horizontal = LfTheme.spacing.md),
                )
            } else {
                CandidateList(state, onEvent, onReview)
            }
        }
    }
}

/**
 * The queue itself.
 *
 * Split out of [InboxScreen] when the erase band pushed that function past
 * detekt's length limit. The division is the one `LedgerFlowShell` already
 * makes: the outer function decides what chrome is on screen, this decides what
 * is under it.
 */
@Composable
private fun CandidateList(
    state: InboxUiState,
    onEvent: (InboxEvent) -> Unit,
    onReview: (String) -> Unit,
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(horizontal = LfTheme.spacing.md),
        verticalArrangement = Arrangement.spacedBy(LfTheme.spacing.sm),
        contentPadding = PaddingValues(bottom = LfTheme.spacing.md),
    ) {
        items(
            items = state.rows,
            key = { it.id },
            // One row type, so the compiler can reuse every slot.
            contentType = { "pending" },
        ) { row ->
            SwipeableRow(
                row = row,
                selectable = state.canErase,
                selected = row.id in state.selected,
                onEvent = onEvent,
                onReview = onReview,
            )
        }
    }
}

/**
 * §5.1's filters — the ones that currently hold something.
 *
 * Chips rather than a segmented control: the options do not fit a segmented
 * control's fixed track at font scale 2.0 without truncating, and truncating a
 * label is BUG9. In an [LfActionRow] so they wrap as whole chips instead.
 *
 * **Adaptive, because four chips were two too many** (owner). `Suppressed` is
 * empty unless a payment arrived twice, and `Failed` is empty by construction —
 * no path in the app writes that status today. Both were permanent furniture
 * advertising screens with nothing on them.
 *
 * They are hidden by *count*, never by a hard-coded rule: `FAILED` stays
 * reachable for a cause that is genuinely terminal, so a chip removed outright
 * would hide those rows on the day something finally writes one. See
 * [InboxUiState.visibleFilters].
 *
 * The count rides the label — `Discarded · 4` — the same shape the Ledger's
 * bands use, so "is there anything in there" is answered without a tap.
 */
@Composable
private fun FilterBand(state: InboxUiState, onEvent: (InboxEvent) -> Unit) {
    LfActionRow(
        alignment = LfActionAlignment.Start,
        modifier = Modifier.padding(horizontal = LfTheme.spacing.md),
    ) {
        state.visibleFilters.forEach { filter ->
            val count = state.counts[filter] ?: 0
            LfChip(
                // No count on an empty chip: "Discarded · 0" is a label for a
                // thing that is not there, and the only empty chips drawn are
                // the two this row keeps on purpose.
                label = if (count > 0) "${filter.label()} · $count" else filter.label(),
                style = if (filter == state.filter) LfChipStyle.Selected else LfChipStyle.Assist,
                onClick = { onEvent(InboxEvent.FilterSelected(filter)) },
            )
        }
    }
}

/**
 * Erasing, on the three filters that permit it (CHANGE#1).
 *
 * A single inline row rather than a toolbar or an app-bar action mode: the
 * owner's brief is that chrome is charged to the scrolling content once per
 * band, and a selection mode that swaps the whole header would cost the list a
 * band's height on every filter change.
 *
 * `Erase all` is present whenever the filter has rows; `Erase N` and `Clear`
 * appear only with a selection, so the row is one control wide most of the
 * time. In an [LfActionRow] so they wrap as whole controls at font scale 2.0
 * (BUG9), and `Inline` because these are in-list actions rather than a screen's
 * primary one.
 *
 * **Neither button erases.** Both open the confirmation; only its confirm
 * destroys anything.
 */
@Composable
private fun EraseBand(state: InboxUiState, onEvent: (InboxEvent) -> Unit) {
    LfActionRow(
        alignment = LfActionAlignment.Start,
        modifier = Modifier.padding(horizontal = LfTheme.spacing.md),
    ) {
        LfButton(
            text = "Erase all",
            style = LfButtonStyle.Inline,
            enabled = !state.isWorking,
            onClick = { onEvent(InboxEvent.EraseAllRequested) },
        )
        if (state.hasSelection) {
            LfButton(
                text = "Erase ${state.selectionCount}",
                style = LfButtonStyle.Inline,
                enabled = !state.isWorking,
                onClick = { onEvent(InboxEvent.EraseSelectedRequested) },
            )
            LfButton(
                text = "Clear",
                style = LfButtonStyle.Inline,
                enabled = !state.isWorking,
                onClick = { onEvent(InboxEvent.SelectionCleared) },
            )
        }
    }
}

/**
 * The last thing between a tap and an irreversible delete.
 *
 * `Warning`, the emphasis otherwise reserved for the Recovery Kit and the bin's
 * purge, and it **names the count** — "Erase all?" without a number is a
 * question the user cannot answer (CLAUDE.md §7).
 *
 * It says what survives, because that is the part people get wrong: the
 * captured message stays and only the candidate goes, so the corpus a future
 * parser rule is written against is not what this destroys.
 */
@Composable
private fun EraseConfirmationDialog(
    confirmation: InboxConfirmation,
    onEvent: (InboxEvent) -> Unit,
) {
    val (title, body) = when (confirmation) {
        is InboxConfirmation.EraseSelected -> {
            val noun = if (confirmation.count == 1) "item" else "items"
            "Erase ${confirmation.count} $noun?" to
                "This cannot be undone. The captured messages themselves are kept."
        }

        is InboxConfirmation.EraseAll -> {
            val noun = if (confirmation.count == 1) "item" else "items"
            "Erase all ${confirmation.count} $noun?" to
                "Everything under ${confirmation.filter.label()} is destroyed for good. " +
                "This cannot be undone. The captured messages themselves are kept."
        }
    }

    LfDialog(
        title = title,
        body = body,
        confirmText = "Erase for good",
        emphasis = LfDialogEmphasis.Warning,
        onConfirm = { onEvent(InboxEvent.EraseConfirmed) },
        onDismiss = { onEvent(InboxEvent.EraseDismissed) },
    )
}

/**
 * §5.1's swipe-to-discard, wrapping the row.
 *
 * Discarding is the action a queue needs most and the one that costs the least
 * to undo, so it is the gesture rather than a button — which is also what buys
 * the row back a line: with Discard on the swipe, the remaining actions fit
 * beside the provenance instead of below it.
 *
 * Only `PENDING` rows swipe. A discarded row's gesture would be "discard it
 * again", and an approved one has a `ledger_entry` behind it that this screen
 * must not silently contradict.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SwipeableRow(
    row: PendingTransaction,
    selectable: Boolean,
    selected: Boolean,
    onEvent: (InboxEvent) -> Unit,
    onReview: (String) -> Unit,
) {
    if (row.status != PendingStatus.PENDING) {
        PendingRow(row, selectable, selected, onEvent, onReview)
        return
    }

    // A pending row on the Suppressed filter is selectable AND swipeable. The
    // checkbox lives inside the row, so the swipe still wraps the whole thing.
    val state = rememberSwipeToDismissBoxState()

    // Reacting to the settled value rather than vetoing in `confirmValueChange`,
    // which is deprecated. `reset()` snaps the box back so the row leaves
    // because the database said so and the list recomposed -- not because the
    // gesture animated it away. If the write is refused the row simply stays,
    // rather than vanishing from a screen it is still on.
    LaunchedEffect(state.currentValue) {
        if (state.currentValue != SwipeToDismissBoxValue.Settled) {
            onEvent(InboxEvent.Discarded(row.id, row.title()))
            state.reset()
        }
    }

    SwipeToDismissBox(
        state = state,
        backgroundContent = {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        LfTheme.colors.debit.copy(alpha = DISCARD_TRACK_ALPHA),
                        RoundedCornerShape(LfTheme.spacing.sm),
                    )
                    .padding(horizontal = LfTheme.spacing.md),
                contentAlignment = Alignment.CenterStart,
            ) {
                Text(
                    text = "Discard",
                    style = LfTheme.typography.label,
                    color = LfTheme.colors.textSecondary,
                )
            }
        },
    ) {
        PendingRow(row, selectable, selected, onEvent, onReview)
    }
}

/**
 * One candidate, in two lines.
 *
 * The owner's brief: a row presenting one thing plus its actions costs about two
 * lines, not a card with a header and a row of pills. So the payee and the
 * amount share the first line and the provenance shares the second **with the
 * actions** — which is only possible because Discard moved to the swipe. The
 * whole row opens review; the inline actions are shortcuts past it.
 *
 * `LfActionRow` still wraps the actions as whole controls, so at font scale 2.0
 * they drop to their own line rather than clipping a label (BUG9). Two lines is
 * the comfortable case, not a promise the layout has to keep by truncating.
 */
@Composable
private fun PendingRow(
    row: PendingTransaction,
    selectable: Boolean,
    selected: Boolean,
    onEvent: (InboxEvent) -> Unit,
    onReview: (String) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(LfTheme.colors.surfaceBase, RoundedCornerShape(LfTheme.spacing.sm))
            .border(
                HAIRLINE.dp,
                LfTheme.colors.outline,
                RoundedCornerShape(LfTheme.spacing.sm),
            )
            .padding(
                start = if (selectable) LfTheme.spacing.xs else LfTheme.spacing.md,
                end = LfTheme.spacing.md,
                top = LfTheme.spacing.sm,
                bottom = LfTheme.spacing.sm,
            ),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // The box is its own target and the body still opens review, which is
        // the difference from the bin: there, a row has no other action, so the
        // whole row toggles. Here it does, and stealing the tap would make the
        // Discarded filter's rows unopenable. The box carries its own padding so
        // the target is a full 48dp rather than the glyph's 20dp.
        if (selectable) {
            Checkbox(
                checked = selected,
                onCheckedChange = { onEvent(InboxEvent.SelectionToggled(row.id)) },
                colors = CheckboxDefaults.colors(checkedColor = LfTheme.colors.accent),
                modifier = Modifier.semantics {
                    contentDescription = if (selected) {
                        "Selected, ${row.title()}"
                    } else {
                        "Not selected, ${row.title()}"
                    }
                },
            )
        }

        Column(
            modifier = Modifier
                .weight(1f)
                .clickable { onReview(row.id) },
            verticalArrangement = Arrangement.spacedBy(LfTheme.spacing.xs),
        ) {
            RowBody(row, onEvent, onReview)
        }
    }
}

/**
 * The two lines a candidate shows.
 *
 * Split out of [PendingRow] when the selection checkbox pushed that function
 * past detekt's length limit -- which was the right signal rather than a
 * threshold to raise: the row now does two jobs, it is a selection target *and*
 * a summary, and they read better apart.
 */
@Composable
private fun RowBody(
    row: PendingTransaction,
    onEvent: (InboxEvent) -> Unit,
    onReview: (String) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = row.title(),
            style = LfTheme.typography.bodyL,
            color = LfTheme.colors.textPrimary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false).padding(end = LfTheme.spacing.sm),
        )
        Text(
            text = row.amountLabel(),
            style = LfTheme.typography.amountM,
            color = row.amountColor(),
            maxLines = 1,
        )
    }

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Both weighted, and that is not arbitrary: `LfActionRow` is a FlowRow
        // that calls `fillMaxWidth()` on itself, so an unweighted one beside a
        // Text claims the whole line and squeezes the text to nothing -- which
        // is exactly what the first device screenshot showed, provenance
        // silently gone. Weighting both makes them share the line, and the
        // FlowRow still wraps its controls whole inside its half at font scale
        // 2.0 (BUG9).
        Text(
            // The stamp leads, and that ordering is the whole decision: this
            // line is `maxLines = 1` and shares the row with the actions, so
            // something gets ellipsised on a long one. Putting when-it-happened
            // first means the account number is what goes, which is the least
            // useful of the three and the one still visible on the review
            // screen.
            text = row.detailLine(),
            style = LfTheme.typography.label,
            color = LfTheme.colors.textTertiary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f).padding(end = LfTheme.spacing.sm),
        )
        RowActions(row, onEvent, onReview, Modifier.weight(1f))
    }
}

/**
 * Inline, on one edge, wrapping as whole controls.
 *
 * Approve is offered only when the candidate needs no decisions — an amount and
 * a book. Anything else goes through review, because the ledger must never be
 * handed a book that was guessed (Law 2). Discard is not here: it is the swipe.
 */
@Composable
private fun RowActions(
    row: PendingTransaction,
    onEvent: (InboxEvent) -> Unit,
    onReview: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    LfActionRow(alignment = LfActionAlignment.End, modifier = modifier) {
        when (row.status) {
            PendingStatus.PENDING -> {
                LfButton(
                    text = "Review",
                    style = LfButtonStyle.Inline,
                    onClick = { onReview(row.id) },
                )
                if (row.isOneTapApprovable) {
                    LfButton(
                        text = "Approve",
                        style = LfButtonStyle.Inline,
                        onClick = { onEvent(InboxEvent.Approved(row.id)) },
                    )
                }
            }

            PendingStatus.DISCARDED -> LfButton(
                text = "Restore",
                style = LfButtonStyle.Inline,
                onClick = { onEvent(InboxEvent.Restored(row.id)) },
            )

            // Approved rows are history and failed ones are not actionable
            // yet -- nothing writes FAILED (see InboxFilter). No actions rather
            // than disabled ones: a control that cannot do anything is worse
            // than its absence.
            PendingStatus.APPROVED, PendingStatus.FAILED -> Unit
        }
    }
}

private fun InboxFilter.label(): String = when (this) {
    InboxFilter.PENDING -> "Pending"
    InboxFilter.SUPPRESSED -> "Suppressed"
    InboxFilter.DISCARDED -> "Discarded"
    InboxFilter.FAILED -> "Failed"
}

private fun InboxUiState.subtitle(): String? =
    if (pendingCount == 0) null else "$pendingCount waiting"

private fun InboxUiState.emptyTitle(): String = when (filter) {
    InboxFilter.PENDING -> "Nothing to review"
    InboxFilter.SUPPRESSED -> "No duplicates"
    InboxFilter.DISCARDED -> "Nothing discarded"
    InboxFilter.FAILED -> "Nothing failed"
}

private fun InboxUiState.emptyBody(): String = when (filter) {
    InboxFilter.PENDING ->
        "Bank messages appear here for you to approve before they reach your ledger."
    InboxFilter.SUPPRESSED ->
        "When one payment arrives twice, the copy is kept here rather than discarded."
    InboxFilter.DISCARDED -> "Items you reject stay here for 30 days."
    InboxFilter.FAILED -> "Messages the pipeline could not process at all would appear here."
}

/** The merchant if the parser found one, and an honest placeholder if it did not. */
private fun PendingTransaction.title(): String =
    extracted.merchantRaw?.trim()?.takeIf { it.isNotEmpty() } ?: "Unknown payee"

private fun PendingTransaction.amountLabel(): String {
    val amount = extracted.amount ?: return "—"
    val ledger = extracted.direction.toLedgerOrNull()
        ?: return MoneyFormat.plain(amount.minor, extracted.currency ?: "INR")
    return MoneyFormat.directional(amount.minor, extracted.currency ?: "INR", ledger)
}

@Composable
private fun PendingTransaction.amountColor() = when (extracted.direction) {
    ExtractedDirection.DEBIT -> LfTheme.colors.debit
    ExtractedDirection.CREDIT -> LfTheme.colors.credit
    // An unread direction is not a book, and colouring it as one would assert
    // something the parser did not find.
    ExtractedDirection.UNKNOWN -> LfTheme.colors.textSecondary
}

/**
 * Where it came from and how sure the parser was.
 *
 * `needs_manual_fill` is said in words rather than shown as a badge: it is the
 * one thing on the row that changes what the user has to do next.
 */
/**
 * When it happened, then where it came from.
 *
 * Shown on **every** filter -- pending, suppressed, discarded and failed --
 * because "which of these is the one from this morning" is the question a
 * queue of near-identical bank messages actually raises, and it is the same
 * question on a discarded row as on a live one.
 *
 * [TimeStamp.ofCapture] rather than [TimeStamp.of]: every bank SMS in the
 * corpus states a date and no clock, so `occurredAt` is midnight and the naive
 * stamp would read `12:00 am` on essentially every row. See its KDoc.
 */
@Composable
private fun PendingTransaction.detailLine(): String {
    val stamp = extracted.occurredAt
        // The message named a day, so keep it and take the clock from capture.
        ?.let { TimeStamp.ofCapture(it, capturedAt = createdAt, withDate = true) }
        // It named nothing at all; capture is the only time there is.
        ?: TimeStamp.of(createdAt, withDate = true)
    return "$stamp · ${provenance()}"
}

private fun PendingTransaction.provenance(): String {
    val origin = when (source) {
        EntrySource.SMS -> "SMS"
        EntrySource.NOTIFICATION -> "Notification"
        EntrySource.OCR -> "Receipt"
        EntrySource.MANUAL -> "Manual"
        EntrySource.IMPORT -> "Import"
    }
    return buildList {
        add(origin)
        if (needsManualFill) add("needs details")
        if (isSuppressed) add("duplicate")
        extracted.accountLast4?.let { add("A/C $it") }
    }.joinToString(" · ")
}

private const val HAIRLINE = 1

/** The swipe track, tinted toward the debit colour without shouting. */
private const val DISCARD_TRACK_ALPHA = 0.12f

// ── Previews ─────────────────────────────────────────────────────────────────
//
// Every top-level screen carries all three, per CLAUDE.md §5. Font scale is the
// one that matters here: three inline actions on a row is exactly the shape
// BUG9 came from.

private val sampleRows = listOf(
    PendingTransaction(
        id = "1",
        source = EntrySource.SMS,
        extracted = ExtractedTransaction(
            amount = Money(200L),
            currency = "INR",
            direction = ExtractedDirection.DEBIT,
            merchantRaw = "RAMESH KUMAR",
            accountLast4 = "6402",
            confidence = 0.9,
        ),
        confidence = 0.9,
        status = PendingStatus.PENDING,
        needsManualFill = false,
        suppressedById = null,
        createdAt = 0L,
        reviewedAt = null,
        approvedEntryId = null,
    ),
    PendingTransaction(
        id = "2",
        source = EntrySource.SMS,
        extracted = ExtractedTransaction(),
        confidence = 0.0,
        status = PendingStatus.PENDING,
        needsManualFill = true,
        suppressedById = null,
        createdAt = 0L,
        reviewedAt = null,
        approvedEntryId = null,
    ),
)

@PreviewScreenSizes
@PreviewFontScale
@PreviewLightDark
@Composable
private fun InboxScreenPreview() {
    LfTheme {
        InboxScreen(
            state = InboxUiState(rows = sampleRows, pendingCount = 2, loading = false),
            onEvent = {},
            onReview = {},
        )
    }
}

@PreviewFontScale
@PreviewLightDark
@Composable
private fun InboxEmptyPreview() {
    LfTheme {
        InboxScreen(
            state = InboxUiState(loading = false),
            onEvent = {},
            onReview = {},
        )
    }
}
