package com.ledgerflow.core.database.dao

import androidx.paging.PagingSource
import androidx.room.Dao
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/**
 * The merchant entries page's reads (V2-6): one merchant's entries in one book
 * and window, and their total; and the Ledger list narrowed to merchant
 * categories. Reads only, through `debit_entries` and
 * `credit_entries` (ADR-0002), one statement per book.
 *
 * A DAO of its own rather than four more on `LedgerEntryDao`, which was at
 * detekt's limit and is the table's write path; nothing here writes.
 */
@Dao
public interface MerchantEntriesDao {

    /**
     * One merchant's debits between [from] and [to] inclusive, newest first:
     * the page Analytics opens from a merchant (V2-6). [pagingDebits]'s
     * projection, written out for the reason Home's recent rows give, and
     * narrowed by the `(ledger, merchant_id, local_date)` index the views sit
     * on, so a five-year window is a range read rather than a scan.
     */
    @Query(
        "SELECT e.id AS id, e.amount_minor AS amount_minor, e.currency AS currency, " +
            "e.local_date AS local_date, e.occurred_at AS occurred_at, e.note AS note, " +
            "c.name AS category_name, c.color_argb AS category_color_argb, " +
            "m.canonical_name AS merchant_name, " +
            "(SELECT lc.name FROM line_item li JOIN category lc ON lc.id = li.category_id " +
            "WHERE li.entry_id = e.id AND li.category_id IS NOT NULL " +
            "GROUP BY li.category_id ORDER BY SUM(li.total_minor) DESC, MIN(li.position) ASC " +
            "LIMIT 1) AS line_item_category_name, " +
            "(SELECT lc.color_argb FROM line_item li JOIN category lc ON lc.id = li.category_id " +
            "WHERE li.entry_id = e.id AND li.category_id IS NOT NULL " +
            "GROUP BY li.category_id ORDER BY SUM(li.total_minor) DESC, MIN(li.position) ASC " +
            "LIMIT 1) AS line_item_category_color_argb, " +
            "(SELECT COUNT(DISTINCT li.category_id) FROM line_item li " +
            "WHERE li.entry_id = e.id AND li.category_id IS NOT NULL) AS line_item_category_count " +
            "FROM debit_entries e " +
            "LEFT JOIN category c ON c.id = e.category_id " +
            "LEFT JOIN merchant m ON m.id = e.merchant_id " +
            "WHERE e.merchant_id = :merchantId AND e.local_date BETWEEN :from AND :to " +
            "ORDER BY e.local_date DESC, e.occurred_at DESC",
    )
    public fun pagingMerchantDebits(merchantId: String, from: Int, to: Int): PagingSource<Int, LedgerListRow>

    /** The credit book's mirror of [pagingMerchantDebits]. */
    @Query(
        "SELECT e.id AS id, e.amount_minor AS amount_minor, e.currency AS currency, " +
            "e.local_date AS local_date, e.occurred_at AS occurred_at, e.note AS note, " +
            "c.name AS category_name, c.color_argb AS category_color_argb, " +
            "m.canonical_name AS merchant_name, " +
            "(SELECT lc.name FROM line_item li JOIN category lc ON lc.id = li.category_id " +
            "WHERE li.entry_id = e.id AND li.category_id IS NOT NULL " +
            "GROUP BY li.category_id ORDER BY SUM(li.total_minor) DESC, MIN(li.position) ASC " +
            "LIMIT 1) AS line_item_category_name, " +
            "(SELECT lc.color_argb FROM line_item li JOIN category lc ON lc.id = li.category_id " +
            "WHERE li.entry_id = e.id AND li.category_id IS NOT NULL " +
            "GROUP BY li.category_id ORDER BY SUM(li.total_minor) DESC, MIN(li.position) ASC " +
            "LIMIT 1) AS line_item_category_color_argb, " +
            "(SELECT COUNT(DISTINCT li.category_id) FROM line_item li " +
            "WHERE li.entry_id = e.id AND li.category_id IS NOT NULL) AS line_item_category_count " +
            "FROM credit_entries e " +
            "LEFT JOIN category c ON c.id = e.category_id " +
            "LEFT JOIN merchant m ON m.id = e.merchant_id " +
            "WHERE e.merchant_id = :merchantId AND e.local_date BETWEEN :from AND :to " +
            "ORDER BY e.local_date DESC, e.occurred_at DESC",
    )
    public fun pagingMerchantCredits(merchantId: String, from: Int, to: Int): PagingSource<Int, LedgerListRow>

    /** What [pagingMerchantDebits] lists, summed and counted, for the page's header. */
    @Query(
        "SELECT COALESCE(SUM(amount_minor), 0) AS total_minor, COUNT(*) AS entries FROM debit_entries " +
            "WHERE merchant_id = :merchantId AND local_date BETWEEN :from AND :to",
    )
    public fun observeMerchantDebitTotal(merchantId: String, from: Int, to: Int): Flow<MerchantTotalRow>

