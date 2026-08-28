package com.ledgerflow.feature.ledger

import androidx.compose.runtime.Immutable
import com.ledgerflow.core.common.time.OccurredAt
import com.ledgerflow.core.domain.inbox.PendingTransaction
import com.ledgerflow.core.domain.ledger.DraftSummary
import com.ledgerflow.core.model.LedgerType

/**
 * The Ledger tab's state (SPEC.md §5.5, §9.3).
 *
 * Small on purpose. The entries themselves are **not** here: a `PagingData` is
 * neither immutable nor comparable, and putting one in a state class would make
 * every recomposition of the screen a diff against a stream. They travel as
 * their own `Flow` off the ViewModel, which is what `collectAsLazyPagingItems`
 * expects.
 */
@Immutable
public data class LedgerUiState(
    /**
     * Which book is on screen.
     *
     * A partition selector, not a filter (Law 2). The two books never appear in
     * one list and there is no combined figure anywhere on this screen —
     * switching this runs a different query against a different view, it does
     * not narrow a shared result set.
     */
    val ledger: LedgerType = LedgerType.DEBIT,

    /**
     * Today, as days since epoch, so a day header can say "Today" rather than
     * a date the user has to decode.
     *
     * Supplied by the ViewModel off the injected [com.ledgerflow.core.common.time.Clock]
     * rather than read in the composable: `LocalDate.now()` inside composition
     * is an untestable read of the wall clock, and it is the same value every
     * header on the screen has to agree on.
     */
    val today: Int = 0,

    /**
     * Whether this book holds anything at all, ignoring the list's window.
     *
     * Only ever read when the list is empty, and only to choose which of two
     * sentences to show. "You have not saved an expense yet" and "nothing in
     * the last 30 days" render the same zero rows and mean opposite things —
     * telling a returning user the first is telling them their data is gone.
     */
    val hasAnyEntries: Boolean = false,

    /**
     * How far back the list looks, in days.
     *
     * Carried into state so the empty-state copy can name the actual number
     * rather than restate a constant it hopes still matches
     * [com.ledgerflow.core.domain.ledger.LedgerRepository.LIST_WINDOW_DAYS].
     */
    val windowDays: Int = 0,

    /**
     * This book's unsaved entries, newest first (ADR-0013).
     *
     * Not paged, and deliberately: drafts are bounded by the 30-day sweep in
     * §6.1.2 and by how many entries a person can leave half-typed, which is a
     * handful. Paging a list that short would cost more than it saved.
     *
     * A [DraftSummary] rather than an `EntryDraft`: the payload's shape belongs
     * to `:feature:entry`, and this screen must never parse it.
     */
    /**
     * Everything in this book that is not in the ledger yet, newest first.
     *
     * **Drafts and captured candidates in one list** (owner, CHANGE#1). They
     * were two bands until the owner asked for one: a draft is typing the user
     * started, a candidate is a message waiting for the tap Law 1 requires, and
     * both are answers to "what have I not dealt with". They stay two *types*
     * — see [UnsavedRow] — because they open different screens and their
     * discards mean different things, and the row shows which is which.
     *
     * Sorted together by when each thing happened rather than grouped by kind,
     * so the section reads chronologically like the rest of the Ledger.
     *
     * Bounded without paging: drafts by §6.1.2's 30-day sweep, candidates by
     * how many messages a person leaves unreviewed. Paging this would be
     * machinery with nothing to do.
     */
    val unsaved: List<UnsavedRow> = emptyList(),

    /**
     * Whether the database has actually answered yet.
     *
     * It exists to tell "this book has no unsaved entries" from "the drafts
     * query has not come back", which [pending] alone cannot -- both are an
     * empty list, and the screen must not build its list until it knows which.
     *
     * Found on device: the seed state has no drafts, so `LazyColumn` composed
     * without the unsaved section and the rows arrived a frame later as a
     * *prepend*. Lazy lists anchor their scroll to the item that was first, so
     * the new section was placed above the viewport and the user saw a Ledger
     * with no unsaved entries on it at all -- until they scrolled up, which
     * nobody does on a list that appears to start at the top.
     */
    val isLoaded: Boolean = false,

    /**
     * The install's base currency, for formatting a draft's amount.
     *
     * Committed rows carry their own `currency` column; a draft has no such
     * column, because it was never approved and §5.8 stamps the currency at
     * approval. So the screen takes it from the install, which is the currency
     * that draft will be saved in.
     */
    val currencyCode: String = DEFAULT_CURRENCY,

    /**
     * The question currently on screen, if any.
     *
     * ViewModel state rather than a `remember` inside the row, for the reason
     * `CategoriesUiState.dialog` gives: a row leaves composition whenever its
     * page is dropped or the list re-emits, and a confirmation that evaporates
     * mid-question is worse than not asking. It also survives a rotation.
     *
     * One field for both questions rather than two nullable ones, so the two
     * cannot both be open at once -- a state the screen would have no sensible
     * way to render.
     */
    val confirmation: LedgerConfirmation? = null,

    /** A refusal the user should see, in words they can act on. */
    val message: String? = null,
)

