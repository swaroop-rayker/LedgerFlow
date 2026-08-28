package com.ledgerflow.feature.ledger

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.PreviewFontScale
import androidx.compose.ui.tooling.preview.PreviewLightDark
import androidx.compose.ui.tooling.preview.PreviewScreenSizes
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.paging.LoadState
import androidx.paging.PagingData
import androidx.paging.compose.LazyPagingItems
import androidx.paging.compose.collectAsLazyPagingItems
import com.ledgerflow.core.designsystem.component.LfButton
import com.ledgerflow.core.designsystem.component.LfButtonStyle
import com.ledgerflow.core.designsystem.component.LfCategoryDot
import com.ledgerflow.core.designsystem.component.LfDialog
import com.ledgerflow.core.designsystem.component.LfEmptyState
import com.ledgerflow.core.designsystem.component.LfIconButton
import com.ledgerflow.core.designsystem.component.LfScreenTitle
import com.ledgerflow.core.designsystem.component.LfSegmentedControl
import com.ledgerflow.core.designsystem.format.MoneyFormat
import com.ledgerflow.core.designsystem.format.TimeStamp
import com.ledgerflow.core.designsystem.icon.LfIcons
import com.ledgerflow.core.designsystem.theme.LfTheme
import com.ledgerflow.core.domain.inbox.PendingTransaction
import com.ledgerflow.core.domain.ledger.DraftSummary
import com.ledgerflow.core.model.LedgerListItem
import com.ledgerflow.core.model.LedgerType
import com.ledgerflow.core.model.Money
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

/**
 * The two ledgers (SPEC.md §5.5, §9.3, Law 2).
 *
 * An `Expenses | Income` control over **two separate queries against two
 * separate views**, never one list with a sign column and never a combined
 * total. The tab is a partition selector, not a filter over shared data — that
 * distinction is the whole of ADR-0002 at the UI layer, and it is why there is
 * no "All" segment and no running balance anywhere on this screen.
 *
 * Stateless in the sense CLAUDE.md §5 means: state in, one event lambda out.
 * [entries] arrives as a `Flow` rather than as `LazyPagingItems` because
 * `LazyPagingItems` is a composition-scoped holder — hoisting it to the caller
 * moves the call site without hoisting any state, and it makes the previews
 * below need a fake pager instead of a list.
 */
@Composable
public fun LedgerScreen(
    state: LedgerUiState,
    entries: Flow<PagingData<LedgerListItem>>,
    onEvent: (LedgerEvent) -> Unit,
    onOpenDraft: (String) -> Unit,
    onReviewCandidate: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val items = entries.collectAsLazyPagingItems()

    state.confirmation?.let { Confirmation(it, onEvent) }

    // `sm` between the header bands, not `md`. The list is the only thing on
    // this screen anyone scrolls, so every step of the gap scale above it is
    // charged to the list twice (CLAUDE.md §5).
    Column(
        modifier = modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(LfTheme.spacing.sm),
    ) {
        LfScreenTitle(title = "Ledger")

        LfSegmentedControl(
            options = listOf("Expenses", "Income"),
            selectedIndex = state.ledger.ordinal,
            onSelect = { onEvent(LedgerEvent.LedgerSelected(LedgerType.entries[it])) },
            modifier = Modifier.padding(horizontal = LfTheme.spacing.lg),
        )

        state.message?.let { MessageBanner(message = it, onEvent = onEvent) }

        EntryList(
            items = items,
            state = state,
            onEvent = onEvent,
            onOpenDraft = onOpenDraft,
            onReviewCandidate = onReviewCandidate,
        )
    }
}

/**
 * Whichever question is open.
 *
 * Two wordings, not one with the noun swapped. Deleting a saved entry removes
 * something the user committed and can in principle be recovered; discarding a
 * draft throws away something they were still typing and nothing else holds a
 * copy — that is BUG6's territory, and it should not read like routine
 * housekeeping.
 */
@Composable
private fun Confirmation(confirmation: LedgerConfirmation, onEvent: (LedgerEvent) -> Unit) {
    val (title, body, confirmText) = when (confirmation) {
        is LedgerConfirmation.DeleteEntry -> Triple(
            "Delete this entry?",
            "${confirmation.label} will no longer appear in your ledger or totals.",
            "Delete",
        )
        // "This cannot be undone" is the sentence that matters, and it is the
        // same sentence the Inbox's erase dialog uses. Since the two kinds of
        // unsaved row share one section, a user reaching this dialog has very
        // likely just discarded a *candidate* -- which lands in the Inbox's
        // Discarded filter and is restorable for 30 days. Nothing in
        // "throws away what you had typed" told them this one is different.
        is LedgerConfirmation.DiscardDraft -> Triple(
            "Discard this unsaved entry?",
            "${confirmation.label} was never saved. Discarding it throws away " +
                "what you had typed, and this cannot be undone.",
            "Discard",
        )
    }
    LfDialog(
        title = title,
        body = body,
        confirmText = confirmText,
        onConfirm = { onEvent(LedgerEvent.ConfirmationAccepted) },
        onDismiss = { onEvent(LedgerEvent.ConfirmationDismissed) },
    )
}

