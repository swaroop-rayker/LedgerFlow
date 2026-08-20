package com.ledgerflow.core.domain.ledger

import androidx.paging.PagingData
import com.ledgerflow.core.model.DeletedEntry
import com.ledgerflow.core.model.EntryAssignment
import com.ledgerflow.core.model.EntryOrigin
import com.ledgerflow.core.model.ForeignAmount
import com.ledgerflow.core.model.LedgerEntry
import com.ledgerflow.core.model.LedgerListItem
import com.ledgerflow.core.model.LedgerType
import com.ledgerflow.core.model.LineItemKind
import com.ledgerflow.core.model.Money
import kotlinx.coroutines.flow.Flow

/**
 * The ledger's write path and its narrow reads (SPEC.md §6.1, ADR-0002).
 *
 * **[approve] is the only way a row reaches `ledger_entry`** (Law 1), and the
 * only sanctioned caller of it is `ApproveTransactionUseCase`. That is not a
 * convention a reviewer has to notice: `LedgerSingleWriterTest` scans every
 * module's sources and fails the build on any other call site.
 *
 * Everything [approve] validates is checked *inside* the transaction that
 * writes. Checking first and inserting afterwards would leave a window in which
 * a category is soft-deleted between the two, and the entry lands pointing at a
 * row no picker will ever show again.
 */
public interface LedgerRepository {

    /**
     * Validates and commits one entry with its line items, atomically.
     *
     * A partial write here is an entry whose items are missing -- a total that
     * is silently wrong rather than visibly absent, which is the harder bug to
     * ever notice.
     */
    public suspend fun approve(request: ApprovalRequest): LedgerResult<LedgerEntry>

    /**
     * One book's entries, newest first, paged (SPEC.md §5.5, §9.3).
     *
     * **Paged rather than a `Flow<List<LedgerListItem>>`**, because a ledger
     * grows without bound and CLAUDE.md §8 is unconditional about it: the
     * Ledger list is the surface §11 pins to "0 frames > 16.6 ms during scroll",
     * and a list that has to be materialised first cannot meet that on year
     * three of use. `PagingData` on this interface is ADR-0014; the artifact
     * behind it is `paging-common`, which is Kotlin/JVM and keeps this module
     * unit-testable off-device.
     *
     * Takes a [LedgerType] and reads that book's view alone. There is
     * deliberately no overload that omits it and no variant returning both
     * (Law 2) -- the `Expenses | Income` control on the screen selects a
     * partition, it does not filter shared data.
     *
     * Ordered `local_date DESC, occurred_at DESC` by the query, so the recency
     * headers the list draws come off that ordering rather than from regrouping
     * a list the caller assembled.
     *
     * @param since the oldest `local_date` to return, as days since epoch.
     *   **A bound on the view, not a retention policy** -- see [LIST_WINDOW_DAYS].
     *   Pass [Int.MIN_VALUE] for the whole book.
     */
    public fun observeEntries(ledger: LedgerType, since: Int): Flow<PagingData<LedgerListItem>>

    /**
     * Removes one entry from the books (§5.5).
     *
     * A **soft** delete: the row keeps its history and simply stops being
     * visible to every read path, because the views filter `deleted_at`. Its
     * line items survive with it, which a real `DELETE` would cascade away.
     *
     * Takes the [ledger] as well as the [id] and both are used in the
     * statement. An id carries no book inside it, so without the pair a screen
     * showing one book could reach into the other; with it, a mismatch affects
     * no rows and comes back as [LedgerError.EntryNotFound] (Law 2).
     *
     * **Only `DeleteEntryUseCase` may call this**, the same way only
     * `ApproveTransactionUseCase` may call [approve]. Law 1 is written about
     * inserts, so this is not literally covered by it -- but its purpose is
     * that each way of writing to `ledger_entry` has exactly one audited door,
     * and a delete is a write. `LedgerSingleWriterTest` fails the build on any
     * other call site.
     */
    public suspend fun softDeleteEntry(ledger: LedgerType, id: String): LedgerResult<Unit>