/**
 * A question the user has been asked, and what it is about.
 *
 * The label is built when the question is posed rather than looked up when it
 * is answered: by then the row may have been paged out, and a confirmation that
 * cannot name what it is about is one people learn to tap through.
 *
 * Two cases rather than one with a flag, because they destroy different things
 * and must not share wording. Deleting a saved entry removes something the user
 * committed; discarding a draft throws away something they were still typing,
 * which is BUG6's territory and reads differently.
 */
/** Until `app_meta.baseCurrency` has been read. Never shown for long. */
internal const val DEFAULT_CURRENCY: String = "INR"

public sealed interface LedgerConfirmation {

    public val id: String
    public val label: String

    /** A committed entry. Soft-deleted, so it is recoverable in principle. */
    @Immutable
    public data class DeleteEntry(
        override val id: String,
        override val label: String,
    ) : LedgerConfirmation

    /** An unsaved draft. Nothing else holds it, so this really is the end of it. */
    @Immutable
    public data class DiscardDraft(
        override val id: String,
        override val label: String,
    ) : LedgerConfirmation
}

/**
 * One row in the "Unsaved" section (CHANGE#1).
 *
 * A sealed type rather than one flattened row model, because the difference
 * survives the merge and matters at the moment of the tap: a [Draft] opens the
 * entry form and its discard is final, a [Candidate] opens the review screen
 * and its discard is reversible for 30 days (§5.1). Flattening them would make
 * two rows that look identical do different things, which is the one outcome
 * the marker on the row exists to prevent.
 */
@Immutable
public sealed interface UnsavedRow {

    /** Stable across recomposition, and unique across both kinds. */
    public val key: String

    /** When the thing happened, for the shared newest-first sort. */
    public val happenedAt: Long

    /** Typing the user started. Opens the entry form. */
    @Immutable
    public data class Draft(val summary: DraftSummary) : UnsavedRow {
        override val key: String get() = "draft-${summary.id}"
        override val happenedAt: Long get() = summary.datedAt
    }

    /**
     * A captured message waiting for approval. Opens the review screen.
     *
     * **[happenedAt] is the same value the row displays**, via
     * [OccurredAt.effectiveOrCapture]. Sorting on the raw `occurredAt` instead
     * put both of the owner's real candidates at midnight — every bank SMS
     * states a date and no clock — so a draft from 2:49 pm sorted *above* a
     * candidate the row itself showed as 4:24 pm. The list looked simply
     * unsorted, and neither the display nor the sort was wrong on its own,
     * which is why it took a device to see.
     */
    @Immutable
    public data class Candidate(val candidate: PendingTransaction) : UnsavedRow {
        override val key: String get() = "candidate-${candidate.id}"
        override val happenedAt: Long
            get() = OccurredAt.effectiveOrCapture(
                occurredAt = candidate.extracted.occurredAt,
                capturedAt = candidate.createdAt,
            )
    }
}

public sealed interface LedgerEvent {
    /** The `Expenses | Income` control. Selects a book; never filters one. */
    public data class LedgerSelected(val ledger: LedgerType) : LedgerEvent

    /**
     * Ask before removing. Confirmed separately — it destroys a real entry and
     * changes totals the user has already read.
     */
    public data class DeleteRequested(val id: String, val label: String) : LedgerEvent

    /** Ask before throwing away an unsaved entry (§6.1.2, BUG6). */
    public data class DiscardRequested(val id: String, val label: String) : LedgerEvent

    /** Answers whichever question is open. */
    public data object ConfirmationAccepted : LedgerEvent
    public data object ConfirmationDismissed : LedgerEvent

    public data object MessageDismissed : LedgerEvent
}