/** A refusal, in a line the user can dismiss. */
@Composable
private fun MessageBanner(message: String, onEvent: (LedgerEvent) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = LfTheme.spacing.lg),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = message,
            style = LfTheme.typography.bodyM,
            color = LfTheme.colors.warn,
            modifier = Modifier.weight(1f),
        )
        LfButton(
            text = "OK",
            style = LfButtonStyle.Text,
            onClick = { onEvent(LedgerEvent.MessageDismissed) },
        )
    }
}

/**
 * The book, newest first, grouped into recency bands.
 *
 * The grouping is read off the query's own ordering (`local_date DESC,
 * occurred_at DESC`) rather than regrouping a materialised list: a header is
 * emitted wherever a row's band differs from the row above it, which is a
 * comparison against one neighbour and needs nothing beyond the loaded pages.
 * Regrouping would mean holding the whole ledger, which is what CLAUDE.md §8
 * forbids and what Paging is here to avoid.
 *
 * The snapshot is read to *declare* items; `items[index]` is read inside each
 * item's content, because that indexed access is what signals Paging to fetch
 * the next page. Declaring from the snapshot alone would build a list that
 * never grows.
 */
@Composable
private fun EntryList(
    items: LazyPagingItems<LedgerListItem>,
    state: LedgerUiState,
    onEvent: (LedgerEvent) -> Unit,
    onOpenDraft: (String) -> Unit,
    onReviewCandidate: (String) -> Unit,
) {
    // Nothing is drawn until the drafts query has answered. Composing the list
    // first and prepending the unsaved section afterwards put that section
    // above the scroll anchor and out of sight -- see [LedgerUiState.isLoaded].
    if (!state.isLoaded) return

    // `itemCount == 0` alone is also true for the first frame, before the first
    // page has loaded — showing an empty state there would flash "nothing here"
    // at a user whose ledger is full.
    val settled = items.loadState.refresh !is LoadState.Loading
    // The empty state is about the *book*, so unsaved entries do not count
    // towards it — but it must not hide them either. With drafts on screen the
    // list still renders; without them it is genuinely empty.
    if (settled && state.showsNothing(items.itemCount)) {
        EmptyBook(state)
        return
    }

    val snapshot = items.itemSnapshotList
    // Keyed on the book, so switching tabs builds a new list rather than
    // carrying the old one's scroll position into it. Two disjoint books are
    // two lists (Law 2), and it also means a book with more unsaved entries
    // than the last one cannot reproduce the prepend problem above.
    key(state.ledger) {
    LazyColumn(
        // `md`, not the `lg` the taxonomy list uses. That screen holds a dozen
        // items; this one holds a row per transaction and has to fit a name, an
        // amount, a timestamp and a control on every one of them. 16dp of side
        // inset instead of 24dp is 16dp back across the row, which is the
        // difference between the amount sitting beside the timestamp and being
        // pushed onto a line of its own.
        modifier = Modifier.fillMaxSize().padding(horizontal = LfTheme.spacing.md),
        verticalArrangement = Arrangement.spacedBy(LfTheme.spacing.xs),
        contentPadding = PaddingValues(bottom = LfTheme.spacing.xxl),
    ) {
        unsavedBand(state, onOpenDraft, onReviewCandidate, onEvent)

        for (index in 0 until items.itemCount) {
            val item = snapshot.getOrNull(index) ?: continue
            val previous = if (index == 0) null else snapshot.getOrNull(index - 1)

            val bucket = recencyBucketOf(item.localDate, state.today)
            val previousBucket = previous?.let { recencyBucketOf(it.localDate, state.today) }

            if (bucket != previousBucket) {
                stickyHeader(key = "band-${bucket.name}", contentType = BAND_HEADER_TYPE) {
                    BandHeader(bucket)
                }
            }

            item(key = item.id, contentType = ENTRY_TYPE) {
                // The indexed read is the prefetch signal. The snapshot value is
                // the fallback for the window between a page being dropped and
                // the list being told about it.
                EntryRow(
                    item = items[index] ?: item,
                    bucket = bucket,
                    onEvent = onEvent,
                )
            }
        }
    }
    }
}

/**
 * A recency band boundary.
 *
 * Opaque on `surfaceBase` because it sticks: a translucent header lets the rows
 * it is pinned over scroll through it, which reads as a rendering fault rather
 * than as a header.
 *
 * No band total. A per-book subtotal would be legal under Law 2 — it nets
 * nothing — but it is a second query per visible band and it is not what this
 * screen is for yet.
 */
@Composable
private fun BandHeader(bucket: RecencyBucket) {
    BandLabel(bucket.label)
}

/**
 * One captured payment waiting for the tap Law 1 requires (CHANGE#2).
 *
 * **Read-only, and one line shorter than the draft row.** A draft offers
 * Discard here because the entry form is where it is finished; a candidate
 * offers nothing, because approving it is a decision that belongs on the review
 * screen where the parsed fields are visible. The Ledger's job is to say *that*
 * it exists.
 *
 * No book chip and no colour split: the row may be a debit, a credit, or one
 * the parser could not read — and the last kind shows on both tabs. Colouring
 * it as spend on the Expenses tab would be the guess Law 2 exists to prevent,
 * made in pixels.
 */