    /** The credit book's mirror of [observeMerchantDebitTotal]. */
    @Query(
        "SELECT COALESCE(SUM(amount_minor), 0) AS total_minor, COUNT(*) AS entries FROM credit_entries " +
            "WHERE merchant_id = :merchantId AND local_date BETWEEN :from AND :to",
    )
    public fun observeMerchantCreditTotal(merchantId: String, from: Int, to: Int): Flow<MerchantTotalRow>

    /**
     * The Ledger list narrowed to merchant categories (V2-6, V2-PLAN §5.3):
     * [pagingDebits]'s projection and window, for entries whose merchant is
     * filed under one of [merchantCategoryIds] **now** (the join is read at
     * query time, so re-filing a merchant moves its entries, and Room
     * re-runs this when `merchant` changes). [uncategorised] = 1 adds the
     * merchants with no live category, as Organise and Analytics count them.
     * An entry with no merchant matches nothing: the inner join drops it.
     * [merchantCategoryIds] is never empty (Room cannot bind that); a caller
     * with only Uncategorised passes an id no category has.
     */
    @Query(
        "SELECT e.id AS id, e.amount_minor AS amount_minor, e.currency AS currency, " +
            "e.local_date AS local_date, e.occurred_at AS occurred_at, e.note AS note, " +
            "c.name AS category_name, c.color_argb AS category_color_argb, " +
            "m.canonical_name AS merchant_name, " +
            "(SELECT lc.name FROM line_item li JOIN category lc ON lc.id = li.category_id " +
            "WHERE li.entry_id = e.id AND li.category_id IS NOT NULL " +
            "GROUP BY li.category_id ORDER BY SUM(li.total_minor) DESC, MIN(li.position) ASC " +
            "LIMIT 1) AS line_item_category_name, " +
            "(SELECT lc.color_argb FROM line_item li JOIN category lc ON lc.id = li.category_id " +
            "WHERE li.entry_id = e.id AND li.category_id IS NOT NULL " +
            "GROUP BY li.category_id ORDER BY SUM(li.total_minor) DESC, MIN(li.position) ASC " +
            "LIMIT 1) AS line_item_category_color_argb, " +
            "(SELECT COUNT(DISTINCT li.category_id) FROM line_item li " +
            "WHERE li.entry_id = e.id AND li.category_id IS NOT NULL) AS line_item_category_count " +
            "FROM debit_entries e " +
            "LEFT JOIN category c ON c.id = e.category_id " +
            "JOIN merchant m ON m.id = e.merchant_id " +
            "WHERE e.local_date >= :since AND (m.merchant_category_id IN (:merchantCategoryIds) " +
            "OR (:uncategorised = 1 AND (m.merchant_category_id IS NULL OR m.merchant_category_id NOT IN " +
            "(SELECT mc.id FROM merchant_category mc WHERE mc.deleted_at = 0)))) " +
            "ORDER BY e.local_date DESC, e.occurred_at DESC",
    )
    public fun pagingDebitsInMerchantCategories(
        merchantCategoryIds: List<String>,
        uncategorised: Int,
        since: Int,
    ): PagingSource<Int, LedgerListRow>

    /** The credit book's mirror of [pagingDebitsInMerchantCategories]. */
    @Query(
        "SELECT e.id AS id, e.amount_minor AS amount_minor, e.currency AS currency, " +
            "e.local_date AS local_date, e.occurred_at AS occurred_at, e.note AS note, " +
            "c.name AS category_name, c.color_argb AS category_color_argb, " +
            "m.canonical_name AS merchant_name, " +
            "(SELECT lc.name FROM line_item li JOIN category lc ON lc.id = li.category_id " +
            "WHERE li.entry_id = e.id AND li.category_id IS NOT NULL " +
            "GROUP BY li.category_id ORDER BY SUM(li.total_minor) DESC, MIN(li.position) ASC " +
            "LIMIT 1) AS line_item_category_name, " +
            "(SELECT lc.color_argb FROM line_item li JOIN category lc ON lc.id = li.category_id " +
            "WHERE li.entry_id = e.id AND li.category_id IS NOT NULL " +
            "GROUP BY li.category_id ORDER BY SUM(li.total_minor) DESC, MIN(li.position) ASC " +
            "LIMIT 1) AS line_item_category_color_argb, " +
            "(SELECT COUNT(DISTINCT li.category_id) FROM line_item li " +
            "WHERE li.entry_id = e.id AND li.category_id IS NOT NULL) AS line_item_category_count " +
            "FROM credit_entries e " +
            "LEFT JOIN category c ON c.id = e.category_id " +
            "JOIN merchant m ON m.id = e.merchant_id " +
            "WHERE e.local_date >= :since AND (m.merchant_category_id IN (:merchantCategoryIds) " +
            "OR (:uncategorised = 1 AND (m.merchant_category_id IS NULL OR m.merchant_category_id NOT IN " +
            "(SELECT mc.id FROM merchant_category mc WHERE mc.deleted_at = 0)))) " +
            "ORDER BY e.local_date DESC, e.occurred_at DESC",
    )
    public fun pagingCreditsInMerchantCategories(
        merchantCategoryIds: List<String>,
        uncategorised: Int,
        since: Int,
    ): PagingSource<Int, LedgerListRow>
}
