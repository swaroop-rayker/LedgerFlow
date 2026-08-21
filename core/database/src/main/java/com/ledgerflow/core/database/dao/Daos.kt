package com.ledgerflow.core.database.dao

import androidx.paging.PagingSource
import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.RewriteQueriesToDropUnusedColumns
import androidx.room.Transaction
import androidx.room.Update
import androidx.room.Upsert
import com.ledgerflow.core.database.entity.AppMetaEntity
import com.ledgerflow.core.database.entity.CategoryEntity
import com.ledgerflow.core.database.entity.CreditEntryView
import com.ledgerflow.core.database.entity.DebitEntryView
import com.ledgerflow.core.database.entity.LedgerEntryEntity
import com.ledgerflow.core.database.entity.LineItemEntity
import com.ledgerflow.core.database.entity.MerchantEntity
import com.ledgerflow.core.database.entity.PaymentMethodEntity
import com.ledgerflow.core.model.LedgerType
import kotlinx.coroutines.flow.Flow

@Dao
public interface AppMetaDao {

    @Query("SELECT value FROM app_meta WHERE `key` = :key")
    public suspend fun value(key: String): String?

    @Query("SELECT * FROM app_meta ORDER BY `key`")
    public fun observeAll(): Flow<List<AppMetaEntity>>

    @Query("SELECT * FROM app_meta ORDER BY `key`")
    public suspend fun all(): List<AppMetaEntity>

    @Upsert
    public suspend fun put(entry: AppMetaEntity)

    @Upsert
    public suspend fun putAll(entries: List<AppMetaEntity>)
}

@Dao
public interface CategoryDao {

    @Query(
        "SELECT * FROM category WHERE ledger_scope = :ledger AND deleted_at = 0 ORDER BY sort_order",
    )
    public fun observeLive(ledger: LedgerType): Flow<List<CategoryEntity>>

    @Query("SELECT * FROM category ORDER BY id")
    public suspend fun all(): List<CategoryEntity>

    @Query("SELECT * FROM category WHERE id = :id")
    public suspend fun byId(id: String): CategoryEntity?

    @Query("SELECT COUNT(*) FROM category")
    public suspend fun count(): Int

    /**
     * Collision check for the uniqueness index (§6.1.1).
     *
     * Matches on `parent_key` and `deleted_at = 0` exactly as the index does, so
     * this and the constraint can never disagree about what a duplicate is.
     * `COLLATE NOCASE` comes from the column, so "Food" finds "food".
     */
    @Query(
        "SELECT id FROM category WHERE parent_key = :parentKey AND name = :name " +
            "AND ledger_scope = :ledger AND deleted_at = 0 LIMIT 1",
    )
    public suspend fun findLiveByName(parentKey: String, name: String, ledger: LedgerType): String?

    @Query("SELECT COUNT(*) FROM category WHERE parent_id = :id AND deleted_at = 0")
    public suspend fun liveChildCount(id: String): Int

    @Insert(onConflict = OnConflictStrategy.ABORT)
    public suspend fun insert(category: CategoryEntity)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    public suspend fun insertAll(categories: List<CategoryEntity>)

    @Update
    public suspend fun update(category: CategoryEntity)

    @Query("UPDATE category SET deleted_at = :deletedAt WHERE id = :id")
    public suspend fun softDelete(id: String, deletedAt: Long)

    // -- The hidden lifecycle (ADR-0016) -------------------------------------

    /**
     * Everything hidden in one book, most recently hidden first.
     *
     * Parents and children both, unshaped: which of them the user is shown as a
     * restorable row depends on whether each child's parent is also hidden, and
     * that is a question about two rows the repository answers by combining
     * this with the live read. A SQL self-join could do it, at the cost of
     * putting presentation logic in a statement nobody would think to look in.
     */
    @Query(
        "SELECT * FROM category WHERE ledger_scope = :ledger AND deleted_at != 0 " +
            "ORDER BY deleted_at DESC, sort_order",
    )
    public fun observeHidden(ledger: LedgerType): Flow<List<CategoryEntity>>