@Composable
private fun CandidateRow(
    candidate: PendingTransaction,
    currencyCode: String,
    onReview: (String) -> Unit,
) {
    // Merchant and category on one line, exactly as the draft row above does
    // it -- a candidate the user has filed should read the same as a draft they
    // filed, since the section no longer tells them apart by position (owner).
    val title = listOfNotNull(
        candidate.effective.merchantRaw?.takeIf { it.isNotBlank() },
        candidate.editedCategoryName,
    ).joinToString(SEPARATOR).takeIf { it.isNotEmpty() } ?: UNREAD_MESSAGE
    val amount = candidate.effective.amount
        ?.let { MoneyFormat.symbolised(it.minor, currencyCode) }
        // §5.1's never-drop row: nothing was extracted, so there is no figure to
        // show. An em dash rather than a zero -- "0" is an amount, and this is
        // the absence of one.
        ?: NO_AMOUNT
    // Every bank SMS in the corpus states a date and no clock, so `occurredAt`
    // is midnight and the naive stamp would read "12:00 am" on every row. See
    // TimeStamp.ofCapture.
    val stamp = candidate.effective.occurredAt
        ?.let { TimeStamp.ofCapture(it, capturedAt = candidate.createdAt, withDate = true) }
        ?: TimeStamp.of(candidate.createdAt, withDate = true)
    val detail = "$stamp$SEPARATOR$TO_REVIEW_MARKER"

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(LfTheme.colors.surfaceRaised, RoundedCornerShape(LfTheme.spacing.sm))
            .border(
                HAIRLINE.dp,
                LfTheme.colors.outline,
                RoundedCornerShape(LfTheme.spacing.sm),
            )
            .clickable { onReview(candidate.id) }
            .clearAndSetSemantics {
                contentDescription = "$TO_REVIEW_MARKER, $amount, $title, $stamp. " +
                    "Opens the review screen."
            }
            .padding(
                start = LfTheme.spacing.md,
                end = LfTheme.spacing.md,
                top = LfTheme.spacing.sm,
                bottom = LfTheme.spacing.sm,
            ),
        horizontalArrangement = Arrangement.spacedBy(LfTheme.spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = LfTheme.typography.bodyL,
                color = LfTheme.colors.textPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                // The marker is what stops two rows that look alike opening
                // different screens with no warning. It rides the line the
                // stamp is already on, so it costs no height.
                text = detail,
                style = LfTheme.typography.label,
                color = LfTheme.colors.textTertiary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Text(
            text = amount,
            style = LfTheme.typography.amountM,
            color = LfTheme.colors.textSecondary,
            maxLines = 1,
        )
    }
}

private fun LazyListScope.unsavedBand(
    state: LedgerUiState,
    onOpenDraft: (String) -> Unit,
    onReviewCandidate: (String) -> Unit,
    onEvent: (LedgerEvent) -> Unit,
) {
    if (state.unsaved.isEmpty()) return

    stickyHeader(key = "band-unsaved", contentType = BAND_HEADER_TYPE) {
        BandLabel("Unsaved · ${state.unsaved.size}")
    }
    items(
        count = state.unsaved.size,
        key = { state.unsaved[it].key },
        // Distinct content types per kind: the two rows have different shapes,
        // and telling the compiler they are one slot would make it reuse a
        // draft's layout for a candidate.
        contentType = {
            when (state.unsaved[it]) {
                is UnsavedRow.Draft -> PENDING_TYPE
                is UnsavedRow.Candidate -> CANDIDATE_TYPE
            }
        },
    ) { index ->
        when (val row = state.unsaved[index]) {
            is UnsavedRow.Draft -> PendingRow(
                draft = row.summary,
                currencyCode = state.currencyCode,
                onOpen = onOpenDraft,
                onEvent = onEvent,
            )

            is UnsavedRow.Candidate -> CandidateRow(
                candidate = row.candidate,
                currencyCode = state.currencyCode,
                onReview = onReviewCandidate,
            )
        }
    }
}

/**
 * Whether this book has nothing at all to show.
 *
 * Named rather than left inline: the empty state is about the *book*, so the
 * unsaved section does not count towards it — but it must not hide it either,
 * and `&& isEmpty()` reads as boilerplate rather than as that rule.
 */
private fun LedgerUiState.showsNothing(entryCount: Int): Boolean =
    entryCount == 0 && unsaved.isEmpty()

/** The header's look, shared by the recency bands and the unsaved section. */
@Composable
private fun BandLabel(text: String) {
    Text(
        text = text,
        style = LfTheme.typography.label,
        color = LfTheme.colors.textSecondary,
        modifier = Modifier
            .fillMaxWidth()
            .background(LfTheme.colors.surfaceBase)
            .padding(top = LfTheme.spacing.sm, bottom = LfTheme.spacing.xs),
    )
}

