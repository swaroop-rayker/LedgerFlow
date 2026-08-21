package com.ledgerflow.core.database.dao

import androidx.room.Dao
import androidx.room.Query
import com.ledgerflow.core.model.LedgerType

/**
 * What happens to `ledger_entry` when the taxonomy above it changes.
 *
 * Split out of [LedgerEntryDao], which had grown past thirty statements and was
 * carrying two jobs. The cut is not arbitrary: everything here is a write (or a
 * count feeding one) issued **by a taxonomy repository** about entries it does
 * not otherwise touch, and none of it belongs to the read/approve/bin/purge
 * lifecycle that is [LedgerEntryDao]'s subject. The block already sat behind its
 * own section comment; this makes the boundary a type.
 *
 * **Every statement binds `:ledger`,** and callers that need both books iterate
 * over `LedgerType` rather than dropping the predicate. That is not ceremony to
 * appease `LedgerIsolationTest` -- which scans this file too, being in the same
 * package -- but the honest shape: a category is ledger-scoped, so its
 * re-assignment genuinely only touches one book, and a merchant merge that spans
 * both should say so by doing it twice rather than by writing one statement that
 * quietly reaches across the partition (ADR-0002).
 */
@Dao
public interface LedgerTaxonomyDao {

    @Query(
        "SELECT COUNT(*) FROM ledger_entry WHERE ledger = :ledger " +
            "AND category_id = :categoryId AND deleted_at IS NULL",
    )
    public suspend fun countForCategory(ledger: LedgerType, categoryId: String): Int

    /**
     * Moves entries off a category being deleted.
     *
     * `subcategory_id` is cleared in the same statement. §6.1.1's invariant is
     * that a row's subcategory's parent equals its `category_id`; leaving the
     * old subcategory behind under a new parent breaks exactly that, and it is
     * the kind of inconsistency that surfaces months later as an analytics
     * bucket that does not add up.
     */
    @Query(
        "UPDATE ledger_entry SET category_id = :target, subcategory_id = NULL, " +
            "updated_at = :now WHERE ledger = :ledger AND category_id = :source",
    )
    public suspend fun reassignCategory(
        ledger: LedgerType,
        source: String,
        target: String,
        now: Long,
    )

    @Query(
        "UPDATE ledger_entry SET subcategory_id = NULL, updated_at = :now " +
            "WHERE ledger = :ledger AND subcategory_id = :source",
    )
    public suspend fun clearSubcategory(ledger: LedgerType, source: String, now: Long)

    @Query(
        "SELECT COUNT(*) FROM ledger_entry WHERE ledger = :ledger " +
            "AND merchant_id = :merchantId AND deleted_at IS NULL",
    )
    public suspend fun countForMerchant(ledger: LedgerType, merchantId: String): Int

    @Query(
        "UPDATE ledger_entry SET merchant_id = :target, updated_at = :now " +
            "WHERE ledger = :ledger AND merchant_id = :source",
    )
    public suspend fun reassignMerchant(
        ledger: LedgerType,
        source: String,
        target: String,
        now: Long,
    )

    @Query(
        "UPDATE ledger_entry SET payment_method_id = NULL, updated_at = :now " +
            "WHERE ledger = :ledger AND payment_method_id = :source",
    )
    public suspend fun clearPaymentMethod(ledger: LedgerType, source: String, now: Long)

    // -- Reference counts for a purge (ADR-0016) -----------------------------
    //
    // The two statements below look like duplicates of `countForCategory` and
    // `countForMerchant` and are not. Those two bind `deleted_at IS NULL`,
    // which is the right question before a *soft* delete: does anything the
    // user can currently see still use this row. These omit it, because a hard
    // delete does not care whether the entry is visible -- it strips the
    // reference off a binned entry exactly as thoroughly, and a binned entry
    // can still be restored from the bin, at which point it comes back missing
    // a merchant it had when it went in.
    //
    // **Keep them separate.** Collapsing the four into two by dropping the
    // predicate would over-refuse ordinary soft deletes; collapsing the other
    // way silently narrows the purge check, and nothing would fail until a
    // user restored an entry months later.

    /**
     * Entries filed **under** [categoryId] in one book, binned ones included.
     *
     * `category_id` only, deliberately -- the mirror of [countForCategory],
     * which asks the same question about live rows. An entry that merely names
     * the row as its `subcategory_id` is not counted, because it does not need
     * anywhere to go: `clearSubcategory` drops the reference and the entry keeps
     * the category it was already filed under. That is what a soft delete has
     * always done to a subcategory, and a purge changing it would mean the user
     * being asked to re-file entries that were never mis-filed.
     */
    @Query(
        "SELECT COUNT(*) FROM ledger_entry WHERE ledger = :ledger " +
            "AND category_id = :categoryId",
    )
    public suspend fun countAllForCategory(ledger: LedgerType, categoryId: String): Int

    /** Entries referencing [merchantId] in one book, **binned ones included**. */
    @Query(
        "SELECT COUNT(*) FROM ledger_entry WHERE ledger = :ledger " +
            "AND merchant_id = :merchantId",
    )
    public suspend fun countAllForMerchant(ledger: LedgerType, merchantId: String): Int
}