    /**
     * The subcategories hidden *with* [parentId], identified by the stamp they
     * share.
     *
     * `softDeleteChildren` writes one `clock.nowMillis()` across the branch, so
     * the timestamp is the batch id -- and matching on it is what keeps a
     * subcategory the user hid on its own last week from being dragged back
     * into a restore of its parent today. Nothing else in the row records which
     * deletion it belonged to.
     */
    @Query(
        "SELECT * FROM category WHERE parent_id = :parentId AND deleted_at = :deletedAt " +
            "AND deleted_at != 0",
    )
    public suspend fun hiddenChildren(parentId: String, deletedAt: Long): List<CategoryEntity>

    /**
     * Hides the subcategories of [id] along with it (BUG12).
     *
     * This replaces `reparentChildren`, which moved them under the re-assign
     * target instead -- and with a null target, which is the no-entries path,
     * moved them to no parent at all: promoted to top-level categories the user
     * never created, the opposite of what its own comment claimed. That
     * statement is gone rather than merely unused, because it is a one-line way
     * to reintroduce BUG12 sitting in the first file anyone fixing a tree
     * problem would open.
     *
     * A branch is deleted as a unit and comes back as one. The stamp is what
     * makes that possible: it is the only record in the schema of which
     * deletion a hidden child belonged to.
     */
    @Query("UPDATE category SET deleted_at = :deletedAt WHERE parent_id = :id AND deleted_at = 0")
    public suspend fun softDeleteChildren(id: String, deletedAt: Long)

    /**
     * Un-hides one category.
     *
     * `AND deleted_at != 0` makes "0 rows" mean exactly "nothing hidden with
     * that id", so a stale list reports honestly instead of silently
     * succeeding.
     *
     * @return rows affected.
     */
    @Query("UPDATE category SET deleted_at = 0 WHERE id = :id AND deleted_at != 0")
    public suspend fun restore(id: String): Int

    /** Un-hides the subcategories that went out with [parentId], and only those. */
    @Query(
        "UPDATE category SET deleted_at = 0 " +
            "WHERE parent_id = :parentId AND deleted_at = :deletedAt AND deleted_at != 0",
    )
    public suspend fun restoreChildren(parentId: String, deletedAt: Long): Int

    /**
     * **Destroys the subcategories that went out with [parentId]. Irreversible.**
     *
     * Only `PurgeHiddenCategoryUseCase` may reach it. A branch is destroyed as
     * the unit it was hidden as; leaving the children behind would strand rows
     * whose `parent_id` resolves to nothing, and the hidden list -- which folds
     * a batch into its parent's row -- would then have no row to show them under.
     *
     * @return rows destroyed.
     */
    @Query(
        "DELETE FROM category " +
            "WHERE parent_id = :parentId AND deleted_at = :deletedAt AND deleted_at != 0",
    )
    public suspend fun hardDeleteChildren(parentId: String, deletedAt: Long): Int

    /**
     * **Destroys one hidden category. Irreversible.**
     *
     * Only `PurgeHiddenCategoryUseCase` may reach it, enforced by
     * `TaxonomySingleWriterTest`.
     *
     * `AND deleted_at != 0` is the same real guard `purgeDeletedEntry` carries:
     * without it this statement could destroy a *live* category by id, which is
     * a thing no screen should be able to ask for. `category_group_member`
     * cascades away by foreign key; `ledger_entry` does **not**, because
     * `category_id` has no key at all -- which is why the repository refuses to
     * call this while anything still references the row.
     *
     * @return rows destroyed.
     */
    @Query("DELETE FROM category WHERE id = :id AND deleted_at != 0")
    public suspend fun hardDelete(id: String): Int
}

@Dao
public interface MerchantDao {

    @Query("SELECT * FROM merchant WHERE deleted_at = 0 ORDER BY canonical_name")
    public fun observeLive(): Flow<List<MerchantEntity>>

    @Query("SELECT * FROM merchant ORDER BY id")
    public suspend fun all(): List<MerchantEntity>

    @Query("SELECT * FROM merchant WHERE id = :id")
    public suspend fun byId(id: String): MerchantEntity?

    @Query("SELECT * FROM merchant WHERE normalized_key = :key AND deleted_at = 0 LIMIT 1")
    public suspend fun byNormalizedKey(key: String): MerchantEntity?