/**
 * One unsaved entry.
 *
 * The whole row is tappable and opens *that* draft in the entry form — the
 * screen hands an id upwards and the graph decides where it goes, because
 * features never reach each other directly (CLAUDE.md §3).
 *
 * Visually the same card as a committed entry, with the accent border and no
 * timestamp. It is the same kind of thing at a different stage, so a second
 * shape would read as a second design (CLAUDE.md §5) — but it must not read as
 * *filed*, which is what the border and the "not saved yet" line are for.
 */
@Composable
private fun PendingRow(
    draft: DraftSummary,
    currencyCode: String,
    onOpen: (String) -> Unit,
    onEvent: (LedgerEvent) -> Unit,
) {
    val spacing = LfTheme.spacing
    val colors = LfTheme.colors
    val shape = RoundedCornerShape(spacing.cornerLarge)

    val title = listOfNotNull(draft.merchantName, draft.categoryName)
        .joinToString(SEPARATOR)
        .ifEmpty { UNTITLED_DRAFT }
    val amount = MoneyFormat.symbolised(draft.amount.minor, currencyCode)
    val label = amount + SEPARATOR + title
    // Always with the date, unlike a committed row. A pending row sits under
    // "Unsaved", which says nothing about *when* -- so there is no header above
    // it carrying the date for it, and dropping it would leave the row unable
    // to answer the question at all.
    // The marker rides the stamp's line, which the row already had, so telling
    // a draft from a candidate in the merged section costs no height
    // (owner, CHANGE#1). Kept separate from `stamp` because the spoken string
    // below already opens with the marker, and one built from `detail` said
    // "Draft, ... 2:49 pm, Draft" -- heard on the device, where a duplicated
    // word is more obviously wrong than it looks in source.
    val stamp = occurredStamp(draft.datedAt, withDate = true)
    val detail = stamp + SEPARATOR + DRAFT_MARKER

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(colors.surfaceRaised, shape)
            .border(HAIRLINE.dp, colors.accent, shape)
            .padding(start = spacing.md, end = spacing.xs, top = spacing.sm, bottom = spacing.sm),
        horizontalArrangement = Arrangement.spacedBy(spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            modifier = Modifier
                .weight(1f)
                .clickable { onOpen(draft.id) }
                .clearAndSetSemantics {
                    contentDescription =
                        "$DRAFT_MARKER, $amount, $title, $stamp. Opens to finish it."
                },
            horizontalArrangement = Arrangement.spacedBy(spacing.sm),
            verticalAlignment = Alignment.Top,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = LfTheme.typography.bodyL,
                    color = colors.textPrimary,
                )
                EntryRowBody(
                    leading = { PendingStatus(detail) },
                    trailing = { PendingAmount(amount) },
                )
            }
        }

        LfIconButton(
            icon = LfIcons.Delete,
            modifier = Modifier.align(Alignment.CenterVertically),
            contentDescription = "Discard unsaved $title",
            onClick = { onEvent(LedgerEvent.DiscardRequested(draft.id, label)) },
        )
    }
}

/**
 * One entry.
 *
 * **Two lines, and the same two on every row.** Naming across the top; the
 * timestamp and the amount sharing the second line, stamp left and amount
 * right. A uniform height is what makes a list scannable, and this shape holds
 * it whatever the row contains.
 *
 * It took two measured failures to arrive at, both worth recording because each
 * looks like the fix for the other:
 *
 * 1. Timestamp beside the note, amount in a column of its own. A row of
 *    `-₹1,54,000.00` squeezed the left column until the *stamp* ellipsised to
 *    "18 Aug, 5:32 …" — the one field the row exists to add was the field that
 *    got clipped.
 * 2. Timestamp stacked under the amount. That fixed the clipping and made the
 *    right-hand column as wide as **the wider of the two**, which for anything
 *    under about ₹100 is the *stamp*, not the amount. So every row paid a
 *    ~120dp column whatever it held, and once the delete control took its 48dp
 *    the naming block fell under its floor and every row stacked into three
 *    lines.
 *
 * Neither field competes here: the name owns a line, and the stamp and amount
 * are the only two things on the other one. [EntryRowBody] still measures them,
 * so at large font scales they stack rather than clip — but at ordinary scales
 * they always fit, which is what keeps the list dense.
 *
 * Still denser than the Organise cards (CLAUDE.md §5): those spend their second
 * line on a row of controls, this one spends it on data.
 *
 * The swatch column is reserved even when there is no category, so names stay
 * in a scannable column instead of stepping in and out by 24dp on every unfiled
 * row.
 */