    /**
     * One book's binned entries, newest first.
     *
     * The bin screen collects both books and merges them for display
     * (ADR-0015). This stays per book because the statement underneath has to:
     * an id carries no ledger, and a read that could be pointed at either would
     * be the shape ADR-0002 removes.
     */
    public fun observeDeleted(ledger: LedgerType): Flow<List<DeletedEntry>>

    /**
     * Puts one binned entry back into its book.
     *
     * The inverse of [softDeleteEntry], and the reason that one keeps the row
     * rather than destroying it: clearing `deleted_at` returns the entry to
     * every read path at once.
     *
     * **Only `RestoreEntryUseCase` may call this.** It is a write to
     * `ledger_entry` and gets the same audited door the other three have;
     * `LedgerSingleWriterTest` fails the build on any other caller.
     *
     * @return [Unit], or [LedgerError.EntryNotFound] when nothing binned in
     *   that book has that id -- which also covers being asked through the
     *   wrong book (Law 2).
     */
    public suspend fun restoreEntry(ledger: LedgerType, id: String): LedgerResult<Unit>

    /**
     * **Destroys one binned entry. There is no undo.**
     *
     * The chosen-row form of [purgeDeletedEntries]. Refuses anything that is
     * not already binned, so a live entry can never be reached through it.
     *
     * **Only `PurgeDeletedEntriesUseCase` may call this**, enforced by
     * `LedgerSingleWriterTest`.
     *
     * @return rows destroyed: 1, or 0 if it was already gone.
     */
    public suspend fun purgeDeletedEntry(ledger: LedgerType, id: String): Int

    /**
     * How many soft-deleted entries this book still carries.
     *
     * Zero means there is nothing for the erase action in More to do, and the
     * row is not offered. Counted from the base table, because the views exist
     * precisely to hide these rows.
     */
    public fun observeDeletedCount(ledger: LedgerType): Flow<Int>

    /**
     * **Destroys every soft-deleted entry in one book. There is no undo.**
     *
     * The only operation in LedgerFlow that removes committed ledger data from
     * the file. [softDeleteEntry] hides an entry and keeps it; this is what the
     * user reaches for when they want it actually gone.
     *
     * Line items and any in-flight edit draft of a purged entry go with it, by
     * foreign key.
     *
     * **Only `PurgeDeletedEntriesUseCase` may call this**, enforced by
     * `LedgerSingleWriterTest`. It is a third audited door beside [approve] and
     * [softDeleteEntry], and by some distance the one that most needs auditing.
     *
     * @return rows destroyed.
     */
    public suspend fun purgeDeletedEntries(ledger: LedgerType): Int

    /**
     * Rewrites the database file, reclaiming the space freed by a purge.
     *
     * `DELETE` marks pages free; it does not zero them and it does not shrink
     * the file. Without this, "permanently erase" would be true of the app's
     * queries and false of the bytes on disk -- which is the half a user asking
     * for permanence actually cares about.
     *
     * It lives on this interface rather than behind a maintenance port of its
     * own for the reason [baseCurrency] does: the purge is its only caller, and
     * inventing a repository to hold one statement would be a layer that exists
     * to hold a constant. Give it its own port when something else needs it.
     */
    public suspend fun compactStorage()

    /**
     * Whether this book holds anything at all, ignoring
     * [observeEntries]' window.
     *
     * The screen needs it to tell "you have not saved an expense yet" from
     * "nothing in the last 30 days", which look identical from the list's own
     * point of view and mean opposite things to the person reading them.
     *
     * Per book, like everything else here. There is no variant answering for
     * both (Law 2).
     */
    public fun observeHasEntries(ledger: LedgerType): Flow<Boolean>