    @Insert(onConflict = OnConflictStrategy.ABORT)
    public suspend fun insert(merchant: MerchantEntity)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    public suspend fun insertAll(merchants: List<MerchantEntity>)

    @Update
    public suspend fun update(merchant: MerchantEntity)

    @Query("UPDATE merchant SET deleted_at = :deletedAt WHERE id = :id")
    public suspend fun softDelete(id: String, deletedAt: Long)

    // -- The hidden lifecycle (ADR-0016) -------------------------------------

    @Query("SELECT * FROM merchant WHERE deleted_at != 0 ORDER BY deleted_at DESC")
    public fun observeHidden(): Flow<List<MerchantEntity>>

    /**
     * Lookup on the normalized key **ignoring `deleted_at`** -- the one place
     * that has to (BUG11).
     *
     * `index_merchant_normalized_key` is `UNIQUE (normalized_key)` and does not
     * include `deleted_at`, so a hidden row still occupies its key.
     * [byNormalizedKey] filters hidden rows out, which meant `createOrGet`
     * could not see the blocker and inserted straight into the constraint --
     * raising `SQLiteConstraintException` out of a repository whose whole
     * contract is typed refusals. This is how the collision is found before it
     * is caused.
     */
    @Query("SELECT * FROM merchant WHERE normalized_key = :key LIMIT 1")
    public suspend fun byNormalizedKeyAny(key: String): MerchantEntity?

    /** @return rows affected; 0 means nothing hidden had that id. */
    @Query("UPDATE merchant SET deleted_at = 0 WHERE id = :id AND deleted_at != 0")
    public suspend fun restore(id: String): Int

    /**
     * **Destroys one hidden merchant. Irreversible.**
     *
     * Only `PurgeHiddenMerchantUseCase` may reach it, enforced by
     * `TaxonomySingleWriterTest`.
     *
     * `merchant_alias` cascades away with it, which is right -- an alias for a
     * merchant that no longer exists matches nothing.
     * `ledger_entry.merchant_id` is `ON DELETE SET NULL`, which is *not* right
     * and is why the repository counts references first: left to the schema
     * this succeeds and quietly strips the shop's name off every entry that
     * ever used it.
     *
     * @return rows destroyed.
     */
    @Query("DELETE FROM merchant WHERE id = :id AND deleted_at != 0")
    public suspend fun hardDelete(id: String): Int
}

@Dao
public interface PaymentMethodDao {

    @Query("SELECT * FROM payment_method WHERE deleted_at = 0 ORDER BY label")
    public fun observeLive(): Flow<List<PaymentMethodEntity>>

    @Query("SELECT * FROM payment_method ORDER BY id")
    public suspend fun all(): List<PaymentMethodEntity>

    @Query("SELECT * FROM payment_method WHERE id = :id")
    public suspend fun byId(id: String): PaymentMethodEntity?

    @Query("SELECT COUNT(*) FROM payment_method")
    public suspend fun count(): Int

    @Query("SELECT id FROM payment_method WHERE label = :label AND deleted_at = 0 LIMIT 1")
    public suspend fun findLiveByLabel(label: String): String?

    @Insert(onConflict = OnConflictStrategy.ABORT)
    public suspend fun insert(method: PaymentMethodEntity)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    public suspend fun insertAll(methods: List<PaymentMethodEntity>)

    @Update
    public suspend fun update(method: PaymentMethodEntity)

    @Query("UPDATE payment_method SET is_default = 0 WHERE is_default = 1")
    public suspend fun clearDefault()

    @Query("UPDATE payment_method SET is_default = 1 WHERE id = :id")
    public suspend fun markDefault(id: String)

    /**
     * Exactly one default, in one transaction.
     *
     * Two rows with `is_default = 1` would make "which card did that go on?"
     * depend on row order, and the schema has no constraint that prevents it.
     */
    @Transaction
    public suspend fun setDefault(id: String) {
        clearDefault()
        markDefault(id)
    }

    @Query("UPDATE payment_method SET deleted_at = :deletedAt WHERE id = :id")
    public suspend fun softDelete(id: String, deletedAt: Long)

    // -- The hidden lifecycle (ADR-0016) -------------------------------------

    @Query("SELECT * FROM payment_method WHERE deleted_at != 0 ORDER BY deleted_at DESC")
    public fun observeHidden(): Flow<List<PaymentMethodEntity>>