@Composable
private fun EntryRow(
    item: LedgerListItem,
    bucket: RecencyBucket,
    onEvent: (LedgerEvent) -> Unit,
) {
    val spacing = LfTheme.spacing
    val colors = LfTheme.colors
    val shape = RoundedCornerShape(spacing.cornerLarge)

    // Merchant *and* category, on one line. They were stacked — merchant as the
    // title, category demoted to the grey line — which read as a hierarchy that
    // does not exist: they are two independent facts about the same entry, and
    // most rows have both. Either alone degrades to just itself, with no
    // dangling separator.
    //
    // The category half reads displayCategoryName, not categoryName directly
    // (ADR-0018): an itemised entry has no category of its own, so its largest
    // line item's stands in, with "+2" appended when the bill spans more than
    // one. Without this an itemised row fell into the same UNFILED bucket as a
    // genuinely uncategorised one — a Reliance Fresh bill split across
    // groceries and electronics read as filed under nothing at all.
    val categoryLabel = item.displayCategoryName?.let { name ->
        item.additionalCategoryCount?.let { extra -> "$name +$extra" } ?: name
    }
    val title = listOfNotNull(item.merchantName, categoryLabel)
        .joinToString(SEPARATOR)
        .ifEmpty { UNFILED }
    // The band header already says "Today"; repeating the date under it is
    // noise, and dropping it is also what keeps the second line comfortable on
    // the rows the user looks at most.
    val stamp = occurredStamp(item.occurredAt, withDate = bucket.needsDate)
    val note = item.note?.takeIf { it.isNotBlank() }
    val detail = listOfNotNull(stamp, note).joinToString(SEPARATOR)

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(colors.surfaceRaised, shape)
            .border(HAIRLINE.dp, colors.outline, shape)
            // Tighter on the trailing edge: the delete control is a 48dp touch
            // target around a 16dp glyph, so it carries ~16dp of its own inset
            // and a full `md` on top would leave the icon looking adrift.
            .padding(start = spacing.md, end = spacing.xs, top = spacing.sm, bottom = spacing.sm),
        horizontalArrangement = Arrangement.spacedBy(spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // The merged announcement covers the *content*, not the whole row.
        // `clearAndSetSemantics` clears its descendants, so leaving it on the
        // outer Row would have swallowed the delete button's own label and left
        // a screen-reader user with a control they cannot identify or reach.
        Row(
            modifier = Modifier
                .weight(1f)
                .clearAndSetSemantics { contentDescription = item.spokenAs(title, detail) },
            horizontalArrangement = Arrangement.spacedBy(spacing.sm),
            // `Top`, not `CenterVertically`. The swatch belongs to the name, and
            // centring it against a two-line row parked it between the two
            // lines -- reading as though it labelled the timestamp as much as
            // the merchant. Reported on device.
            verticalAlignment = Alignment.Top,
        ) {
            EntrySwatch(item)

            Column(modifier = Modifier.weight(1f)) {
                // No `maxLines` and no ellipsis: merchant and category are the
                // row's identity, and clipping them is the BUG9 failure one
                // level up from a button label. They wrap instead, which costs
                // a line on the rare long pairing and never hides which entry
                // this is. It has the full width here, so that is rare.
                Text(
                    text = title,
                    style = LfTheme.typography.bodyL,
                    color = colors.textPrimary,
                )
                EntryRowBody(
                    leading = { EntryDetail(detail = detail) },
                    trailing = { EntryAmount(item = item) },
                )
            }
        }

        // An icon, not an `Inline` text action in an `LfActionRow`. The taxonomy
        // cards use the latter, but they carry three actions over a list of a
        // dozen items; this carries one over a list without end, and a text
        // button would add a whole line to every row on the screen whose entire
        // job is being scanned. `LfIconButton` is already a 48dp target and the
        // rows are taller than that, so it costs no height at all.
        LfIconButton(
            icon = LfIcons.Delete,
            // Stays centred on the row: unlike the swatch it belongs to the
            // entry as a whole rather than to the naming line.
            modifier = Modifier.align(Alignment.CenterVertically),
            contentDescription = "Delete " + title,
            onClick = { onEvent(LedgerEvent.DeleteRequested(item.id, item.deleteLabel(title))) },
        )
    }
}

/**
 * The category's colour, on the naming line.
 *
 * Centred inside a box exactly one naming line tall, rather than nudged down by
 * a hand-picked padding. The line box is what the swatch has to align with, and
 * it is the thing that grows with the font scale — a fixed offset would drift
 * away from the text it is aligning to the moment the user changes their type
 * size.
 *
 * The slot is reserved even for an entry filed under nothing, so names stay in
 * a scannable column instead of stepping in and out by 24dp on every unfiled
 * row.
 */
@Composable
private fun EntrySwatch(item: LedgerListItem) {
    val namingLine = with(LocalDensity.current) {
        LfTheme.typography.bodyL.lineHeight.toDp()
    }
    Box(
        modifier = Modifier.height(namingLine),
        contentAlignment = Alignment.Center,
    ) {
        // The display variants, not the raw column: an itemised entry's swatch
        // comes from its largest line item's category (ADR-0018), same as the
        // title text above does.
        val categoryName = item.displayCategoryName
        val categoryColor = item.displayCategoryColorArgb
        if (categoryName != null && categoryColor != null) {
            LfCategoryDot(name = categoryName, colorArgb = categoryColor)
        } else {
            Box(
                modifier = Modifier
                    .size(LfTheme.spacing.lg)
                    .border(HAIRLINE.dp, LfTheme.colors.outline, CircleShape),
            )
        }
    }
}