    /**
     * The combinations this ledger has actually seen, most-used first.
     *
     * Feeds the repeat-expense chips that make §5.4's ≤4-tap target reachable.
     * Takes a [LedgerType] and reads that book's view alone; there is
     * deliberately no variant returning both (Law 2).
     */
    public fun observeRecentCombos(ledger: LedgerType, limit: Int): Flow<List<EntryCombo>>

    /**
     * The install's base currency (`app_meta.baseCurrency`, §5.8).
     *
     * It lives on this interface because it is a property of every amount the
     * ledger stores rather than a user preference -- it is chosen once at
     * onboarding, cannot be changed (§1.3), and [approve] already has to read
     * it to stamp each row. A settings repository can take it over when there
     * is one; inventing one now for a single immutable value would be a layer
     * that exists to hold a constant.
     *
     * Null only if the §7.4 gate never completed, which an unlocked vault makes
     * impossible -- [approve] refuses rather than guessing, and so should any
     * caller.
     */
    public suspend fun baseCurrency(): String?

    public companion object {
        /**
         * How far back the Ledger list looks, in days.
         *
         * **Nothing is deleted at this boundary.** Entries older than it are
         * still stored, still exported, still counted by analytics -- §5.6 runs
         * windows out to 5Y and could not do so otherwise -- and still restored
         * from a `.lfbk`. This bounds one screen, because the list a user
         * scrolls daily is worth keeping short and recent, and because an
         * unbounded scroll through years of entries is not how anyone finds a
         * transaction. Date filters and search (P1/P3) are how the rest is
         * reached.
         *
         * It lives on the repository rather than in the ViewModel so both books
         * cannot drift to different windows.
         */
        public const val LIST_WINDOW_DAYS: Int = 30
    }
}

/**
 * An entry a human has decided to commit.
 *
 * `currency` is absent on purpose: `amount_minor` is always the base currency
 * (§5.8), so letting a caller name one would create a value the repository
 * would have to either trust or override. It reads `app_meta.baseCurrency`
 * instead.
 *
 * `localDate` is absent for the same reason -- it is `occurredAt` in the
 * capture device's timezone, and two fields that must agree are two fields that
 * can disagree.
 */
public data class ApprovalRequest(
    val ledger: LedgerType,
    /** Positive, base currency, minor units (Law 3). */
    val amount: Money,
    val occurredAt: Long,
    val assignment: EntryAssignment = EntryAssignment(),
    val note: String? = null,
    val origin: EntryOrigin = EntryOrigin.Manual,
    val foreign: ForeignAmount? = null,
    val isRecurring: Boolean = false,
    val lineItems: List<NewLineItem> = emptyList(),
)

/**
 * A line the user typed, before it has an id or a position.
 *
 * When the lines do not sum to the entry's amount, the approval writes the
 * difference as an [LineItemKind.UNALLOCATED] row rather than refusing the save
 * or quietly letting the total drift -- the same rule §5.3 sets for an
 * unbalanced receipt, applied here so manual and OCR entries cannot disagree
 * about what an unbalanced bill means.
 */
public data class NewLineItem(
    val name: String,
    val total: Money,
    val kind: LineItemKind = LineItemKind.ITEM,
    val quantityMilli: Long = com.ledgerflow.core.model.LineItem.UNIT_QUANTITY_MILLI,
    val unitPrice: Money? = null,
    val categoryId: String? = null,
    val subcategoryId: String? = null,
)

/**
 * A category/merchant/instrument combination the user has filed before.
 *
 * Ranked by use count with recency as the tiebreak, so a combination used every
 * week outranks one used twice yesterday -- "frequent" and "recent" in §5.4 are
 * one ordering, not two lists.
 */
public data class EntryCombo(
    val categoryId: String,
    val subcategoryId: String?,
    val merchantId: String?,
    val paymentMethodId: String?,
    val uses: Int,
    val lastUsedAt: Long,
)