    /**
     * Un-hides one payment method, as a non-default.
     *
     * `is_default = 0` in the same statement rather than in a second one.
     * Hiding does not clear the flag -- nothing reads it on a hidden row -- so
     * a plain restore of what was once the default can produce two rows both
     * claiming to be it, and which one the entry form picks then depends on row
     * order. The schema has no constraint that prevents that, so the statement
     * has to.
     *
     * @return rows affected.
     */
    @Query(
        "UPDATE payment_method SET deleted_at = 0, is_default = 0 " +
            "WHERE id = :id AND deleted_at != 0",
    )
    public suspend fun restore(id: String): Int

    /**
     * **Destroys one hidden payment method. Irreversible.**
     *
     * Only `PurgeHiddenPaymentMethodUseCase` may reach it, enforced by
     * `TaxonomySingleWriterTest`.
     *
     * The only one of the three destroys that needs no reference check first:
     * `softDelete` already cleared `payment_method_id` from every entry in both
     * books, binned ones included, so nothing points here by the time a row is
     * hidden.
     *
     * @return rows destroyed.
     */
    @Query("DELETE FROM payment_method WHERE id = :id AND deleted_at != 0")
    public suspend fun hardDelete(id: String): Int
}

/**
 * Ledger reads and writes.
 *
 * **Reads go through `debit_entries` / `credit_entries`, never `ledger_entry`**
 * (ADR-0002). The few queries that do name the base table take an explicit
 * `ledger` parameter; `LedgerIsolationTest` fails the build on any query that
 * names it without one.
 *
 * There is deliberately no query returning both ledgers, and no aggregate
 * spanning them (Law 2).
 */
@Dao
public interface LedgerEntryDao {

    // The views are full projections of ledger_entry; these list screens need
    // only a subset. RewriteQueriesToDropUnusedColumns lets Room narrow the
    // SELECT rather than reading every column off disk for each row -- and
    // keeps the views general for callers that do want the rest.
    @RewriteQueriesToDropUnusedColumns
    @Query("SELECT * FROM debit_entries ORDER BY local_date DESC, occurred_at DESC")
    public fun observeDebits(): Flow<List<DebitEntryView>>

    @RewriteQueriesToDropUnusedColumns
    @Query("SELECT * FROM credit_entries ORDER BY local_date DESC, occurred_at DESC")
    public fun observeCredits(): Flow<List<CreditEntryView>>

    /**
     * One book's entries, page by page, joined to the names the list shows.
     *
     * **Paged, not a `Flow<List<..>>`** (CLAUDE.md §8). The two `observeDebits`
     * / `observeCredits` reads above materialise a whole book and exist for
     * bounded callers -- exports, tests, the round-trip verifier. A screen the
     * user scrolls must never be one of them: a ledger grows without bound and
     * the list is the one surface §11 pins to "0 frames > 16.6 ms".
     *
     * One statement per book rather than one taking `:ledger`, for the reason
     * ADR-0002 gives: the predicate lives inside the view, so neither statement
     * can be made to return the other book's rows by passing a different
     * argument. `LedgerIsolationTest` reads both of these.
     *
     * `LEFT JOIN`, twice, and the direction matters -- an entry filed under no
     * category is a real row (§5.1 writes one with `confidence = 0` and no
     * assignment), and an inner join would make it invisible in the list while
     * still counting in every total. Neither join filters `deleted_at`: a
     * hidden merchant keeps labelling the entries it was already on (§5.5),
     * which is the entire reason merchants soft-delete rather than vanish.
     *
     * The `ORDER BY` matches the flow reads above, and the list's recency
     * headers are read off that ordering rather than regrouping a materialised
     * list.
     *
     * **`since` bounds the *view*, never the data.** It is the oldest
     * `local_date` the Ledger list shows -- 30 days back -- and nothing is
     * deleted at that boundary: analytics (§5.6 runs windows out to 5Y),
     * budgets and export all still read the whole book. The bound exists so the
     * screen a user scrolls daily stays short and recent. A caller that wants
     * everything passes `Int.MIN_VALUE`.
     */
    @Query(
        "SELECT e.id AS id, e.amount_minor AS amount_minor, e.currency AS currency, " +
            "e.local_date AS local_date, e.occurred_at AS occurred_at, e.note AS note, " +
            "c.name AS category_name, c.color_argb AS category_color_argb, " +
            "m.canonical_name AS merchant_name " +
            "FROM debit_entries e " +
            "LEFT JOIN category c ON c.id = e.category_id " +
            "LEFT JOIN merchant m ON m.id = e.merchant_id " +
            "WHERE e.local_date >= :since " +
            "ORDER BY e.local_date DESC, e.occurred_at DESC",
    )
    public fun pagingDebits(since: Int): PagingSource<Int, LedgerListRow>