/**
 * When the draft is dated.
 *
 * **The stamp alone, in the accent that marks the row unfiled.** It said
 * "19 Aug, 10:14 am · Not saved yet" for one build, and on device that
 * ellipsised to "19 Aug, 10:14 am · Not saved…" — the words survived and the
 * date, which is the only thing this line was added to show, was the part
 * clipped.
 *
 * Dropping them costs nothing. "Unsaved" is already said three times over: by
 * the section header, by the accent border around the row, and by this text
 * being accent-coloured where a committed row's is grey. The row also announces
 * itself as "Unsaved, …" to a screen reader, which is the one place the visual
 * cues do not reach.
 */
@Composable
private fun PendingStatus(stamp: String) {
    Text(
        text = stamp,
        style = LfTheme.typography.label,
        color = LfTheme.colors.accent,
        maxLines = NOTE_MAX_LINES,
        overflow = TextOverflow.Ellipsis,
    )
}

/**
 * A draft's amount.
 *
 * **Unsigned, unlike a committed row.** The `-`/`+` prefix reads off the book
 * an entry was filed into, and a draft has not been filed into one yet -- the
 * form's `Expense | Income` control is still editable. A glyph here would be
 * claiming something the entry has not decided.
 */
@Composable
private fun PendingAmount(amount: String) {
    Text(
        text = amount,
        style = LfTheme.typography.amountM,
        color = LfTheme.colors.textSecondary,
        textAlign = TextAlign.End,
    )
}

/** When it happened, and anything the user wrote about it. */
@Composable
private fun EntryDetail(detail: String) {
    // Truncates, and that is a deliberate asymmetry: the stamp in front of it
    // is a fixed width the layout can plan for, so the only thing that can
    // overflow is the note — free text, and the one field on this row the user
    // did not pick from a list.
    Text(
        text = detail,
        style = LfTheme.typography.label,
        color = LfTheme.colors.textTertiary,
        maxLines = NOTE_MAX_LINES,
        overflow = TextOverflow.Ellipsis,
    )
}

/** How much, and which way. */
@Composable
private fun EntryAmount(item: LedgerListItem) {
    val colors = LfTheme.colors
    Text(
        // The sign is a glyph, not a stored value: `amount_minor` is positive
        // in both books and direction is carried by `ledger` (Law 2). Colour
        // alone would carry it, but colour alone fails §9.6 for a colour-blind
        // reader — the sign is the same redundancy the category swatch's
        // initial provides for its colour.
        text = MoneyFormat.directional(item.amount.minor, item.currency, item.ledger),
        style = LfTheme.typography.amountM,
        color = if (item.ledger == LedgerType.DEBIT) colors.debit else colors.credit,
        textAlign = TextAlign.End,
    )
}

/**
 * [leading] beside [trailing], or above it when both will not fit.
 *
 * **Measured per row rather than switched on a font-scale threshold**, which is
 * what CLAUDE.md §5 asks for and what a threshold gets wrong in both
 * directions: at font scale 2.0 `-₹18,752.00` is wider than the whole row, so
 * as an unweighted child it takes every pixel and the leading block collapses
 * to about one character. A global threshold would fix that row and also stack
 * `-₹69.00`, which had room to spare at the very same scale.
 *
 * So [trailing] is measured first against the real row width, and [leading]
 * gets what is left if that clears the width [leading] actually needs on one
 * line; otherwise the two stack and each takes the full width. The decision is
 * per row, per amount and per stamp — degrading by re-laying-out whole blocks,
 * never by clipping a label (BUG9).
 *
 * The floor is [leading]'s own `maxIntrinsicWidth` rather than a constant,
 * because a constant is a guess about content it cannot see. A 96dp floor
 * chosen for naming width let a narrow amount at font scale 2.0 leave 144dp
 * beside a timestamp needing 200 — side by side, and clipped.
 *
 * Each child is measured exactly once, which is why this is a plain [Layout]
 * and not a `SubcomposeLayout`. [trailing] goes first because its width is the
 * one worth respecting: an amount broken mid-number is unreadable in a way a
 * wrapped timestamp is not.
 */
