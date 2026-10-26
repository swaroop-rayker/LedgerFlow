package com.ledgerflow.feature.ledger

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.layout.LazyLayoutCacheWindow
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.PreviewFontScale
import androidx.compose.ui.tooling.preview.PreviewLightDark
import androidx.compose.ui.tooling.preview.PreviewScreenSizes
import androidx.compose.ui.unit.dp
import androidx.paging.LoadState
import androidx.paging.LoadStates
import androidx.paging.PagingData
import androidx.paging.compose.LazyPagingItems
import androidx.paging.compose.collectAsLazyPagingItems
import com.ledgerflow.core.designsystem.component.LfAdaptiveRow
import com.ledgerflow.core.designsystem.component.LfButton
import com.ledgerflow.core.designsystem.component.LfButtonStyle
import com.ledgerflow.core.designsystem.component.LfDialog
import com.ledgerflow.core.designsystem.component.LfEmptyState
import com.ledgerflow.core.designsystem.component.LfIconButton
import com.ledgerflow.core.designsystem.component.LfScreenTitle
import com.ledgerflow.core.designsystem.component.LfSegmentedControl
import com.ledgerflow.core.designsystem.format.MoneyFormat
import com.ledgerflow.core.designsystem.format.TimeStamp
import com.ledgerflow.core.designsystem.icon.LfIcons
import com.ledgerflow.core.designsystem.pattern.LfPageSpacing
import com.ledgerflow.core.designsystem.pattern.LfTabPage
import com.ledgerflow.core.designsystem.theme.LfTextAlign
import com.ledgerflow.core.designsystem.theme.LfTheme
import com.ledgerflow.core.domain.inbox.PendingTransaction
import com.ledgerflow.core.domain.ledger.DraftSummary
import com.ledgerflow.core.model.LedgerListItem
import com.ledgerflow.core.model.LedgerType
import com.ledgerflow.core.model.Money
import com.ledgerflow.core.ui.entry.ENTRY_UNFILED
import com.ledgerflow.core.ui.entry.LfEntryRow
import com.ledgerflow.core.ui.entry.entryTitle
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

    EntryList(
        items = items,
        state = state,
        onEvent = onEvent,
        onOpenDraft = onOpenDraft,
        onReviewCandidate = onReviewCandidate,
        modifier = modifier,
    )
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
        modifier = Modifier.fillMaxWidth(),
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
// LazyLayoutCacheWindow is experimental in foundation 1.12. If a BOM bump
// changes it, this fails to compile rather than silently losing the prefetch.
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun EntryList(
    items: LazyPagingItems<LedgerListItem>,
    state: LedgerUiState,
    onEvent: (LedgerEvent) -> Unit,
    onOpenDraft: (String) -> Unit,
    onReviewCandidate: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    // `itemCount == 0` alone is also true for the first frame, before the first
    // page has loaded — showing an empty state there would flash "nothing here"
    // at a user whose ledger is full.
    val settled = items.loadState.refresh !is LoadState.Loading
    val status: (@Composable ColumnScope.() -> Unit)? = when {
        // Nothing is drawn until the drafts query has answered. Composing the
        // list first and prepending the unsaved section afterwards put that
        // section above the scroll anchor and out of sight -- see
        // [LedgerUiState.isLoaded].
        !state.isLoaded -> { {} }
        // The empty state is about the *book*, so unsaved entries do not count
        // towards it — but it must not hide them either. With drafts on screen
        // the list still renders; without them it is genuinely empty.
        settled && state.showsNothing(items.itemCount) -> { { EmptyBook(state) } }
        else -> null
    }

    val snapshot = items.itemSnapshotList
    // One painter for every row's delete control: a painter per row was a
    // vector tree built and a bitmap uploaded for each row scrolled in, inside
    // the frames that missed 120 Hz (see LfIconButton's Painter overload).
    val deleteIcon = rememberVectorPainter(LfIcons.Delete)
    // Keyed on the book, so switching tabs builds a new list rather than
    // carrying the old one's scroll position into it. Two disjoint books are
    // two lists (Law 2), and it also means a book with more unsaved entries
    // than the last one cannot reproduce the prepend problem above.
    key(state.ledger) {
        // Rows composed ahead of the viewport in idle time, not inside the frame
        // that scrolls them in. Measured 2026-09-29 at 120 Hz: every frame that
        // missed its 8.3 ms deadline was composing, creating and measuring a new
        // row (~4 ms) because the default prefetch keeps one row ahead, and a fast
        // fling outruns it. Two viewports ahead and one behind (for the fling
        // back): one-and-a-half still left late frames on a warm phone, and the
        // rows it keeps composed cost little (32 MB PSS after a scroll).
        val listState = rememberLazyListState(
            cacheWindow = LazyLayoutCacheWindow(aheadFraction = CACHE_AHEAD, behindFraction = CACHE_BEHIND),
        )
        // The header is pinned, as it always was: the book switch is what the
        // list is read through. `Dense` because this list holds a row per
        // transaction, and every step of the gap scale is charged to it.
        LfTabPage(
            title = "Ledger",
            modifier = modifier,
            filters = {
                LfSegmentedControl(
                    options = listOf("Expenses", "Income"),
                    selectedIndex = state.ledger.ordinal,
                    onSelect = { onEvent(LedgerEvent.LedgerSelected(LedgerType.entries[it])) },
                )
            },
            notices = state.message?.let { message -> { MessageBanner(message = message, onEvent = onEvent) } },
            statusContent = status,
            spacing = LfPageSpacing.Dense,
            pinHeader = true,
            listState = listState,
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
                    val row = items[index] ?: item
                    LfEntryRow(
                        item = row,
                        withDate = bucket.needsDate,
                        deleteIcon = deleteIcon,
                        onDelete = {
                            onEvent(LedgerEvent.DeleteRequested(row.id, row.deleteLabel(row.entryTitle())))
                        },
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
    val detail = candidateDetail(stamp)

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
        // The draft row's shape (BUG47): title across the top, then the detail
        // line and the amount on the adaptive body, which moves the amount
        // below only when both will not fit. With the amount in a column of
        // its own, the detail line got about 150 dp at font scale 2.0.
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = LfTheme.typography.bodyL,
                color = LfTheme.colors.textPrimary,
            )
            EntryRowBody(
                // The marker is what stops two rows that look alike opening
                // different screens with no warning. It rides the line the
                // stamp is already on, so it costs no height.
                leading = { UnsavedDetail(text = detail, color = LfTheme.colors.textTertiary) },
                trailing = {
                    Text(
                        text = amount,
                        style = LfTheme.typography.amountM,
                        color = LfTheme.colors.textSecondary,
                        maxLines = 1,
                    )
                },
            )
        }
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
    val detail = pendingDetail(stamp)

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
    UnsavedDetail(text = stamp, color = LfTheme.colors.accent)
}

/** A draft's detail line: when it is dated, then the marker. */
internal fun pendingDetail(stamp: String): String = stamp + SEPARATOR + DRAFT_MARKER

/** A candidate's detail line: when it happened, then the marker. */
internal fun candidateDetail(stamp: String): String = stamp + SEPARATOR + TO_REVIEW_MARKER

/**
 * The detail line of an Unsaved row: the stamp, then the `Draft` / `To review`
 * marker (`960b5ee`). The stamp leads, so on a narrow line it is never the part
 * that goes.
 */
@Composable
internal fun UnsavedDetail(
    text: String,
    color: Color,
    onTextLayout: (TextLayoutResult) -> Unit = {},
) {
    Text(
        text = text,
        style = LfTheme.typography.label,
        color = color,
        maxLines = UNSAVED_DETAIL_MAX_LINES,
        overflow = TextOverflow.Ellipsis,
        onTextLayout = onTextLayout,
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
        textAlign = LfTextAlign.end,
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
    // Lifted to the design system at P5 step 4 so Analytics and the Inbox
    // degrade the same way; the reasoning above is the original's.
    LfAdaptiveRow(leading = leading, trailing = trailing, modifier = modifier)
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

/** Hairline. The same weight the taxonomy cards and the nesting rail use. */
internal const val HAIRLINE = 1

private const val BAND_HEADER_TYPE = "band"
private const val ENTRY_TYPE = "entry"

/** The Ledger's prefetch window, in viewports (see EntryList). */
private const val CACHE_AHEAD = 2f
private const val CACHE_BEHIND = 1f
private const val PENDING_TYPE = "pending"
private const val CANDIDATE_TYPE = "candidate"
private const val SEPARATOR = " · "

/**
 * Two, so at large text the `Draft` / `To review` marker wraps rather than being
 * the part the ellipsis takes (BUG47). At font scale 1.0 the line still fits on
 * one, so this costs no height where the row is usually seen.
 */
internal const val UNSAVED_DETAIL_MAX_LINES = 2


/**
 * An entry filed under nothing. §5.1 writes these when a parse fails.
 *
 * `internal` so the bin says the same word, the way it shares [HAIRLINE]. Both
 * screens reach it only after the line-item fallback has come back empty too
 * (ADR-0018), so it now means "genuinely nothing" on either.
 */
internal const val UNFILED = ENTRY_UNFILED

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

/**
 * Preview pages with a **finished** load stated. A static `PagingData` without
 * explicit load states reports `refresh = Loading` forever, and [EntryList]
 * waits for a settled load before showing an empty book -- so the two empty
 * previews rendered a blank page until P5 step 4's goldens showed it.
 */
private fun previewPages(rows: List<LedgerListItem>): Flow<PagingData<LedgerListItem>> {
    val done = LoadState.NotLoading(endOfPaginationReached = true)
    return flowOf(PagingData.from(rows, sourceLoadStates = LoadStates(done, done, done)))
}

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
            entries = previewPages(previewExpenses),
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
            entries = previewPages(previewIncome),
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
            entries = previewPages(emptyList()),
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
            entries = previewPages(emptyList()),
            onEvent = {},
            onOpenDraft = {},
            onReviewCandidate = {},
        )
    }
}

private const val PREVIEW_WINDOW_DAYS = 30