    @Query(
        "SELECT e.id AS id, e.amount_minor AS amount_minor, e.currency AS currency, " +
            "e.local_date AS local_date, e.occurred_at AS occurred_at, e.note AS note, " +
            "c.name AS category_name, c.color_argb AS category_color_argb, " +
            "m.canonical_name AS merchant_name " +
            "FROM credit_entries e " +
            "LEFT JOIN category c ON c.id = e.category_id " +
            "LEFT JOIN merchant m ON m.id = e.merchant_id " +
            "WHERE e.local_date >= :since " +
            "ORDER BY e.local_date DESC, e.occurred_at DESC",
    )
    public fun pagingCredits(since: Int): PagingSource<Int, LedgerListRow>

    /**
     * Whether this book holds anything at all, ignoring the list's window.
     *
     * It exists to tell two empty states apart, and they are not the same
     * sentence: a new user has never saved an expense, while a returning one
     * may have a full ledger whose last entry predates the 30-day window. Both
     * render zero rows, and showing the first message to the second user reads
     * as "the app lost my data".
     *
     * `EXISTS` rather than `COUNT(*)`: SQLite stops at the first row it finds,
     * so this stays constant-time on a ledger of any size. Unbounded on purpose
     * -- the window is the question being answered, so it cannot also be a
     * premise.
     */
    @Query("SELECT EXISTS(SELECT 1 FROM debit_entries)")
    public fun observeHasDebits(): Flow<Boolean>

    @Query("SELECT EXISTS(SELECT 1 FROM credit_entries)")
    public fun observeHasCredits(): Flow<Boolean>

    /**
     * Soft-deletes one entry: sets `deleted_at`, leaves the row.
     *
     * **Soft, not `DELETE`.** The views already filter `deleted_at IS NULL`, so
     * setting the column removes the entry from every read path in the app
     * without touching a byte of history -- which matters because `line_item`
     * cascades on a real delete and an entry's items are the only record of how
     * a bill broke down. It also keeps the row available to a future undo and
     * to the `.lfbk` round-trip, which compares tables for row-level equality
     * (§13.1, BUG4).
     *
     * `AND ledger = :ledger` is not defensive padding. It is what makes the
     * statement legal under `LedgerIsolationTest`, and it is a real guard: an
     * id is a UUIDv7 with no book encoded in it, so without the predicate a
     * screen showing Expenses could delete a credit row by passing an id it
     * should never have had. With it, the wrong book simply affects no rows and
     * the caller gets a refusal.
     *
     * `AND deleted_at IS NULL` makes it idempotent-safe rather than merely
     * idempotent: a second confirmation on a stale row affects nothing and is
     * reported as "already gone" instead of silently overwriting the original
     * deletion timestamp.
     *
     * @return rows affected -- 0 means no live entry with that id in that book.
     */
    @Query(
        "UPDATE ledger_entry SET deleted_at = :deletedAt, updated_at = :deletedAt " +
            "WHERE id = :id AND ledger = :ledger AND deleted_at IS NULL",
    )
    public suspend fun softDeleteEntry(ledger: LedgerType, id: String, deletedAt: Long): Int

    @Query("SELECT SUM(amount_minor) FROM debit_entries WHERE local_date BETWEEN :from AND :to")
    public suspend fun debitTotal(from: Int, to: Int): Long?

    @Query("SELECT SUM(amount_minor) FROM credit_entries WHERE local_date BETWEEN :from AND :to")
    public suspend fun creditTotal(from: Int, to: Int): Long?