@Composable
internal fun EntryRowBody(
    leading: @Composable () -> Unit,
    trailing: @Composable () -> Unit,
    modifier: Modifier = Modifier,
) {
    val gapPx = with(LocalDensity.current) { LfTheme.spacing.sm.roundToPx() }

    Layout(
        modifier = modifier,
        contents = listOf(leading, trailing),
    ) { (leadingMeasurables, trailingMeasurables), constraints ->
        val width = constraints.maxWidth
        val leadingMeasurable = leadingMeasurables.first()

        val trailingPlaceable = trailingMeasurables.first().measure(Constraints(maxWidth = width))
        val remaining = width - trailingPlaceable.width - gapPx
        // The floor is what the leading block *actually needs* on one line, not
        // a constant. A fixed 96dp was a guess about naming width, and the
        // leading block here is a timestamp: at font scale 2.0 a narrow amount
        // left 144dp beside a stamp that needed 200, so the row stayed
        // side-by-side and clipped "19 Aug, 10:15 am" to "19 Aug, 10:1…" --
        // clipping the one field the line exists to show.
        //
        // A long note raises this and stacks the row earlier than it used to.
        // That is the right way round: stacking costs a line, clipping costs
        // the information.
        val stacked = remaining < leadingMeasurable.maxIntrinsicWidth(constraints.maxHeight)

        val leadingPlaceable =
            leadingMeasurable.measure(Constraints(maxWidth = if (stacked) width else remaining))

        if (stacked) {
            val height = leadingPlaceable.height + gapPx + trailingPlaceable.height
            layout(width, height) {
                leadingPlaceable.place(0, 0)
                // Right-aligned even when stacked, so the amount stays in the
                // optical column it occupies on every other row.
                trailingPlaceable.place(
                    x = width - trailingPlaceable.width,
                    y = leadingPlaceable.height + gapPx,
                )
            }
        } else {
            val height = maxOf(leadingPlaceable.height, trailingPlaceable.height)
            layout(width, height) {
                leadingPlaceable.place(0, (height - leadingPlaceable.height) / 2)
                trailingPlaceable.place(
                    x = width - trailingPlaceable.width,
                    y = (height - trailingPlaceable.height) / 2,
                )
            }
        }
    }
}

/**
 * How the confirmation names this entry.
 *
 * Unsigned, unlike the list: the `-`/`+` prefix reads as direction in a column
 * of amounts and as a typo inside a sentence, and the book is already
 * unambiguous from the tab the user is looking at.
 */
private fun LedgerListItem.deleteLabel(title: String): String =
    MoneyFormat.symbolised(amount.minor, currency) + SEPARATOR + title

/**
 * The empty book — two of them, and they are not the same sentence.
 *
 * A new user has never saved an expense. A returning one may have a full ledger
 * whose most recent entry is older than the list's window. Both render zero
 * rows; telling the second user "no expenses yet" is telling them their data is
 * gone, which is the alarm this branch exists to prevent.
 *
 * The first message's copy is also what BUG10 was reported against — it used to
 * promise "add an entry and it appears here" on a screen that could not show
 * one.
 */
@Composable
private fun EmptyBook(state: LedgerUiState) {
    val book = when (state.ledger) {
        LedgerType.DEBIT -> "expenses"
        LedgerType.CREDIT -> "income"
    }
    val other = when (state.ledger) {
        LedgerType.DEBIT -> "Income"
        LedgerType.CREDIT -> "Expenses"
    }

    if (state.hasAnyEntries) {
        LfEmptyState(
            title = "Nothing in the last ${state.windowDays} days",
            body = "Your older $book are still saved — this list just shows the " +
                "last ${state.windowDays} days. Analytics and export still cover " +
                "everything.",
        )
    } else {
        LfEmptyState(
            title = "No $book yet",
            body = "Whatever you save appears here, newest first. Expenses and " +
                "income are kept as two separate books — check the $other tab if " +
                "you filed it there.",
        )
    }
}

/**
 * `6:42 pm` under Today and Yesterday, `19 Aug, 6:42 pm` elsewhere.
 *
 * The date is dropped where the band header already carries it. Repeating
 * "19 Aug" under a header that says "Today" is noise, and it is also what makes
 * room for the amount to sit beside the stamp rather than below it on the rows
 * the user reads most.
 *
 * `occurred_at`, not `created_at`: the row means the transaction, and the date
 * picker in the entry form sets exactly this. For a manual entry saved on the
 * spot they are the same instant anyway; when they differ, it is because the
 * user deliberately back-dated a receipt, and showing them the moment they
 * typed it would be showing them the wrong fact.
 *
 * The 12/24-hour choice follows the *device setting* rather than the locale,
 * because on Android that is a switch the user can flip independently and
 * `DateTimeFormatter` cannot see it.
 */
@Composable
internal fun occurredStamp(occurredAt: Long, withDate: Boolean): String =
    TimeStamp.of(occurredAt, withDate)

/**
 * What TalkBack says for a row.
 *
 * Spelled out rather than assembled from the visible strings, twice over:
 * `MoneyFormat` ships a spoken form because screen readers skip currency glyphs
 * (SPEC.md §9.6), and the `-`/`+` prefix is skipped just as often — so the
 * direction becomes a word here rather than a character.
 */
private fun LedgerListItem.spokenAs(title: String, detail: String): String =
    listOf(MoneyFormat.spokenDirectional(amount.minor, currency, ledger), title, detail)
        .filter { it.isNotEmpty() }
        .joinToString(SEPARATOR)

/** Hairline. The same weight the taxonomy cards and the nesting rail use. */
internal const val HAIRLINE = 1

private const val BAND_HEADER_TYPE = "band"
private const val ENTRY_TYPE = "entry"
private const val PENDING_TYPE = "pending"
private const val CANDIDATE_TYPE = "candidate"
private const val SEPARATOR = " · "
private const val NOTE_MAX_LINES = 1