    /**
     * How many soft-deleted entries this book is still carrying.
     *
     * Drives the "Erase deleted entries" row in More -- its subtitle, and
     * whether it is offered at all. Reads `ledger_entry` rather than a view on
     * purpose: the views exist to hide exactly these rows, so a view cannot
     * count them.
     */
    @Query("SELECT COUNT(*) FROM ledger_entry WHERE ledger = :ledger AND deleted_at IS NOT NULL")
    public fun observeDeletedCount(ledger: LedgerType): Flow<Int>

    /**
     * One book's binned entries, newest first, with their names resolved.
     *
     * Reads `ledger_entry` because it has to: the views' whole predicate is
     * `deleted_at IS NULL`, so neither can ever return a binned row. `:ledger`
     * is still bound, which is the invariant `LedgerIsolationTest` actually
     * guards and the thing that stops a statement being aimed at the wrong
     * book.
     *
     * The screen shows both books together and merges these two result sets in
     * Kotlin (ADR-0015). **No statement spans them**, nothing is summed, and
     * every row carries its own `ledger` so the UI can sign and colour it
     * individually -- which is what keeps Law 2 true of a mixed list.
     *
     * Ordered by `occurred_at`, not `deleted_at`: the row shows the entry's own
     * date, and a list sorted by one date while displaying another reads as
     * broken.
     */
    @Query(
        "SELECT e.id AS id, e.ledger AS ledger, e.amount_minor AS amount_minor, " +
            "e.currency AS currency, e.occurred_at AS occurred_at, " +
            "e.deleted_at AS deleted_at, e.note AS note, " +
            "c.name AS category_name, c.color_argb AS category_color_argb, " +
            "s.name AS subcategory_name, m.canonical_name AS merchant_name " +
            "FROM ledger_entry e " +
            "LEFT JOIN category c ON c.id = e.category_id " +
            "LEFT JOIN category s ON s.id = e.subcategory_id " +
            "LEFT JOIN merchant m ON m.id = e.merchant_id " +
            "WHERE e.ledger = :ledger AND e.deleted_at IS NOT NULL " +
            "ORDER BY e.occurred_at DESC",
    )
    public fun observeDeleted(ledger: LedgerType): Flow<List<DeletedEntryRow>>

    /**
     * Puts one binned entry back.
     *
     * Clearing `deleted_at` is all it takes -- the views filter on that column,
     * so the entry reappears in its book, its totals and its list the moment
     * this commits. That recoverability is the entire reason
     * [softDeleteEntry] does not `DELETE`.
     *
     * `AND deleted_at IS NOT NULL` keeps it honest twice over: restoring
     * something already live affects nothing rather than stamping `updated_at`
     * for no reason, and it makes "0 rows" mean exactly "there was nothing
     * binned with that id in that book".
     *
     * @return rows affected.
     */
    @Query(
        "UPDATE ledger_entry SET deleted_at = NULL, updated_at = :updatedAt " +
            "WHERE id = :id AND ledger = :ledger AND deleted_at IS NOT NULL",
    )
    public suspend fun restoreEntry(ledger: LedgerType, id: String, updatedAt: Long): Int

    /**
     * **Destroys one binned entry. Irreversible.**
     *
     * The single-row form of [purgeDeletedEntries], for a bin where the user
     * picks what goes. `AND deleted_at IS NOT NULL` is a real guard, not
     * decoration: without it this statement could destroy a *live* entry by id,
     * which is a thing no screen should be able to ask for.
     *
     * Only `PurgeDeletedEntriesUseCase` may reach it, like its sibling.
     *
     * @return rows destroyed.
     */
    @Query(
        "DELETE FROM ledger_entry " +
            "WHERE id = :id AND ledger = :ledger AND deleted_at IS NOT NULL",
    )
    public suspend fun purgeDeletedEntry(ledger: LedgerType, id: String): Int