/**
 * An entry filed under nothing. §5.1 writes these when a parse fails.
 *
 * `internal` so the bin says the same word, the way it shares [HAIRLINE]. Both
 * screens reach it only after the line-item fallback has come back empty too
 * (ADR-0018), so it now means "genuinely nothing" on either.
 */
internal const val UNFILED = "Unfiled"

/** A draft with nothing chosen yet, which is most of a draft's life. */
private const val UNTITLED_DRAFT = "Unsaved entry"
private const val UNREAD_MESSAGE = "Unread message"

/** Tells a candidate row apart from a draft row in the merged section. */
private const val TO_REVIEW_MARKER = "To review"

/** ...and its opposite, on the draft row. */
private const val DRAFT_MARKER = "Draft"
private const val NO_AMOUNT = "—"

// ── Previews (CLAUDE.md §5) ───────────────────────────────────────────────

private const val PREVIEW_TODAY = 20_684
private const val PREVIEW_NOW = 1_787_000_000_000L
private const val PREVIEW_HOUR = 3_600_000L

private fun previewItem(
    id: String,
    minor: Long,
    dayOffset: Int,
    merchant: String? = null,
    category: String? = null,
    note: String? = null,
) = LedgerListItem(
    id = id,
    ledger = LedgerType.DEBIT,
    amount = Money(minor),
    currency = "INR",
    occurredAt = PREVIEW_NOW - dayOffset * 24 * PREVIEW_HOUR,
    localDate = PREVIEW_TODAY - dayOffset,
    categoryName = category,
    categoryColorArgb = category?.let { 0xFF3E6AD6.toInt() },
    merchantName = merchant,
    note = note,
)

/** One row per band, so every header renders in the previews. */
private val previewExpenses = listOf(
    previewItem("1", 1_240_50, 0, "Big Bazaar", "Groceries"),
    previewItem("2", 60_00, 0, category = "Transport", note = "Auto to office"),
    previewItem("3", 1_24_000_00, 1, "Landlord", "Rent"),
    previewItem("4", 349_00, 3, note = "Cash, no receipt"),
    previewItem("5", 2_100_00, 12, "Croma", "Electronics"),
)

/** The other book, so the income colour and `+` are previewed, not assumed. */
private val previewIncome = listOf(
    previewItem("6", 85_000_00, 0, "Acme Corp", "Salary"),
    previewItem("7", 2_400_00, 2, category = "Interest"),
).map { it.copy(ledger = LedgerType.CREDIT) }

@PreviewScreenSizes
@PreviewFontScale
@PreviewLightDark
@Composable
private fun LedgerPreview() {
    LfTheme {
        LedgerScreen(
            state = LedgerUiState(
                ledger = LedgerType.DEBIT,
                today = PREVIEW_TODAY,
                hasAnyEntries = true,
                windowDays = PREVIEW_WINDOW_DAYS,
                isLoaded = true,
            ),
            entries = flowOf(PagingData.from(previewExpenses)),
            onEvent = {},
            onOpenDraft = {},
            onReviewCandidate = {},
        )
    }
}

@PreviewScreenSizes
@PreviewFontScale
@PreviewLightDark
@Composable
private fun LedgerIncomePreview() {
    LfTheme {
        LedgerScreen(
            state = LedgerUiState(
                ledger = LedgerType.CREDIT,
                today = PREVIEW_TODAY,
                hasAnyEntries = true,
                windowDays = PREVIEW_WINDOW_DAYS,
                isLoaded = true,
            ),
            entries = flowOf(PagingData.from(previewIncome)),
            onEvent = {},
            onOpenDraft = {},
            onReviewCandidate = {},
        )
    }
}

/** The never-used book. */
@PreviewScreenSizes
@PreviewFontScale
@PreviewLightDark
@Composable
private fun LedgerEmptyPreview() {
    LfTheme {
        LedgerScreen(
            state = LedgerUiState(
                ledger = LedgerType.CREDIT,
                today = PREVIEW_TODAY,
                hasAnyEntries = false,
                windowDays = PREVIEW_WINDOW_DAYS,
                isLoaded = true,
            ),
            entries = flowOf(PagingData.empty()),
            onEvent = {},
            onOpenDraft = {},
            onReviewCandidate = {},
        )
    }
}

/** A full book whose entries all predate the window — the alarming one. */
@PreviewScreenSizes
@PreviewFontScale
@PreviewLightDark
@Composable
private fun LedgerOutsideWindowPreview() {
    LfTheme {
        LedgerScreen(
            state = LedgerUiState(
                ledger = LedgerType.DEBIT,
                today = PREVIEW_TODAY,
                hasAnyEntries = true,
                windowDays = PREVIEW_WINDOW_DAYS,
                isLoaded = true,
            ),
            entries = flowOf(PagingData.empty()),
            onEvent = {},
            onOpenDraft = {},
            onReviewCandidate = {},
        )
    }
}

private const val PREVIEW_WINDOW_DAYS = 30