    /**
     * **Hard-deletes every soft-deleted entry in one book. Irreversible.**
     *
     * The one statement in the app that destroys committed ledger data. Only
     * `PurgeDeletedEntriesUseCase` may reach it, and
     * `LedgerSingleWriterTest` fails the build on any other caller.
     *
     * `line_item` and any `draft_entry` that was an in-flight edit of a purged
     * entry go with it, by the foreign keys those tables already declare --
     * `LedgerFlowDatabaseFactory` sets `PRAGMA foreign_keys = ON`, so the
     * cascade is real rather than aspirational.
     *
     * Per book, like everything else here (Law 2), which also keeps the
     * statement legal under `LedgerIsolationTest`.
     *
     * @return rows destroyed.
     */
    @Query("DELETE FROM ledger_entry WHERE ledger = :ledger AND deleted_at IS NOT NULL")
    public suspend fun purgeDeletedEntries(ledger: LedgerType): Int

    @Query("SELECT * FROM ledger_entry WHERE ledger = :ledger ORDER BY id")
    public suspend fun allForLedger(ledger: LedgerType): List<LedgerEntryEntity>

    @Query("SELECT COUNT(*) FROM ledger_entry WHERE ledger = :ledger")
    public suspend fun countForLedger(ledger: LedgerType): Int

    @Query("SELECT * FROM line_item ORDER BY id")
    public suspend fun allLineItems(): List<LineItemEntity>

    /**
     * The combinations this book has actually been filed under (§5.4).
     *
     * One statement per ledger rather than one taking `:ledger`, because the
     * grouping reads a view and there are two views. That is the shape ADR-0002
     * asks for: the predicate is part of the object, so neither statement can
     * be made to return the other book's rows by passing a different argument.
     *
     * Ordered by use count with recency as the tiebreak -- "recent" and
     * "frequent" in §5.4 are one ranking, not two lists. Entries with no
     * category are excluded: a chip that fills in nothing saves no taps.
     */
    @Query(
        "SELECT category_id, subcategory_id, merchant_id, payment_method_id, " +
            "COUNT(*) AS uses, MAX(occurred_at) AS last_used_at FROM debit_entries " +
            "WHERE category_id IS NOT NULL " +
            "GROUP BY category_id, subcategory_id, merchant_id, payment_method_id " +
            "ORDER BY uses DESC, last_used_at DESC LIMIT :limit",
    )
    public fun observeDebitCombos(limit: Int): Flow<List<EntryComboRow>>

    @Query(
        "SELECT category_id, subcategory_id, merchant_id, payment_method_id, " +
            "COUNT(*) AS uses, MAX(occurred_at) AS last_used_at FROM credit_entries " +
            "WHERE category_id IS NOT NULL " +
            "GROUP BY category_id, subcategory_id, merchant_id, payment_method_id " +
            "ORDER BY uses DESC, last_used_at DESC LIMIT :limit",
    )
    public fun observeCreditCombos(limit: Int): Flow<List<EntryComboRow>>

    /**
     * Rows violating §6.1.1's denormalisation invariant.
     *
     * `ApproveTransactionUseCase` is what stops these being written, and a
     * SQLite CHECK cannot express the rule because it needs a subquery. So this
     * is the detector: `LedgerEntryConsistencyTest` asserts it returns zero
     * after every write path the app has, which is what turns the enforcement
     * from "we remembered to check" into something a build can fail on.
     *
     * `IS NOT` rather than `<>` so a null parent (a subcategory that is really a
     * top-level category) counts as a mismatch instead of comparing to null and
     * disappearing.
     */
    @Query(
        "SELECT COUNT(*) FROM ledger_entry WHERE ledger = :ledger " +
            "AND subcategory_id IS NOT NULL AND (category_id IS NULL OR " +
            "(SELECT parent_id FROM category WHERE id = subcategory_id) IS NOT category_id)",
    )
    public suspend fun inconsistentSubcategoryCount(ledger: LedgerType): Int

    /**
     * Approval is a single transaction (CLAUDE.md §5): the entry and its line
     * items land together or not at all. A half-approved entry would be a
     * ledger row whose items are missing -- silently wrong totals.
     */
    @Transaction
    public suspend fun insertEntryWithLineItems(
        entry: LedgerEntryEntity,
        lineItems: List<LineItemEntity>,
    ) {
        insertEntry(entry)
        if (lineItems.isNotEmpty()) insertLineItems(lineItems)
    }

    @Insert(onConflict = OnConflictStrategy.ABORT)
    public suspend fun insertEntry(entry: LedgerEntryEntity)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    public suspend fun insertLineItems(lineItems: List<LineItemEntity>)
}
